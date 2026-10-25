using System.Net.WebSockets;
using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.Tests;

public sealed class PlaybackProgressLoopTests
{
    public static IEnumerable<object[]> RecoverableFailures()
    {
        yield return [new WebSocketException("websocket closed")];
        yield return [new IOException("pipe closed")];
        yield return [new ObjectDisposedException("socket")];
        yield return [new InvalidOperationException("socket is not ready")];
    }

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

    [Theory]
    [MemberData(nameof(RecoverableFailures))]
    public async Task Recoverable_failures_are_reported_and_the_loop_continues(Exception failure)
    {
        using var cancellation = new CancellationTokenSource();
        var errors = new List<Exception>();
        var attempts = 0;
        var secondAttempt = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);

        var loop = PlaybackProgressLoop.RunAsync(
            token =>
            {
                if (Interlocked.Increment(ref attempts) == 1) throw failure;
                secondAttempt.TrySetResult(true);
                cancellation.Cancel();
                return Task.CompletedTask;
            },
            TimeSpan.FromMilliseconds(1), cancellation.Token, errors.Add);

        await secondAttempt.Task.WaitAsync(TimeSpan.FromSeconds(2));
        await loop.WaitAsync(TimeSpan.FromSeconds(2));

        Assert.Same(failure, Assert.Single(errors));
        Assert.Equal(2, attempts);
    }

    [Fact]
    public async Task Cancellation_during_a_tick_is_a_normal_shutdown()
    {
        using var cancellation = new CancellationTokenSource();
        var attempts = 0;
        var loop = PlaybackProgressLoop.RunAsync(
            token =>
            {
                attempts++;
                cancellation.Cancel();
                throw new OperationCanceledException(token);
            },
            TimeSpan.FromMilliseconds(1), cancellation.Token);

        await loop.WaitAsync(TimeSpan.FromSeconds(2));

        Assert.Equal(1, attempts);
    }

    [Fact]
    public async Task Object_disposal_after_cancellation_is_a_normal_shutdown()
    {
        using var cancellation = new CancellationTokenSource();
        var errors = new List<Exception>();

        await PlaybackProgressLoop.RunAsync(
            _ =>
            {
                cancellation.Cancel();
                throw new ObjectDisposedException("socket");
            },
            TimeSpan.FromMilliseconds(1), cancellation.Token, errors.Add)
            .WaitAsync(TimeSpan.FromSeconds(2));

        Assert.Empty(errors);
    }

    [Fact]
    public async Task Cancellation_before_the_first_tick_stops_the_timer_loop()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        var attempts = 0;

        await PlaybackProgressLoop.RunAsync(
            _ => { attempts++; return Task.CompletedTask; },
            TimeSpan.FromMilliseconds(1), cancellation.Token).WaitAsync(TimeSpan.FromSeconds(2));

        Assert.Equal(0, attempts);
    }

    [Fact]
    public async Task An_uncancelled_operation_cancellation_is_not_swallowed()
    {
        using var cancellation = new CancellationTokenSource();

        await Assert.ThrowsAsync<OperationCanceledException>(() => PlaybackProgressLoop.RunAsync(
            _ => Task.FromException(new OperationCanceledException("unexpected")),
            TimeSpan.FromMilliseconds(1), cancellation.Token));
    }

    [Fact]
    public async Task A_throwing_error_observer_cannot_terminate_the_loop()
    {
        using var cancellation = new CancellationTokenSource();
        var attempts = 0;
        var secondAttempt = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);

        var loop = PlaybackProgressLoop.RunAsync(
            _ =>
            {
                if (Interlocked.Increment(ref attempts) == 1) throw new IOException("temporary");
                secondAttempt.TrySetResult(true);
                cancellation.Cancel();
                return Task.CompletedTask;
            },
            TimeSpan.FromMilliseconds(1), cancellation.Token,
            _ => throw new InvalidOperationException("observer failed"));

        await secondAttempt.Task.WaitAsync(TimeSpan.FromSeconds(2));
        await loop.WaitAsync(TimeSpan.FromSeconds(2));

        Assert.Equal(2, attempts);
    }

    [Fact]
    public async Task Rejects_a_null_tick_and_non_positive_interval()
    {
        using var cancellation = new CancellationTokenSource();

        await Assert.ThrowsAsync<ArgumentNullException>(() => PlaybackProgressLoop.RunAsync(
            null!, TimeSpan.FromMilliseconds(1), cancellation.Token));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => PlaybackProgressLoop.RunAsync(
            _ => Task.CompletedTask, TimeSpan.Zero, cancellation.Token));
    }
}
