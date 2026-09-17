using HomeKtv.Windows.Playback;
using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Tests;

public sealed class PlaybackSnapshotPumpTests
{
    [Fact]
    public async Task Newer_snapshot_cancels_an_inflight_projection_and_only_latest_commits()
    {
        var firstStarted = NewSignal();
        var releaseFirst = NewSignal();
        var committedQueues = new List<long>();

        await using var pump = new PlaybackSnapshotPump(async (work, cancellationToken) =>
        {
            if (work.Snapshot.Playing?.QueueId == 1)
            {
                firstStarted.TrySetResult(true);
                await releaseFirst.Task.WaitAsync(cancellationToken);
            }

            cancellationToken.ThrowIfCancellationRequested();
            if (work.IsCurrent())
            {
                committedQueues.Add(work.Snapshot.Playing!.QueueId!.Value);
            }
        });

        pump.Submit("now_playing", Snapshot(1));
        await firstStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));

        pump.Submit("now_playing", Snapshot(2));
        releaseFirst.TrySetResult(true);
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(new[] { 2L }, committedQueues);
    }

    [Fact]
    public async Task Rapid_updates_keep_only_the_latest_buffered_snapshot()
    {
        var release = NewSignal();
        var committedQueues = new List<long>();

        await using var pump = new PlaybackSnapshotPump(async (work, cancellationToken) =>
        {
            if (work.Snapshot.Playing?.QueueId == 1)
            {
                await release.Task.WaitAsync(cancellationToken);
            }

            cancellationToken.ThrowIfCancellationRequested();
            if (work.IsCurrent())
            {
                committedQueues.Add(work.Snapshot.Playing!.QueueId!.Value);
            }
        });

        pump.Submit("now_playing", Snapshot(1));
        pump.Submit("now_playing", Snapshot(2));
        pump.Submit("now_playing", Snapshot(3));
        release.TrySetResult(true);
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(new[] { 3L }, committedQueues);
    }

    [Fact]
    public async Task Fence_cancels_inflight_projection_without_committing_it()
    {
        var started = NewSignal();
        var release = NewSignal();
        var committed = false;

        await using var pump = new PlaybackSnapshotPump(async (work, cancellationToken) =>
        {
            started.TrySetResult(true);
            await release.Task.WaitAsync(cancellationToken);
            cancellationToken.ThrowIfCancellationRequested();
            committed = true;
        });

        pump.Submit("now_playing", Snapshot(1));
        await started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        pump.Fence();
        release.TrySetResult(true);
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.False(committed);
    }

    private static QueueSnapshot Snapshot(long queueId) => new(
        new NowPlaying(queueId, new SongDto(100 + queueId, "Song", "Artist"), null),
        Array.Empty<QueueEntry>(),
        "playing",
        60,
        false,
        "original",
        new AudioLayoutDto(AudioLayout.NORMAL_STEREO),
        true,
        0);

    private static TaskCompletionSource<bool> NewSignal() =>
        new(TaskCreationOptions.RunContinuationsAsynchronously);
}
