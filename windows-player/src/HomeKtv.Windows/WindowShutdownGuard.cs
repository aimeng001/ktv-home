namespace HomeKtv.Windows;

/** Keeps asynchronous window cleanup failures out of WPF's dispatcher exception path. */
public static class WindowShutdownGuard
{
    public static async Task RunAsync(Func<Task> cleanup, Action<Exception>? report = null)
    {
        ArgumentNullException.ThrowIfNull(cleanup);
        try
        {
            await cleanup().ConfigureAwait(true);
        }
        catch (OperationCanceledException)
        {
            // Cancellation is expected while the player is closing.
        }
        catch (Exception exception)
        {
            try { report?.Invoke(exception); }
            catch { /* cleanup reporting must not rethrow during shutdown */ }
        }
    }
}
