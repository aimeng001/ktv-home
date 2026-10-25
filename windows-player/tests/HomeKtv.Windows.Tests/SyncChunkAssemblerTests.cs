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

    [Fact]
    public void Null_entries_are_normalized_to_an_empty_snapshot_list()
    {
        var assembler = new SyncChunkAssembler(maxChunks: 4, maxEntries: 4);
        var invalidlyOmittedEntries = new QueueSnapshotChunk(
            "sync_full", "empty-entries", 0, 1, true, Header(), null!);

        var result = assembler.Accept(invalidlyOmittedEntries);

        Assert.NotNull(result);
        Assert.Empty(result!.Snapshot.List);
    }

    [Fact]
    public void Interleaved_sync_ids_are_assembled_independently()
    {
        var assembler = new SyncChunkAssembler(maxChunks: 4, maxEntries: 8, maxConcurrentSyncs: 2);
        var header = new QueueSnapshotHeader(null, "idle", 60, false, "accompaniment",
            new AudioLayoutDto(), false, 0, 0, 0);

        Assert.Null(assembler.Accept(Chunk("a", 0, 2, false, header, 1)));
        Assert.Null(assembler.Accept(Chunk("b", 0, 2, false, header, 3)));
        var aResult = assembler.Accept(Chunk("a", 1, 2, true, header, 2));
        var bResult = assembler.Accept(Chunk("b", 1, 2, true, header, 4));

        Assert.NotNull(aResult);
        Assert.NotNull(bResult);
        Assert.Equal(2, aResult!.Snapshot.List.Count);
        Assert.Equal(4, bResult!.Snapshot.List[^1].QueueId);
    }

    [Theory]
    [MemberData(nameof(InvalidChunks))]
    public void Invalid_chunk_is_rejected_and_clears_partial_snapshots(
        QueueSnapshotChunk invalid, int wireBytes, int maxChunks, int maxEntries, int maxChunkBytes)
    {
        var assembler = new SyncChunkAssembler(maxChunks, maxEntries, maxChunkBytes);
        var header = Header();

        Assert.Null(assembler.Accept(Chunk("partial", 0, 2, false, header, 1)));
        Assert.Null(assembler.Accept(invalid, wireBytes));
        Assert.Null(assembler.Accept(Chunk("partial", 1, 2, true, header, 2)));
    }

    public static IEnumerable<object[]> InvalidChunks()
    {
        var header = Header();
        yield return [Chunk("wire", 0, 1, true, header, 1), 11, 4, 4, 10];
        yield return [Chunk("event", 0, 1, true, header, 1, eventType: "unknown"), 0, 4, 4, 100];
        yield return [Chunk(" ", 0, 1, true, header, 1), 0, 4, 4, 100];
        yield return [Chunk("zero-total", 0, 0, false, header, 1), 0, 4, 4, 100];
        yield return [Chunk("too-many-total", 0, 65, false, header, 1), 0, 64, 100, 100];
        yield return [Chunk("over-configured-total", 0, 2, false, header, 1), 0, 1, 4, 100];
        yield return [Chunk("negative-index", -1, 2, false, header, 1), 0, 4, 4, 100];
        yield return [Chunk("past-end-index", 1, 1, false, header, 1), 0, 4, 4, 100];
        yield return [Chunk("wrong-last-flag", 0, 2, true, header, 1), 0, 4, 4, 100];
        yield return [Chunk("missing-header", 0, 1, true, null, 1), 0, 4, 4, 100];
        yield return [Chunk("too-many-entries", 0, 1, true, header, 1,
            entries: [Entry(1), Entry(2), Entry(3)]), 0, 4, 2, 100];
    }

    [Theory]
    [InlineData("event")]
    [InlineData("total")]
    [InlineData("header")]
    public void Inconsistent_chunk_metadata_discards_only_its_own_snapshot(string mismatch)
    {
        var assembler = new SyncChunkAssembler(maxChunks: 4, maxEntries: 4);
        var header = Header();
        Assert.Null(assembler.Accept(Chunk("same", 0, 2, false, header, 1)));

        var conflicting = mismatch switch
        {
            "event" => Chunk("same", 1, 2, true, header, 2, eventType: "queue_updated"),
            "total" => Chunk("same", 1, 3, false, header, 2),
            "header" => Chunk("same", 1, 2, true, header with { Volume = 61 }, 2),
            _ => throw new ArgumentOutOfRangeException(nameof(mismatch)),
        };

        Assert.Null(assembler.Accept(conflicting));
        Assert.Null(assembler.Accept(Chunk("same", 1, 2, true, header, 2)));
    }

    [Fact]
    public void Cumulative_entry_limit_discards_an_oversized_partial_snapshot()
    {
        var assembler = new SyncChunkAssembler(maxChunks: 4, maxEntries: 1);
        var header = Header();

        Assert.Null(assembler.Accept(Chunk("large", 0, 2, false, header, 1)));
        Assert.Null(assembler.Accept(Chunk("large", 1, 2, true, header, 2)));
        Assert.Null(assembler.Accept(Chunk("large", 1, 2, true, header, 3)));
    }

    [Fact]
    public void Concurrent_sync_limit_evicts_the_oldest_partial_snapshot()
    {
        var assembler = new SyncChunkAssembler(maxChunks: 4, maxEntries: 4, maxConcurrentSyncs: 1);
        var header = Header();

        Assert.Null(assembler.Accept(Chunk("old", 0, 2, false, header, 1)));
        Assert.Null(assembler.Accept(Chunk("new", 0, 2, false, header, 3)));
        var result = assembler.Accept(Chunk("new", 1, 2, true, header, 4));

        Assert.NotNull(result);
        Assert.Equal(new long?[] { 3, 4 }, result!.Snapshot.List.Select(entry => entry.QueueId));
        Assert.Null(assembler.Accept(Chunk("old", 1, 2, true, header, 2)));
    }

    [Fact]
    public void Reset_discards_a_partial_snapshot()
    {
        var assembler = new SyncChunkAssembler(maxChunks: 4, maxEntries: 4);
        var header = Header();

        Assert.Null(assembler.Accept(Chunk("reset", 0, 2, false, header, 1)));
        assembler.Reset();

        Assert.Null(assembler.Accept(Chunk("reset", 1, 2, true, header, 2)));
    }

    [Fact]
    public void Constructor_rejects_nonpositive_limits()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new SyncChunkAssembler(maxChunks: 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => new SyncChunkAssembler(maxEntries: 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => new SyncChunkAssembler(maxChunkBytes: 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => new SyncChunkAssembler(maxConcurrentSyncs: 0));
    }

    private static QueueSnapshotChunk Chunk(string id, int index, int total, bool last,
        QueueSnapshotHeader? header, long queueId, string eventType = "sync_full",
        IReadOnlyList<QueueEntry>? entries = null) => new(
        eventType, id, index, total, last, header,
        entries ?? [Entry(queueId)]);

    private static QueueEntry Entry(long queueId) => new(queueId, null, null, null);

    private static QueueSnapshotHeader Header() => new(null, "idle", 60, false, "accompaniment",
        new AudioLayoutDto(), false, 0, 0, 0);
}
