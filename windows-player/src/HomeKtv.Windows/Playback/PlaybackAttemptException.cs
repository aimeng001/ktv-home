namespace HomeKtv.Windows.Playback;

/** Describes a failed playback attempt without guessing which source failed. */
public sealed class PlaybackAttemptException : Exception
{
    public PlaybackAttemptException(long queueId, long? fileId, string message, Exception? innerException = null)
        : base(message, innerException)
    {
        QueueId = queueId;
        FileId = fileId;
    }

    public long QueueId { get; }

    public long? FileId { get; }
}
