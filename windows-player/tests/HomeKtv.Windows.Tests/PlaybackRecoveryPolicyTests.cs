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

    [Fact]
    public void Constructor_rejects_non_positive_attempts_and_negative_backoff()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new PlaybackRecoveryPolicy(maxAttempts: 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => new PlaybackRecoveryPolicy(
            maxAttempts: 1, initialBackoff: TimeSpan.FromMilliseconds(-1)));
    }

    [Fact]
    public void Uses_the_default_backoff_when_none_is_configured()
    {
        var policy = new PlaybackRecoveryPolicy(maxAttempts: 2);

        Assert.True(policy.TryBegin(out var first));
        Assert.Equal(TimeSpan.Zero, first);
        Assert.True(policy.TryBegin(out var second));
        Assert.Equal(TimeSpan.FromMilliseconds(250), second);
    }

    [Fact]
    public void Successful_playback_resets_the_recovery_budget()
    {
        var policy = new PlaybackRecoveryPolicy(maxAttempts: 2,
            initialBackoff: TimeSpan.FromMilliseconds(10));

        Assert.True(policy.TryBegin(out _));
        Assert.True(policy.TryBegin(out _));
        Assert.False(policy.TryBegin(out _));

        policy.MarkSuccess();

        Assert.True(policy.TryBegin(out var backoff));
        Assert.Equal(TimeSpan.Zero, backoff);
    }

    [Fact]
    public void Exponential_backoff_is_capped_at_five_seconds()
    {
        var policy = new PlaybackRecoveryPolicy(maxAttempts: 8,
            initialBackoff: TimeSpan.FromSeconds(1));
        var backoffs = new List<TimeSpan>();

        for (var attempt = 0; attempt < 6; attempt++)
        {
            Assert.True(policy.TryBegin(out var backoff));
            backoffs.Add(backoff);
        }

        Assert.Equal(new[]
        {
            TimeSpan.Zero,
            TimeSpan.FromSeconds(1),
            TimeSpan.FromSeconds(2),
            TimeSpan.FromSeconds(4),
            TimeSpan.FromSeconds(5),
            TimeSpan.FromSeconds(5),
        }, backoffs);
    }
}
