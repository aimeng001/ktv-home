package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.contract.QueueSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Builds bounded snapshot pages without retaining serialized copies of every
 * page. The source snapshot is already bounded by the queue service; this
 * class adds a wire-frame and aggregate budget.
 */
public final class QueueSnapshotChunker {

    private QueueSnapshotChunker() { }

    public static void stream(String eventType, QueueSnapshot snapshot, ObjectMapper mapper,
                              Consumer<QueueSnapshotChunk> consumer) {
        if (snapshot == null || snapshot.list() == null) {
            throw new QueueChunkTooLargeException(-1, "队列快照为空");
        }
        if (snapshot.list().size() > WsProtocolLimits.MAX_QUEUE_ITEMS) {
            throw new QueueChunkTooLargeException(-1, "等待队列超过条数上限");
        }

        String syncId = UUID.randomUUID().toString();
        QueueSnapshotHeader header = QueueSnapshotHeader.from(snapshot);
        int emptyEnvelopeBytes = wireBytes(mapper, new QueueSnapshotChunk(eventType, syncId,
                WsProtocolLimits.MAX_SYNC_CHUNKS - 1, WsProtocolLimits.MAX_SYNC_CHUNKS, false,
                header, List.of()), 0);
        List<List<QueueSnapshot.QueueEntry>> pages = new ArrayList<>();
        List<QueueSnapshot.QueueEntry> page = new ArrayList<>();
        int pageBytes = emptyEnvelopeBytes;
        for (QueueSnapshot.QueueEntry entry : snapshot.list()) {
            byte[] serializedEntry = BoundedJson.serialize(mapper, entry, WsProtocolLimits.MAX_MESSAGE_BYTES);
            int entryBytes = serializedEntry == null ? WsProtocolLimits.MAX_MESSAGE_BYTES + 1 : serializedEntry.length;
            if (serializedEntry == null || emptyEnvelopeBytes + entryBytes > WsProtocolLimits.MAX_MESSAGE_BYTES) {
                if (!fits(mapper, eventType, syncId, WsProtocolLimits.MAX_SYNC_CHUNKS,
                        WsProtocolLimits.MAX_SYNC_CHUNKS - 1, header, List.of(entry))) {
                    throw new QueueChunkTooLargeException(pages.size(),
                            "单个队列条目超过 WebSocket 分片上限");
                }
            }
            int commaBytes = page.isEmpty() ? 0 : 1;
            if (!page.isEmpty() && (page.size() >= WsProtocolLimits.MAX_CHUNK_ENTRIES
                    || pageBytes + commaBytes + entryBytes > WsProtocolLimits.MAX_MESSAGE_BYTES)) {
                while (!fits(mapper, eventType, syncId, WsProtocolLimits.MAX_SYNC_CHUNKS,
                        WsProtocolLimits.MAX_SYNC_CHUNKS - 1, header, page)) {
                    if (page.size() <= 1) {
                        throw new QueueChunkTooLargeException(pages.size(),
                                "单个队列条目超过 WebSocket 分片上限");
                    }
                    QueueSnapshot.QueueEntry overflow = page.remove(page.size() - 1);
                    pages.add(List.copyOf(page));
                    page.clear();
                    page.add(overflow);
                }
                pages.add(List.copyOf(page));
                page.clear();
                pageBytes = emptyEnvelopeBytes;
                commaBytes = 0;
            }
            page.add(entry);
            pageBytes += commaBytes + entryBytes;
        }
        if (!page.isEmpty()) {
            while (!fits(mapper, eventType, syncId, WsProtocolLimits.MAX_SYNC_CHUNKS,
                    WsProtocolLimits.MAX_SYNC_CHUNKS - 1, header, page)) {
                if (page.size() <= 1) {
                    throw new QueueChunkTooLargeException(pages.size(),
                            "单个队列条目超过 WebSocket 分片上限");
                }
                QueueSnapshot.QueueEntry overflow = page.remove(page.size() - 1);
                pages.add(List.copyOf(page));
                page.clear();
                page.add(overflow);
            }
            pages.add(List.copyOf(page));
        } else if (pages.isEmpty()) {
            pages.add(List.of());
        }
        if (pages.size() > WsProtocolLimits.MAX_SYNC_CHUNKS) {
            throw new QueueChunkTooLargeException(-1, "队列快照分片数量超过上限");
        }

        int total = pages.size();
        long totalBytes = 0;
        for (int index = 0; index < total; index++) {
            QueueSnapshotChunk chunk = new QueueSnapshotChunk(eventType, syncId, index, total,
                    index == total - 1, header, pages.get(index));
            int bytes = wireBytes(mapper, chunk, index);
            totalBytes += bytes;
            if (totalBytes > WsProtocolLimits.MAX_SNAPSHOT_BYTES) {
                throw new QueueChunkTooLargeException(index, "队列快照总大小超过上限");
            }
            consumer.accept(chunk);
        }
    }

    public static int wireBytes(ObjectMapper mapper, QueueSnapshotChunk chunk) {
        return wireBytes(mapper, chunk, chunk.index());
    }

    private static int wireBytes(ObjectMapper mapper, QueueSnapshotChunk chunk, int index) {
        byte[] bytes = BoundedJson.serialize(mapper, WsEvent.of(WsEvent.SNAPSHOT_CHUNK, chunk),
                WsProtocolLimits.MAX_MESSAGE_BYTES);
        if (bytes == null) {
            throw new QueueChunkTooLargeException(index,
                    "队列快照分片超过 WebSocket 大小上限");
        }
        return bytes.length;
    }

    private static boolean fits(ObjectMapper mapper, String eventType, String syncId,
                                int total, int index, QueueSnapshotHeader header,
                                List<QueueSnapshot.QueueEntry> entries) {
        QueueSnapshotChunk candidate = new QueueSnapshotChunk(eventType, syncId, index,
                total, false, header, entries);
        return BoundedJson.serialize(mapper, WsEvent.of(WsEvent.SNAPSHOT_CHUNK, candidate),
                WsProtocolLimits.MAX_MESSAGE_BYTES) != null;
    }
}
