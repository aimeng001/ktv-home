using System.IO;
using System.Net.WebSockets;

namespace HomeKtv.Windows.Playback;

/** Runs a long-lived progress callback without dying on a transient transport failure. */
public static class PlaybackProgressLoop
{
    public static async Task RunAsync(
        Func<CancellationToken, Task> tick,
        TimeSpan interval,
        CancellationToken cancellationToken,
        Action<Exception>? onTransientError = null)
    {
        ArgumentNullException.ThrowIfNull(tick);
        if (interval <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(interval));

        using var timer = new PeriodicTimer(interval);
        try
        {
            while (await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false))
            {
                try
                {
                    await tick(cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    break;
                }
                catch (WebSocketException exception)
                {
                    Report(onTransientError, exception);
                }
                catch (IOException exception)
                {
                    Report(onTransientError, exception);
                }
                catch (ObjectDisposedException exception)
                {
                    if (cancellationToken.IsCancellationRequested) break;
                    Report(onTransientError, exception);
                }
                catch (InvalidOperationException exception)
                {
                    Report(onTransientError, exception);
                }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // Normal terminal shutdown.
        }
    }

    private static void Report(Action<Exception>? report, Exception exception)
    {
        try { report?.Invoke(exception); }
        catch { /* observers must not be able to terminate the loop */ }
    }
}
