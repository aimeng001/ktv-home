using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace HomeKtv.Windows.ServerConnection;

public sealed record PendingPlaybackReport(long QueueId)
{
    public string Serialize(long generation)
    {
        if (QueueId <= 0) throw new ArgumentOutOfRangeException(nameof(QueueId));
        if (generation <= 0) throw new ArgumentOutOfRangeException(nameof(generation));
        return ServerMessageFactory.Finished(QueueId, generation);
    }
}

/**
 * In-memory completion outbox with an optional best-effort persistence hook.
 * Queue IDs are the idempotency keys, so an unacknowledged report may be sent
 * again after a reconnect without creating a second history row.
 */
public sealed class PendingPlaybackReportQueue
{
    public const int MaxPending = 100;
    private static readonly HashSet<string> TerminalStatuses = new(StringComparer.Ordinal)
    {
        "APPLIED",
        "ALREADY_APPLIED",
        "STALE",
    };

    private readonly object gate = new();
    private readonly List<long> pending;
    private readonly Action<IReadOnlyList<long>>? persist;

    public PendingPlaybackReportQueue(
        IEnumerable<long>? initialQueueIds = null,
        Action<IReadOnlyList<long>>? persist = null)
    {
        pending = initialQueueIds is null
            ? []
            : initialQueueIds
                .Where(queueId => queueId > 0)
                .Distinct()
                .Take(MaxPending)
                .ToList();
        this.persist = persist;
    }

    public IReadOnlyList<long> PendingQueueIds
    {
        get
        {
            lock (gate) return pending.ToArray();
        }
    }

    public bool Enqueue(long queueId)
    {
        if (queueId <= 0) return false;
        lock (gate)
        {
            if (pending.Contains(queueId) || pending.Count >= MaxPending) return false;
            pending.Add(queueId);
            PersistUnsafe();
            return true;
        }
    }

    public IReadOnlyList<PendingPlaybackReport> Snapshot()
    {
        lock (gate) return pending.Select(queueId => new PendingPlaybackReport(queueId)).ToArray();
    }

    public void Acknowledge(long queueId, string? status)
    {
        if (queueId <= 0 || status is null || !TerminalStatuses.Contains(status)) return;
        lock (gate)
        {
            if (!pending.Remove(queueId)) return;
            PersistUnsafe();
        }
    }

    /** Reconnects do not invalidate a report; it is re-encoded with the next generation. */
    public void OnDisconnected() { }

    private void PersistUnsafe()
    {
        try
        {
            persist?.Invoke(pending.ToArray());
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}

public sealed class PendingPlaybackReportStore
{
    private readonly string path;

    public PendingPlaybackReportStore(string? path = null, string? scope = null)
    {
        this.path = path ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "HomeKtv.Windows", $"pending-finished-{ScopeHash(scope ?? "default")}.json");
    }

    public IReadOnlyList<long> Load()
    {
        try
        {
            if (!File.Exists(path)) return [];
            return JsonSerializer.Deserialize<long[]>(File.ReadAllText(path))?
                .Where(queueId => queueId > 0)
                .Distinct()
                .Take(PendingPlaybackReportQueue.MaxPending)
                .ToArray() ?? [];
        }
        catch (IOException) { return []; }
        catch (UnauthorizedAccessException) { return []; }
        catch (JsonException) { return []; }
    }

    public void Save(IReadOnlyList<long> queueIds)
    {
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(directory)) Directory.CreateDirectory(directory);
        var json = JsonSerializer.Serialize(queueIds.Take(PendingPlaybackReportQueue.MaxPending));
        var temporaryPath = path + ".tmp";
        try
        {
            File.WriteAllText(temporaryPath, json);
            if (File.Exists(path)) File.Replace(temporaryPath, path, null);
            else File.Move(temporaryPath, path);
        }
        finally
        {
            if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
        }
    }

    private static string ScopeHash(string scope)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(scope));
        return Convert.ToHexString(hash)[..16].ToLowerInvariant();
    }
}
