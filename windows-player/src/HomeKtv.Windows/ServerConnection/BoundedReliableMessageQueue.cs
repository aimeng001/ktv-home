namespace HomeKtv.Windows.ServerConnection;

/**
 * Compatibility facade for the old string-only queue API. New code should use
 * ReliablePlaybackOutbox so entries have an idempotency key and ACK lifecycle.
 */
[Obsolete("Use ReliablePlaybackOutbox for keyed, persistent reliable messages.")]
public sealed class BoundedReliableMessageQueue : ReliablePlaybackOutbox
{
    public new const int MaxMessages = ReliablePlaybackOutbox.MaxMessages;

    public bool TryEnqueue(string message)
    {
        var byteCount = Utf8ByteBudget.GetByteCountAtMost(message, Utf8ByteBudget.MaxMessageBytes);
        return byteCount is not null
            && Enqueue(new ReliableMessage(Guid.NewGuid().ToString("N"), message, byteCount.Value))
                == ReliableEnqueueResult.Enqueued;
    }

    public bool TryPeek(out string? message)
    {
        if (TryPeekMessage(out var item))
        {
            message = item!.Text;
            return true;
        }

        message = null;
        return false;
    }

    public bool TryDequeue(out string? message)
    {
        if (TryDequeueMessage(out var item))
        {
            message = item!.Text;
            return true;
        }

        message = null;
        return false;
    }
}
