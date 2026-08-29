using HomeKtv.Windows;

namespace HomeKtv.Windows.Tests;

public sealed class WindowShutdownGuardTests
{
    [Fact]
    public async Task Cleanup_failure_is_reported_and_does_not_escape()
    {
        var reported = new List<Exception>();

        await WindowShutdownGuard.RunAsync(
            () => throw new InvalidOperationException("socket already closed"),
            reported.Add);

        Assert.Single(reported);
        Assert.Equal("socket already closed", reported[0].Message);
    }

    [Fact]
    public async Task Cancellation_during_cleanup_is_ignored()
    {
        var reported = new List<Exception>();

        await WindowShutdownGuard.RunAsync(
            () => throw new OperationCanceledException(),
            reported.Add);

        Assert.Empty(reported);
    }
}
