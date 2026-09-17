namespace HomeKtv.Windows.Playback;

public static class PlaybackFailurePolicy
{
    public static bool ShouldReportPlayError(Exception exception, out long? fileId)
    {
        if (exception is PlaybackAttemptException
            {
                FailureKind: PlaybackFailureKind.MediaMissing,
                FileId: var missingFileId,
            } attempt)
        {
            fileId = missingFileId;
            return true;
        }

        fileId = null;
        return false;
    }
}
