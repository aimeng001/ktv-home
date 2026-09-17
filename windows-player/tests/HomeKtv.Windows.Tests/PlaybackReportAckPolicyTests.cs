using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class PlaybackReportAckPolicyTests
{
    [Fact]
    public void Typed_finished_ack_only_removes_finished_report()
    {
        var targets = PlaybackReportAckTargets.For("finished");

        Assert.True(targets.Finished);
        Assert.False(targets.PlayError);
    }

    [Fact]
    public void Typed_play_error_ack_only_removes_play_error_report()
    {
        var targets = PlaybackReportAckTargets.For("play_error");

        Assert.False(targets.Finished);
        Assert.True(targets.PlayError);
    }

    [Fact]
    public void Missing_type_keeps_legacy_acknowledgement_behavior()
    {
        var targets = PlaybackReportAckTargets.For(null);

        Assert.True(targets.Finished);
        Assert.True(targets.PlayError);
    }
}
