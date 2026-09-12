using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.IO;

namespace HomeKtv.Windows.ServerConnection;

public enum ReliableEnqueueResult
{
    Enqueued,
    AlreadyQueued,
    Rejected,
}

public enum ReliableSendResult
{
    Sent,
    Queued,
    AlreadyQueued,
    Rejected,
}

public sealed record ReliableMessage(string Key, string Text, int Utf8Bytes);

/**
 * Bounded, keyed outbox for reports that must survive reconnects and process
 * restarts. Entries remain until the server returns a terminal acknowledgement.
 */
public class ReliablePlaybackOutbox
{
    public const int MaxMessages = 256;
    public const int MaxBytes = 4 * 1024 * 1024;

    private static readonly HashSet<string> TerminalStatuses = new(StringComparer.Ordinal)
    {
        "APPLIED",
        "ALREADY_APPLIED",
        "STALE",
    };

    private readonly object gate = new();
    private readonly List<ReliableMessage> items = new();
    private readonly Action<IReadOnlyList<ReliableMessage>>? persist;
    private readonly int maxMessages;
    private readonly int maxBytes;
    private int bufferedBytes;

    public ReliablePlaybackOutbox(
        IEnumerable<ReliableMessage>? initialMessages = null,
        Action<IReadOnlyList<ReliableMessage>>? persist = null,
        int maxMessages = MaxMessages,
        int maxBytes = MaxBytes)
    {
        if (maxMessages <= 0) throw new ArgumentOutOfRangeException(nameof(maxMessages));
        if (maxBytes <= 0) throw new ArgumentOutOfRangeException(nameof(maxBytes));
        this.persist = persist;
        this.maxMessages = maxMessages;
        this.maxBytes = maxBytes;

        if (initialMessages is null) return;
        foreach (var message in initialMessages)
        {
            if (!TryNormalize(message, out var normalized)
                || items.Any(item => item.Key == normalized.Key)
                || items.Count >= maxMessages
                || bufferedBytes + normalized.Utf8Bytes > maxBytes)
            {
                continue;
            }

            items.Add(normalized);
            bufferedBytes += normalized.Utf8Bytes;
        }
    }

    public int Count
    {
        get { lock (gate) return items.Count; }
    }

    public int BufferedBytes
    {
        get { lock (gate) return bufferedBytes; }
    }

    public ReliableEnqueueResult Enqueue(ReliableMessage message)
    {
        if (!TryNormalize(message, out var normalized)) return ReliableEnqueueResult.Rejected;

        lock (gate)
        {
            if (items.Any(item => item.Key == normalized.Key))
                return ReliableEnqueueResult.AlreadyQueued;
            if (items.Count >= maxMessages || bufferedBytes + normalized.Utf8Bytes > maxBytes)
                return ReliableEnqueueResult.Rejected;

            items.Add(normalized);
            bufferedBytes += normalized.Utf8Bytes;
            if (!PersistUnsafe())
            {
                items.RemoveAt(items.Count - 1);
                bufferedBytes -= normalized.Utf8Bytes;
                return ReliableEnqueueResult.Rejected;
            }
            return ReliableEnqueueResult.Enqueued;
        }
    }

    public IReadOnlyList<ReliableMessage> Snapshot()
    {
        lock (gate) return items.ToArray();
    }

    public bool TryPeekMessage(out ReliableMessage? message)
    {
        lock (gate)
        {
            message = items.Count == 0 ? null : items[0];
            return message is not null;
        }
    }

    public bool TryDequeueMessage(out ReliableMessage? message)
    {
        lock (gate)
        {
            if (items.Count == 0)
            {
                message = null;
                return false;
            }

            message = items[0];
            items.RemoveAt(0);
            bufferedBytes -= message.Utf8Bytes;
            if (!PersistUnsafe())
            {
                items.Insert(0, message);
                bufferedBytes += message.Utf8Bytes;
                message = null;
                return false;
            }
            return true;
        }
    }

    public bool Acknowledge(string key, string? status)
    {
        if (string.IsNullOrWhiteSpace(key) || status is null || !TerminalStatuses.Contains(status))
            return false;

        lock (gate)
        {
            var index = items.FindIndex(item => item.Key == key);
            if (index < 0) return false;

            var removed = items[index];
            items.RemoveAt(index);
            bufferedBytes -= removed.Utf8Bytes;
            if (!PersistUnsafe())
            {
                items.Insert(index, removed);
                bufferedBytes += removed.Utf8Bytes;
                return false;
            }
            return true;
        }
    }

    public bool Remove(string key)
    {
        if (string.IsNullOrWhiteSpace(key)) return false;

        lock (gate)
        {
            var index = items.FindIndex(item => item.Key == key);
            if (index < 0) return false;

            var removed = items[index];
            items.RemoveAt(index);
            bufferedBytes -= removed.Utf8Bytes;
            if (!PersistUnsafe())
            {
                items.Insert(index, removed);
                bufferedBytes += removed.Utf8Bytes;
                return false;
            }
            return true;
        }
    }

    private bool TryNormalize(ReliableMessage? message, out ReliableMessage normalized)
    {
        normalized = null!;
        if (message is null || string.IsNullOrWhiteSpace(message.Key)) return false;
        var byteCount = Utf8ByteBudget.GetByteCountAtMost(message.Text, Math.Min(
            Utf8ByteBudget.MaxMessageBytes, maxBytes));
        if (byteCount is null || byteCount <= 0) return false;

        normalized = message with { Utf8Bytes = byteCount.Value };
        return true;
    }

    private bool PersistUnsafe()
    {
        try
        {
            persist?.Invoke(items.ToArray());
            return true;
        }
        catch (Exception) when (persist is not null)
        {
            return false;
        }
    }
}

public sealed class ReliablePlaybackOutboxStore
{
    private readonly string path;

    public ReliablePlaybackOutboxStore(string? path = null, string? scope = null)
    {
        this.path = path ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "HomeKtv.Windows", $"reliable-playback-{ScopeHash(scope ?? "default")}.json");
    }

    public IReadOnlyList<ReliableMessage> Load()
    {
        try
        {
            var info = new FileInfo(path);
            if (!info.Exists || info.Length > ReliablePlaybackOutbox.MaxBytes * 2L) return [];
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            return JsonSerializer.Deserialize<List<ReliableMessage>>(stream) ?? [];
        }
        catch (IOException) { return []; }
        catch (UnauthorizedAccessException) { return []; }
        catch (JsonException) { return []; }
    }

    public void Save(IReadOnlyList<ReliableMessage> messages)
    {
        if (messages.Count > ReliablePlaybackOutbox.MaxMessages
            || messages.Sum(message => (long)message.Utf8Bytes) > ReliablePlaybackOutbox.MaxBytes)
        {
            throw new InvalidOperationException("Reliable playback outbox exceeds its storage budget.");
        }

        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(directory)) Directory.CreateDirectory(directory);
        var temporaryPath = path + ".tmp";
        try
        {
            using (var stream = new FileStream(temporaryPath, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                JsonSerializer.Serialize(stream, messages);
            }

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
