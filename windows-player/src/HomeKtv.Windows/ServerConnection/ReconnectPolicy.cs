namespace HomeKtv.Windows.ServerConnection;

public static class ReconnectPolicy
{
    private static readonly int[] BackoffMilliseconds = [1_000, 2_000, 5_000, 10_000];

    public static int DelayMilliseconds(int attempt)
    {
        var index = Math.Clamp(attempt, 0, BackoffMilliseconds.Length - 1);
        return BackoffMilliseconds[index];
    }
}
