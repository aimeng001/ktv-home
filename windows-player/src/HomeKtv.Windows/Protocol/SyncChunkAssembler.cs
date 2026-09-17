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
    private sealed class AssemblyState
    {
        public required string EventType { get; init; }
        public required int Total { get; init; }
        public required QueueSnapshotHeader Header { get; init; }
        public Dictionary<int, IReadOnlyList<QueueEntry>> Chunks { get; } = new();
        public int EntryCount { get; set; }
        public long LastTouched { get; set; }
    }

    private readonly Dictionary<string, AssemblyState> assemblies = new(StringComparer.Ordinal);
    private readonly int maxChunks;
    private readonly int maxEntries;
    private readonly int maxChunkBytes;
    private readonly int maxConcurrentSyncs;
    private long touchSequence;

    public SyncChunkAssembler(
        int maxChunks = MaxSyncChunks,
        int maxEntries = MaxQueueEntries,
        int maxChunkBytes = MaxMessageBytes,
        int maxConcurrentSyncs = 4)
    {
        if (maxChunks <= 0 || maxEntries <= 0 || maxChunkBytes <= 0 || maxConcurrentSyncs <= 0)
            throw new ArgumentOutOfRangeException();
        this.maxChunks = maxChunks;
        this.maxEntries = maxEntries;
        this.maxChunkBytes = maxChunkBytes;
        this.maxConcurrentSyncs = maxConcurrentSyncs;
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

            if (!assemblies.TryGetValue(chunk.SyncId, out var assembly))
            {
                if (assemblies.Count >= maxConcurrentSyncs)
                {
                    var oldest = assemblies.MinBy(item => item.Value.LastTouched).Key;
                    assemblies.Remove(oldest);
                }

                assembly = new AssemblyState
                {
                    EventType = chunk.EventType,
                    Total = chunk.Total,
                    Header = chunk.Header,
                    LastTouched = ++touchSequence,
                };
                assemblies.Add(chunk.SyncId, assembly);
            }
            else if (assembly.EventType != chunk.EventType
                || assembly.Total != chunk.Total
                || !Equals(assembly.Header, chunk.Header))
            {
                assemblies.Remove(chunk.SyncId);
                return null;
            }
            assembly.LastTouched = ++touchSequence;

            if (assembly.Chunks.ContainsKey(chunk.Index))
            {
                assemblies.Remove(chunk.SyncId);
                return null;
            }
            if (assembly.EntryCount + entries.Count > maxEntries)
            {
                assemblies.Remove(chunk.SyncId);
                return null;
            }

            assembly.Chunks[chunk.Index] = entries;
            assembly.EntryCount += entries.Count;
            if (assembly.Chunks.Count != assembly.Total) return null;

            var combined = new List<QueueEntry>(assembly.EntryCount);
            for (var index = 0; index < assembly.Total; index++)
            {
                if (!assembly.Chunks.TryGetValue(index, out var page))
                {
                    assemblies.Remove(chunk.SyncId);
                    return null;
                }
                combined.AddRange(page);
            }

            var result = new AssembledSnapshot(
                assembly.EventType,
                assembly.Header.ToSnapshot(combined));
            assemblies.Remove(chunk.SyncId);
            return result;
        }
    }

    public void Reset()
    {
        lock (gate) ResetUnsafe();
    }

    private void ResetUnsafe()
    {
        assemblies.Clear();
    }
}
