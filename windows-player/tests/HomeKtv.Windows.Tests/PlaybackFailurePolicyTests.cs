using HomeKtv.Windows.Mpv;
using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.Tests;

public sealed class PlaybackFailurePolicyTests
{
    [Fact]
    public void Transcode_failure_reports_play_error_for_confirmed_unplayable_variant()
    {
        var attempt = new PlaybackAttemptException(
            42L, 99L, "MV transcode failed", PlaybackFailureKind.TranscodeFailure);

        var shouldReport = PlaybackFailurePolicy.ShouldReportPlayError(attempt, out var fileId);

        Assert.True(shouldReport);
        Assert.Equal(99L, fileId);
    }
    [Fact]
    public void MediaLoadFailure_DoesNotReportPlayError_WhenMediaExistenceIsUnconfirmed()
    {
        var attempt = new PlaybackAttemptException(42L, 99L, "Failed to load media file");

        var shouldReport = PlaybackFailurePolicy.ShouldReportPlayError(attempt, out var fileId);

        Assert.False(shouldReport);
        Assert.Null(fileId);
    }

    [Fact]
    public void ConfirmedMissingMedia_ReportsPlayError_WithNullFileId()
    {
        var attempt = new PlaybackAttemptException(
            42L, null, "Song no longer exists.", PlaybackFailureKind.MediaMissing);

        var shouldReport = PlaybackFailurePolicy.ShouldReportPlayError(attempt, out var fileId);

        Assert.True(shouldReport);
        Assert.Null(fileId);
    }

    [Fact]
    public void SourceNotReady_DoesNotReportPlayError()
    {
        var attempt = new PlaybackAttemptException(
            42L, null, "No ready file source", PlaybackFailureKind.SourceUnavailable);

        var shouldReport = PlaybackFailurePolicy.ShouldReportPlayError(attempt, out var fileId);

        Assert.False(shouldReport);
        Assert.Null(fileId);
    }

    [Fact]
    public void ProjectionCommandFailure_DoesNotReportPlayError()
    {
        var commandException = new MpvCommandException("Failed to set audio filter");

        var shouldReport = PlaybackFailurePolicy.ShouldReportPlayError(commandException, out var fileId);

        Assert.False(shouldReport);
        Assert.Null(fileId);
    }

    [Fact]
    public void GenericException_DoesNotReportPlayError()
    {
        var genericException = new InvalidOperationException("Unexpected state");

        var shouldReport = PlaybackFailurePolicy.ShouldReportPlayError(genericException, out var fileId);

        Assert.False(shouldReport);
        Assert.Null(fileId);
    }
}
