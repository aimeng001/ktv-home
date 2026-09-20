namespace HomeKtv.Windows.Playback;

public enum PlaybackFailureKind
{
    Unknown,
    MediaMissing,
    SourceUnavailable,
    TranscodeFailure,
    OutputFailure,
}

/** Describes a failed playback attempt without guessing which source failed. */
public sealed class PlaybackAttemptException : Exception
{
    public PlaybackAttemptException(long queueId, long? fileId, string message, Exception? innerException = null)
        : this(queueId, fileId, message, PlaybackFailureKind.Unknown, innerException)
    {
    }

    public PlaybackAttemptException(
        long queueId,
        long? fileId,
        string message,
        PlaybackFailureKind failureKind,
        Exception? innerException = null)
        : base(message, innerException)
    {
        QueueId = queueId;
        FileId = fileId;
        FailureKind = failureKind;
    }

    public long QueueId { get; }

    public long? FileId { get; }

    public PlaybackFailureKind FailureKind { get; }
}
