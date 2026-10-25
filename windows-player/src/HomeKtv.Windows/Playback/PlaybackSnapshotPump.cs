using System.Threading.Channels;
using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Playback;

public sealed record PlaybackSnapshotWork(
    string EventType,
    QueueSnapshot Snapshot,
    long Generation,
    Func<bool> IsCurrent);

/**
 * Keeps at most one buffered playback snapshot and cancels the projection that is in flight
 * when a newer server snapshot arrives. The WebSocket receive loop can therefore hand off
 * snapshots without waiting for HTTP metadata or mpv IPC.
 */
public sealed class PlaybackSnapshotPump : IAsyncDisposable
{
    public delegate Task ProjectionHandler(
        PlaybackSnapshotWork work,
        CancellationToken cancellationToken);

    internal sealed class WorkItem
    {
        public WorkItem(PlaybackSnapshotWork work, CancellationTokenSource cancellationSource)
        {
            Work = work;
            CancellationSource = cancellationSource;
        }

        public PlaybackSnapshotWork Work { get; }
        public CancellationTokenSource CancellationSource { get; }
    }

    internal interface IWorkQueue
    {
        bool TryWrite(WorkItem item);
        bool TryRead(out WorkItem item);
        IAsyncEnumerable<WorkItem> ReadAllAsync(CancellationToken cancellationToken);
        void Complete();
    }

    private sealed class ChannelWorkQueue : IWorkQueue
    {
        private readonly Channel<WorkItem> channel = Channel.CreateBounded<WorkItem>(
            new BoundedChannelOptions(1)
            {
                FullMode = BoundedChannelFullMode.Wait,
                SingleReader = true,
                SingleWriter = false,
            });

        public bool TryWrite(WorkItem item) => channel.Writer.TryWrite(item);

        public bool TryRead(out WorkItem item)
        {
            if (channel.Reader.TryRead(out var readItem))
            {
                item = readItem;
                return true;
            }

            item = null!;
            return false;
        }

        public IAsyncEnumerable<WorkItem> ReadAllAsync(CancellationToken cancellationToken) =>
            channel.Reader.ReadAllAsync(cancellationToken);

        public void Complete() => channel.Writer.TryComplete();
    }

    private readonly IWorkQueue queue;
    private readonly ProjectionHandler projectionHandler;
    private readonly CancellationTokenSource lifetime = new();
    private readonly object stateLock = new();
    private TaskCompletionSource<bool> idle = CompletedSignal();
    private WorkItem? queuedItem;
    private WorkItem? activeItem;
    private WorkItem? latestItem;
    private long generation;
    private bool disposed;
    private readonly Task worker;

    public PlaybackSnapshotPump(ProjectionHandler projectionHandler)
        : this(projectionHandler, new ChannelWorkQueue())
    {
    }

    internal PlaybackSnapshotPump(ProjectionHandler projectionHandler, IWorkQueue queue)
    {
        this.projectionHandler = projectionHandler;
        this.queue = queue;
        worker = RunAsync();
    }

    public event Action<PlaybackSnapshotWork, Exception>? ProjectionFailed;

    public long Submit(string eventType, QueueSnapshot snapshot)
    {
        lock (stateLock)
        {
            ObjectDisposedException.ThrowIf(disposed, this);

            var itemGeneration = ++generation;
            latestItem?.CancellationSource.Cancel();
            var cancellationSource = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
            var work = new PlaybackSnapshotWork(
                eventType,
                snapshot,
                itemGeneration,
                () => IsCurrent(itemGeneration));
            var item = new WorkItem(work, cancellationSource);

            if (idle.Task.IsCompleted)
            {
                idle = NewSignal();
            }

            while (!queue.TryWrite(item))
            {
                if (!queue.TryRead(out var discarded))
                {
                    Thread.Yield();
                    continue;
                }

                if (ReferenceEquals(queuedItem, discarded)) queuedItem = null;
                if (ReferenceEquals(latestItem, discarded)) latestItem = null;
                discarded.CancellationSource.Dispose();
            }

            queuedItem = item;
            latestItem = item;
            return itemGeneration;
        }
    }

    public Task WaitForIdleAsync(CancellationToken cancellationToken = default)
    {
        lock (stateLock)
        {
            return idle.Task.WaitAsync(cancellationToken);
        }
    }

    /** Invalidates queued and in-flight work without submitting a replacement snapshot. */
    public void Fence()
    {
        lock (stateLock)
        {
            generation++;
            latestItem?.CancellationSource.Cancel();
        }
    }

    private bool IsCurrent(long candidateGeneration)
    {
        lock (stateLock)
        {
            return !disposed && candidateGeneration == generation;
        }
    }

    private async Task RunAsync()
    {
        try
        {
            await foreach (var item in queue.ReadAllAsync(lifetime.Token).ConfigureAwait(false))
            {
                lock (stateLock)
                {
                    if (ReferenceEquals(queuedItem, item)) queuedItem = null;
                    activeItem = item;
                }

                try
                {
                    await projectionHandler(item.Work, item.CancellationSource.Token)
                        .ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (
                    item.CancellationSource.IsCancellationRequested || !item.Work.IsCurrent())
                {
                    // A newer snapshot owns the output; cancellation is expected.
                }
                catch (Exception) when (!item.Work.IsCurrent())
                {
                    // Do not report failures from a superseded snapshot.
                }
                catch (Exception exception)
                {
                    try { ProjectionFailed?.Invoke(item.Work, exception); }
                    catch { /* observer failures must not stop the pump */ }
                }
                finally
                {
                    lock (stateLock)
                    {
                        if (ReferenceEquals(activeItem, item)) activeItem = null;
                        if (ReferenceEquals(latestItem, item)) latestItem = null;
                        if (queuedItem is null) idle.TrySetResult(true);
                    }

                    item.CancellationSource.Dispose();
                }
            }
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested)
        {
            // Normal shutdown.
        }
        finally
        {
            lock (stateLock)
            {
                while (queue.TryRead(out var remaining))
                {
                    remaining.CancellationSource.Dispose();
                }

                // The bounded single-slot queue can contain only queuedItem/latestItem.
                // The pump is disposed here, so no newer state can be installed.
                queuedItem = null;
                latestItem = null;
                activeItem = null;
                idle.TrySetResult(true);
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        lock (stateLock)
        {
            if (disposed) return;
            disposed = true;
            latestItem?.CancellationSource.Cancel();
            queue.Complete();
            lifetime.Cancel();
        }

        try { await worker.ConfigureAwait(false); }
        finally
        {
            lifetime.Dispose();
        }
    }

    private static TaskCompletionSource<bool> NewSignal() =>
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    private static TaskCompletionSource<bool> CompletedSignal()
    {
        var signal = NewSignal();
        signal.TrySetResult(true);
        return signal;
    }
}
