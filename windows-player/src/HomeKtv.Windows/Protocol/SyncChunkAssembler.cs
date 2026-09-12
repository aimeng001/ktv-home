namespace HomeKtv.Windows.Protocol;

public sealed record AssembledSnapshot(string EventType, QueueSnapshot Snapshot);

/**
 * Bounded, atomic assembly of server snapshot chunks. Partial or invalid
 * synchronization is never exposed to the playback layer.
 */
public sealed class SyncChunkAssembler
{
    private const int MaxMessageBytes = 1_048_576;
    private const int MaxQueueEntries = 1_000;
    private const int MaxSyncChunks = 64;
    private static readonly HashSet<string> SnapshotEvents = new(StringComparer.Ordinal)
    {
        "sync_full",
        "queue_updated",
        "now_playing",
        "player_state",
        "playback_restarted",
        "playback_seeked",
        "volume_changed",
        "vocal_changed",
    };

    private readonly object gate = new();
    private readonly Dictionary<int, IReadOnlyList<QueueEntry>> chunks = new();
    private readonly int maxChunks;
    private readonly int maxEntries;
    private readonly int maxChunkBytes;
    private string? syncId;
    private string? eventType;
    private int total;
    private QueueSnapshotHeader? header;
    private int entryCount;

    public SyncChunkAssembler(
        int maxChunks = MaxSyncChunks,
        int maxEntries = MaxQueueEntries,
        int maxChunkBytes = MaxMessageBytes)
    {
        if (maxChunks <= 0 || maxEntries <= 0 || maxChunkBytes <= 0)
            throw new ArgumentOutOfRangeException();
        this.maxChunks = maxChunks;
        this.maxEntries = maxEntries;
        this.maxChunkBytes = maxChunkBytes;
    }

    public AssembledSnapshot? Accept(QueueSnapshotChunk chunk, int wireBytes = 0)
    {
        lock (gate)
        {
            var entries = chunk.Entries ?? Array.Empty<QueueEntry>();
            if (wireBytes > maxChunkBytes
                || !SnapshotEvents.Contains(chunk.EventType)
                || string.IsNullOrWhiteSpace(chunk.SyncId)
                || chunk.Total is < 1 or > 64
                || chunk.Total > maxChunks
                || chunk.Index < 0
                || chunk.Index >= chunk.Total
                || chunk.Last != (chunk.Index == chunk.Total - 1)
                || chunk.Header is null
                || entries.Count > maxEntries)
            {
                ResetUnsafe();
                return null;
            }

            if (syncId is null)
            {
                syncId = chunk.SyncId;
                eventType = chunk.EventType;
                total = chunk.Total;
                header = chunk.Header;
            }
            else if (syncId != chunk.SyncId)
            {
                ResetUnsafe();
                syncId = chunk.SyncId;
                eventType = chunk.EventType;
                total = chunk.Total;
                header = chunk.Header;
            }
            else if (eventType != chunk.EventType
                || total != chunk.Total
                || !Equals(header, chunk.Header))
            {
                ResetUnsafe();
                return null;
            }

            if (chunks.ContainsKey(chunk.Index))
            {
                ResetUnsafe();
                return null;
            }
            if (entryCount + entries.Count > maxEntries)
            {
                ResetUnsafe();
                return null;
            }

            chunks[chunk.Index] = entries;
            entryCount += entries.Count;
            if (chunks.Count != total) return null;

            var combined = new List<QueueEntry>(entryCount);
            for (var index = 0; index < total; index++)
            {
                if (!chunks.TryGetValue(index, out var page))
                {
                    ResetUnsafe();
                    return null;
                }
                combined.AddRange(page);
            }

            var result = new AssembledSnapshot(
                eventType!,
                header!.ToSnapshot(combined));
            ResetUnsafe();
            return result;
        }
    }

    public void Reset()
    {
        lock (gate) ResetUnsafe();
    }

    private void ResetUnsafe()
    {
        syncId = null;
        eventType = null;
        total = 0;
        header = null;
        entryCount = 0;
        chunks.Clear();
    }
}
