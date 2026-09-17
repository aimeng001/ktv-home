namespace HomeKtv.Windows.Playback;

/** Bounds repeated renderer recovery and supplies a small exponential backoff. */
public sealed class PlaybackRecoveryPolicy
{
    private readonly object sync = new();
    private readonly int maxAttempts;
    private readonly TimeSpan initialBackoff;
    private int attempts;

    public PlaybackRecoveryPolicy(int maxAttempts = 3,
        TimeSpan? initialBackoff = null)
    {
        if (maxAttempts <= 0) throw new ArgumentOutOfRangeException(nameof(maxAttempts));
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff ?? TimeSpan.FromMilliseconds(250);
        if (this.initialBackoff < TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(initialBackoff));
    }

    public bool TryBegin(out TimeSpan backoff)
    {
        lock (sync)
        {
            if (attempts >= maxAttempts)
            {
                backoff = TimeSpan.Zero;
                return false;
            }

            backoff = attempts == 0
                ? TimeSpan.Zero
                : TimeSpan.FromTicks(Math.Min(
                    TimeSpan.FromSeconds(5).Ticks,
                    initialBackoff.Ticks * (1L << Math.Min(attempts - 1, 4))));
            attempts++;
            return true;
        }
    }

    public void MarkSuccess()
    {
        lock (sync) attempts = 0;
    }
}
