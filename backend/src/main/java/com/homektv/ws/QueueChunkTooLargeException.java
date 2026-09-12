package com.homektv.ws;

/** Raised when a bounded snapshot cannot be represented by the wire protocol. */
public class QueueChunkTooLargeException extends IllegalStateException {
    private final int chunkIndex;

    public QueueChunkTooLargeException(int chunkIndex, String message) {
        super(message);
        this.chunkIndex = chunkIndex;
    }

    public QueueChunkTooLargeException(int chunkIndex, Throwable cause) {
        this(chunkIndex, "队列快照分片超过 WebSocket 大小上限", cause);
    }

    private QueueChunkTooLargeException(int chunkIndex, String message, Throwable cause) {
        super(message, cause);
        this.chunkIndex = chunkIndex;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }
}
