using System.Net.WebSockets;
using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.Tests;

public sealed class PlaybackProgressLoopTests
{
    [Fact]
    public async Task Transient_websocket_failure_does_not_end_the_loop()
    {
        using var cancellation = new CancellationTokenSource();
        var attempts = 0;
        var errors = new List<Exception>();
        var secondAttempt = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);

        var loop = PlaybackProgressLoop.RunAsync(
            async token =>
            {
                if (Interlocked.Increment(ref attempts) == 1)
                {
                    throw new WebSocketException("temporary disconnect");
                }

                secondAttempt.TrySetResult(true);
                cancellation.Cancel();
                await Task.CompletedTask;
            },
            TimeSpan.FromMilliseconds(1),
            cancellation.Token,
            errors.Add);

        await secondAttempt.Task.WaitAsync(TimeSpan.FromSeconds(5));
        await loop.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.True(attempts >= 2);
        Assert.Single(errors);
        Assert.IsType<WebSocketException>(errors[0]);
    }
}
