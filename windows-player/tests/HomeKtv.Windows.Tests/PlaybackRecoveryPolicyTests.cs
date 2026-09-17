using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.Tests;

public sealed class PlaybackRecoveryPolicyTests
{
    [Fact]
    public void Recovery_attempts_are_bounded_and_backed_off()
    {
        var policy = new PlaybackRecoveryPolicy(maxAttempts: 3,
            initialBackoff: TimeSpan.FromMilliseconds(10));

        Assert.True(policy.TryBegin(out var first));
        Assert.Equal(TimeSpan.Zero, first);
        Assert.True(policy.TryBegin(out var second));
        Assert.Equal(TimeSpan.FromMilliseconds(10), second);
        Assert.True(policy.TryBegin(out var third));
        Assert.Equal(TimeSpan.FromMilliseconds(20), third);
        Assert.False(policy.TryBegin(out _));
    }
}
