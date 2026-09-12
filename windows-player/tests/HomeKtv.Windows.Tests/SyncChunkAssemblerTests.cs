using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Tests;

public sealed class SyncChunkAssemblerTests
{
    [Fact]
    public void Snapshot_is_applied_only_after_all_chunks_arrive()
    {
        var assembler = new SyncChunkAssembler(maxChunks: 4, maxEntries: 4);
        var header = new QueueSnapshotHeader(null, "idle", 60, false, "accompaniment",
            new AudioLayoutDto(), false, 0, 0, 0);
        var first = Chunk("sync-1", 0, 2, false, header, 1);
        var last = Chunk("sync-1", 1, 2, true, header, 2);

        Assert.Null(assembler.Accept(last));
        var result = assembler.Accept(first);

        Assert.NotNull(result);
        Assert.Equal("sync_full", result!.EventType);
        Assert.Equal(new long?[] { 1, 2 }, result.Snapshot.List.Select(item => item.QueueId));
    }

    [Fact]
    public void Duplicate_chunk_resets_the_partial_snapshot_and_does_not_apply_it()
    {
        var assembler = new SyncChunkAssembler(maxChunks: 4, maxEntries: 4);
        var header = new QueueSnapshotHeader(null, "idle", 60, false, "accompaniment",
            new AudioLayoutDto(), false, 0, 0, 0);
        var first = Chunk("sync-2", 0, 2, false, header, 1);
        var last = Chunk("sync-2", 1, 2, true, header, 2);

        Assert.Null(assembler.Accept(first));
        Assert.Null(assembler.Accept(first));
        Assert.Null(assembler.Accept(last));
    }

    [Fact]
    public void Missing_header_is_rejected_instead_of_crashing_or_using_defaults()
    {
        var assembler = new SyncChunkAssembler(maxChunks: 4, maxEntries: 4);
        var invalid = Chunk("sync-missing-header", 0, 1, true, null, 1);

        Assert.Null(assembler.Accept(invalid));
    }

    private static QueueSnapshotChunk Chunk(string id, int index, int total, bool last,
        QueueSnapshotHeader? header, long queueId) => new(
        "sync_full", id, index, total, last, header,
        new[] { new QueueEntry(queueId, null, null, null) });
}
