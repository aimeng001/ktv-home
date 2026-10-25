using System.Runtime.CompilerServices;
using System.Threading.Channels;
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
    public async Task Submit_retries_when_the_reader_takes_the_full_slot_before_replacement_read()
    {
        var firstStarted = NewSignal();
        var releaseFirst = NewSignal();
        var projectedQueues = new List<long>();
        var committedQueues = new List<long>();
        var queue = new ReaderWinsReplacementQueue();

        await using var pump = new PlaybackSnapshotPump(async (work, cancellationToken) =>
        {
            var queueId = work.Snapshot.Playing!.QueueId!.Value;
            projectedQueues.Add(queueId);
            if (queueId == 1)
            {
                firstStarted.TrySetResult(true);
                await releaseFirst.Task;
            }

            cancellationToken.ThrowIfCancellationRequested();
            if (work.IsCurrent()) committedQueues.Add(queueId);
        }, queue);

        pump.Submit("now_playing", Snapshot(1));
        await firstStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        pump.Submit("now_playing", Snapshot(2));
        var latestGeneration = pump.Submit("now_playing", Snapshot(3));

        Assert.Equal(3, latestGeneration);
        Assert.Equal(1, queue.ReplacementReadMisses);
        releaseFirst.TrySetResult(true);
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(new[] { 1L, 2L, 3L }, projectedQueues);
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

    [Fact]
    public async Task Projection_failure_is_reported_and_a_throwing_observer_does_not_stop_later_work()
    {
        var failed = new TaskCompletionSource<(PlaybackSnapshotWork Work, Exception Error)>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var projectedQueues = new List<long>();
        var projectionFailure = new IOException("synthetic projection failure");
        var pump = new PlaybackSnapshotPump((work, _) =>
        {
            if (work.Snapshot.Playing?.QueueId == 1) throw projectionFailure;
            projectedQueues.Add(work.Snapshot.Playing!.QueueId!.Value);
            return Task.CompletedTask;
        });
        pump.ProjectionFailed += (work, exception) =>
        {
            failed.TrySetResult((work, exception));
            throw new InvalidOperationException("synthetic observer failure");
        };

        try
        {
            var failedGeneration = pump.Submit("now_playing", Snapshot(1));
            var failure = await failed.Task.WaitAsync(TimeSpan.FromSeconds(5));
            Assert.Equal(failedGeneration, failure.Work.Generation);
            Assert.Equal(1, failure.Work.Snapshot.Playing!.QueueId);
            Assert.Same(projectionFailure, failure.Error);

            pump.Submit("now_playing", Snapshot(2));
            await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

            Assert.Equal(new[] { 2L }, projectedQueues);
        }
        finally
        {
            await pump.DisposeAsync();
        }
    }

    [Fact]
    public async Task Superseded_projection_failure_is_suppressed_and_latest_snapshot_commits()
    {
        var firstStarted = NewSignal();
        var releaseFirst = NewSignal();
        var failedGenerations = new List<long>();
        var projectedQueues = new List<long>();

        await using var pump = new PlaybackSnapshotPump(async (work, _) =>
        {
            var queueId = work.Snapshot.Playing!.QueueId!.Value;
            if (queueId == 1)
            {
                firstStarted.TrySetResult(true);
                await releaseFirst.Task.WaitAsync(TimeSpan.FromSeconds(5));
                throw new IOException("superseded projection failure");
            }

            projectedQueues.Add(queueId);
        });
        pump.ProjectionFailed += (work, _) => failedGenerations.Add(work.Generation);

        pump.Submit("now_playing", Snapshot(1));
        await firstStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        pump.Submit("now_playing", Snapshot(2));
        releaseFirst.TrySetResult(true);
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Empty(failedGenerations);
        Assert.Equal(new[] { 2L }, projectedQueues);
    }

    [Fact]
    public async Task Projection_failure_without_observers_does_not_stop_following_snapshots()
    {
        var projectedQueues = new List<long>();
        await using var pump = new PlaybackSnapshotPump((work, _) =>
        {
            var queueId = work.Snapshot.Playing!.QueueId!.Value;
            if (queueId == 1) throw new IOException("synthetic projection failure");
            projectedQueues.Add(queueId);
            return Task.CompletedTask;
        });

        pump.Submit("now_playing", Snapshot(1));
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));
        pump.Submit("now_playing", Snapshot(2));
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(new[] { 2L }, projectedQueues);
    }

    [Fact]
    public async Task Unexpected_projection_cancellation_is_reported_and_does_not_stop_following_work()
    {
        var failed = new TaskCompletionSource<Exception>(TaskCreationOptions.RunContinuationsAsynchronously);
        var committedQueues = new List<long>();
        var unexpectedCancellation = new OperationCanceledException("projection cancelled itself");
        var pump = new PlaybackSnapshotPump((work, _) =>
        {
            var queueId = work.Snapshot.Playing!.QueueId!.Value;
            if (queueId == 1) return Task.FromException(unexpectedCancellation);

            committedQueues.Add(queueId);
            return Task.CompletedTask;
        });
        pump.ProjectionFailed += (_, exception) => failed.TrySetResult(exception);

        try
        {
            pump.Submit("now_playing", Snapshot(1));
            var reported = await failed.Task.WaitAsync(TimeSpan.FromSeconds(5));
            pump.Submit("now_playing", Snapshot(2));
            await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

            Assert.Same(unexpectedCancellation, reported);
            Assert.Equal(new[] { 2L }, committedQueues);
        }
        finally
        {
            try { await pump.DisposeAsync(); }
            catch (OperationCanceledException) { }
        }
    }

    [Fact]
    public async Task Dispose_cancels_queued_work_and_is_idempotent()
    {
        var firstStarted = NewSignal();
        var releaseFirst = NewSignal();
        var invokedQueues = new List<long>();
        var observedCancellation = new List<(long QueueId, bool IsCancelled, bool IsCurrent)>();
        var committedQueues = new List<long>();
        var pump = new PlaybackSnapshotPump(async (work, cancellationToken) =>
        {
            var queueId = work.Snapshot.Playing!.QueueId!.Value;
            invokedQueues.Add(queueId);
            if (queueId == 1)
            {
                firstStarted.TrySetResult(true);
                await releaseFirst.Task.WaitAsync(TimeSpan.FromSeconds(5));
            }

            observedCancellation.Add((queueId, cancellationToken.IsCancellationRequested, work.IsCurrent()));
            cancellationToken.ThrowIfCancellationRequested();
            committedQueues.Add(queueId);
        });

        pump.Submit("now_playing", Snapshot(1));
        await firstStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        pump.Submit("now_playing", Snapshot(2));

        var disposal = pump.DisposeAsync().AsTask();
        releaseFirst.TrySetResult(true);
        await disposal.WaitAsync(TimeSpan.FromSeconds(5));
        await pump.DisposeAsync();

        Assert.Equal(new[] { 1L, 2L }, invokedQueues);
        Assert.All(observedCancellation, state =>
        {
            Assert.True(state.IsCancelled);
            Assert.False(state.IsCurrent);
        });
        Assert.Empty(committedQueues);
        Assert.Throws<ObjectDisposedException>(() => pump.Submit("now_playing", Snapshot(3)));
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));
    }

    [Fact]
    public async Task Dispose_drains_a_buffered_snapshot_when_lifetime_cancellation_stops_the_reader()
    {
        var firstStarted = NewSignal();
        var releaseFirst = NewSignal();
        var invokedQueues = new List<long>();
        var committedQueues = new List<long>();
        var queue = new BufferedQueueOnCancellation();
        var pump = new PlaybackSnapshotPump(async (work, cancellationToken) =>
        {
            var queueId = work.Snapshot.Playing!.QueueId!.Value;
            invokedQueues.Add(queueId);
            if (queueId == 1)
            {
                firstStarted.TrySetResult(true);
                await releaseFirst.Task;
            }

            cancellationToken.ThrowIfCancellationRequested();
            committedQueues.Add(queueId);
        }, queue);

        pump.Submit("now_playing", Snapshot(1));
        await firstStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        pump.Submit("now_playing", Snapshot(2));

        var disposal = pump.DisposeAsync().AsTask();
        releaseFirst.TrySetResult(true);
        await disposal.WaitAsync(TimeSpan.FromSeconds(5));
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(new[] { 1L }, invokedQueues);
        Assert.Empty(committedQueues);
        Assert.False(queue.TryRead(out _));
        Assert.Throws<ObjectDisposedException>(() => pump.Submit("now_playing", Snapshot(3)));
    }

    [Fact]
    public async Task Fence_without_submitted_work_is_safe_and_next_generation_can_project()
    {
        var projectedQueues = new List<long>();
        await using var pump = new PlaybackSnapshotPump((work, _) =>
        {
            projectedQueues.Add(work.Snapshot.Playing!.QueueId!.Value);
            return Task.CompletedTask;
        });

        pump.Fence();
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));
        var generation = pump.Submit("now_playing", Snapshot(4));
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(2, generation);
        Assert.Equal(new[] { 4L }, projectedQueues);
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

    private sealed class ReaderWinsReplacementQueue : PlaybackSnapshotPump.IWorkQueue
    {
        private readonly Channel<PlaybackSnapshotPump.WorkItem> channel =
            Channel.CreateBounded<PlaybackSnapshotPump.WorkItem>(1);
        private PlaybackSnapshotPump.WorkItem? readerOwnedItem;
        private bool failNextTryRead;

        public int ReplacementReadMisses { get; private set; }

        public bool TryWrite(PlaybackSnapshotPump.WorkItem item)
        {
            if (channel.Writer.TryWrite(item)) return true;

            // Model the single reader consuming the buffered item after the
            // failed write but before Submit attempts its replacement read.
            if (readerOwnedItem is null && channel.Reader.TryRead(out var readItem))
            {
                readerOwnedItem = readItem;
                failNextTryRead = true;
            }

            return false;
        }

        public bool TryRead(out PlaybackSnapshotPump.WorkItem item)
        {
            if (failNextTryRead)
            {
                failNextTryRead = false;
                ReplacementReadMisses++;
                item = null!;
                return false;
            }

            if (readerOwnedItem is { } readerOwned)
            {
                readerOwnedItem = null;
                item = readerOwned;
                return true;
            }

            if (channel.Reader.TryRead(out var readItem))
            {
                item = readItem;
                return true;
            }

            item = null!;
            return false;
        }

        public async IAsyncEnumerable<PlaybackSnapshotPump.WorkItem> ReadAllAsync(
            [EnumeratorCancellation] CancellationToken cancellationToken)
        {
            while (true)
            {
                if (TryRead(out var item))
                {
                    yield return item;
                    continue;
                }

                if (!await channel.Reader.WaitToReadAsync(cancellationToken).ConfigureAwait(false))
                {
                    yield break;
                }
            }
        }

        public void Complete() => channel.Writer.TryComplete();
    }

    private sealed class BufferedQueueOnCancellation : PlaybackSnapshotPump.IWorkQueue
    {
        private readonly Channel<PlaybackSnapshotPump.WorkItem> channel =
            Channel.CreateBounded<PlaybackSnapshotPump.WorkItem>(1);

        public bool TryWrite(PlaybackSnapshotPump.WorkItem item) => channel.Writer.TryWrite(item);

        public bool TryRead(out PlaybackSnapshotPump.WorkItem item)
        {
            if (channel.Reader.TryRead(out var readItem))
            {
                item = readItem;
                return true;
            }

            item = null!;
            return false;
        }

        public async IAsyncEnumerable<PlaybackSnapshotPump.WorkItem> ReadAllAsync(
            [EnumeratorCancellation] CancellationToken cancellationToken)
        {
            while (true)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (TryRead(out var item))
                {
                    yield return item;
                    continue;
                }

                if (!await channel.Reader.WaitToReadAsync(cancellationToken).ConfigureAwait(false))
                {
                    yield break;
                }
            }
        }

        public void Complete() => channel.Writer.TryComplete();
    }
}
