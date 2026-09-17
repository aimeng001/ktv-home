using System.IO;
using HomeKtv.Windows.Mpv;
using HomeKtv.Windows.Protocol;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Playback;

/**
 * Coordinates the shared Home KTV server protocol with the local mpv
 * projection. UI commands always go through the server; mpv never becomes a
 * second source of truth for queue or playback state.
 */
public sealed class PlaybackTerminal : IAsyncDisposable
{
    private readonly HttpServerApi server;
    private readonly KtvWebSocketClient socket;
    private readonly MpvProcessController output;
    private readonly PlaybackCoordinator coordinator;
    private readonly PlaybackSnapshotPump snapshotPump;
    private readonly ActivePlayerLeaseGate leaseGate = new();
    private readonly PlaybackRecoveryPolicy recoveryPolicy = new();
    private readonly SemaphoreSlim recoveryLock = new(1, 1);
    private readonly CancellationTokenSource lifetime = new();
    private Task? socketTask;
    private Task? progressTask;
    private QueueSnapshot? snapshot;
    private long snapshotRevision;
    private readonly object snapshotRevisionLock = new();
    private int disposed;

    public PlaybackTerminal(
        HttpServerApi server,
        KtvWebSocketClient socket,
        MpvProcessController output)
    {
        this.server = server;
        this.socket = socket;
        this.output = output;
        coordinator = new PlaybackCoordinator(server, output);
        snapshotPump = new PlaybackSnapshotPump(
            (work, cancellationToken) => coordinator.ApplySnapshotAsync(
                work.EventType, work.Snapshot, cancellationToken, work.IsCurrent));
        snapshotPump.ProjectionFailed += OnProjectionFailed;

        socket.ConnectionChanged += connected =>
        {
            if (!connected)
            {
                leaseGate.Disconnect();
                _ = FenceOutputAsync();
            }
            ConnectionChanged?.Invoke(connected);
        };
        socket.PlayerAssignmentReceived += assignment => _ = ApplyAssignmentAsync(assignment);
        socket.ConnectionError += exception => Error?.Invoke(exception);
        socket.ReliableMessageRejected += (key, reason) =>
        {
            if (lifetime.IsCancellationRequested) return;
            Error?.Invoke(new InvalidOperationException(
                $"Reliable playback report rejected: {reason}"));
            _ = FenceOutputAsync();
        };
        socket.ProgressReceived += position =>
        {
            CurrentPositionMs = position;
            PositionChanged?.Invoke(position);
        };
        socket.SnapshotReceived += ApplySnapshotFromSocketAsync;
        output.PlaybackFinished += fileId => _ = ReportFinishedAsync(fileId);
        output.PlaybackFailed += fileId => _ = ReportOutputFailureAsync(fileId);
        output.SessionFaulted += exception => _ = RecoverOutputAsync(exception);
    }

    public bool IsConnected => socket.IsConnected;
    public bool IsOutputRunning => output.IsMpvRunning;
    public QueueSnapshot? CurrentSnapshot => Volatile.Read(ref snapshot);
    public long CurrentPositionMs { get; private set; }

    public Task<byte[]?> GetAssetBytesAsync(string? path, CancellationToken cancellationToken = default) =>
        server.GetAssetBytesAsync(path, cancellationToken);

    public event Action<bool>? ConnectionChanged;
    public event Action<QueueSnapshot>? SnapshotChanged;
    public event Action<long>? PositionChanged;
    public event Action<Exception>? Error;

    public async Task ConnectAsync(CancellationToken cancellationToken = default)
    {
        var health = await server.CheckHealthAsync(cancellationToken).ConfigureAwait(false);
        if (health is null)
        {
            throw new InvalidOperationException("Home KTV server health check failed.");
        }

        if (socketTask is null)
        {
            socketTask = socket.RunAsync(lifetime.Token);
            progressTask = SendProgressLoopAsync(lifetime.Token);
        }

        var initial = await server.GetQueueAsync(cancellationToken).ConfigureAwait(false);
        if (initial is not null && leaseGate.TryGetGeneration(out _))
        {
            await ApplySnapshotAsync("sync_full", initial, cancellationToken).ConfigureAwait(false);
            await snapshotPump.WaitForIdleAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    public Task PlayAsync(CancellationToken cancellationToken = default) =>
        SendControlAsync("play", null, "player_state", cancellationToken);

    public Task PauseAsync(CancellationToken cancellationToken = default) =>
        SendControlAsync("pause", null, "player_state", cancellationToken);

    public Task StopAsync(CancellationToken cancellationToken = default) =>
        SendControlAsync("stop", null, "player_state", cancellationToken);

    public Task SeekAsync(long positionMs, CancellationToken cancellationToken = default) =>
        SendControlAsync("seek", new Dictionary<string, object?> { ["position_ms"] = Math.Max(0, positionMs) },
            "playback_seeked", cancellationToken);

    public Task ReplayAsync(CancellationToken cancellationToken = default) =>
        SendControlAsync("restart", null, "playback_restarted", cancellationToken);

    public Task NextAsync(CancellationToken cancellationToken = default) =>
        SendControlAsync("next", null, "now_playing", cancellationToken);

    public Task SetVolumeAsync(int volume, bool muted = false,
        CancellationToken cancellationToken = default) =>
        SendControlAsync("set_volume", new Dictionary<string, object?> { ["volume"] = volume },
            "volume_changed", cancellationToken);

    public Task SetMutedAsync(bool muted, CancellationToken cancellationToken = default) =>
        SendControlAsync("mute", new Dictionary<string, object?> { ["muted"] = muted },
            "volume_changed", cancellationToken);

    public Task SetVocalModeAsync(string mode, CancellationToken cancellationToken = default) =>
        SendControlAsync("set_vocal", new Dictionary<string, object?> { ["mode"] = mode },
            "vocal_changed", cancellationToken);

    public Task SwapVocalTracksAsync(CancellationToken cancellationToken = default) =>
        SendControlAsync("swap_vocal_tracks", null, "vocal_changed", cancellationToken);

    public Task SetDisplayAsync(int screenIndex, CancellationToken cancellationToken = default) =>
        output.SetDisplayAsync(screenIndex, cancellationToken);

    private async Task ApplySnapshotFromSocketAsync(
        string eventType,
        QueueSnapshot incoming,
        CancellationToken cancellationToken)
    {
        await ApplySnapshotAsync(eventType, incoming, cancellationToken).ConfigureAwait(false);
    }

    private Task ApplySnapshotAsync(
        string eventType,
        QueueSnapshot incoming,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (!leaseGate.TryGetGeneration(out _)) return Task.CompletedTask;
        lock (snapshotRevisionLock)
        {
            var currentRevision = snapshotRevision;
            if (!SnapshotRevisionPolicy.ShouldApply(currentRevision, incoming.StateRevision))
            {
                return Task.CompletedTask;
            }

            if (incoming.StateRevision > 0) snapshotRevision = incoming.StateRevision;
            Interlocked.Exchange(ref snapshot, incoming);
        }
        if (eventType is "sync_full" or "playback_seeked" or "playback_restarted")
        {
            CurrentPositionMs = Math.Max(0, incoming.PositionMs);
            PositionChanged?.Invoke(CurrentPositionMs);
        }

        SnapshotChanged?.Invoke(incoming);
        try
        {
            snapshotPump.Submit(eventType, incoming);
        }
        catch (ObjectDisposedException) when (lifetime.IsCancellationRequested)
        {
        }

        return Task.CompletedTask;
    }

    private async Task SendControlAsync(
        string action,
        IReadOnlyDictionary<string, object?>? parameters,
        string eventType,
        CancellationToken cancellationToken)
    {
        var result = await server.SendControlAsync(action, parameters, cancellationToken)
            .ConfigureAwait(false);
        if (result is not null)
        {
            await ApplySnapshotAsync(eventType, result, cancellationToken).ConfigureAwait(false);
            await snapshotPump.WaitForIdleAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    private void OnProjectionFailed(PlaybackSnapshotWork work, Exception exception)
    {
        _ = ReportProjectionFailureAsync(work, exception);
    }

    private async Task ReportProjectionFailureAsync(
        PlaybackSnapshotWork work,
        Exception exception)
    {
        if (lifetime.IsCancellationRequested || !leaseGate.TryGetGeneration(out var generation)) return;
        Error?.Invoke(exception);
        if (work.Snapshot.Playing?.QueueId is not { } queueId) return;

        if (!PlaybackFailurePolicy.ShouldReportPlayError(exception, out var fileId))
        {
            return;
        }

        await SendPlayErrorAsync(queueId, fileId, exception.Message, generation).ConfigureAwait(false);
    }

    /**
     * mpv 在 loadfile 成功之后才发现流拉不下来（end-file + reason="error"）。
     * 这个事件没有 HTTP 404 证据，不能把鉴权、服务端错误、网络超时或
     * 解码失败误报成 play_error，否则后端会错误跳过当前队列项。
     */
    private async Task ReportOutputFailureAsync(long fileId)
    {
        // 与 ReportFinishedAsync 相同的身份校验：只上报仍属于当前投影的媒体。
        var identity = coordinator.ActiveOutput;
        if (identity is null || identity.FileId != fileId
            || !coordinator.TryGetActiveQueueId(identity, out var queueId))
        {
            return;
        }

        if (lifetime.IsCancellationRequested || !leaseGate.TryGetGeneration(out _)) return;

        var exception = new PlaybackAttemptException(
            queueId, fileId, $"mpv reported a playback failure for file {fileId}.",
            PlaybackFailureKind.OutputFailure);
        Error?.Invoke(exception);
        try
        {
            await coordinator.InvalidateOutputProjectionAsync(lifetime.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
        catch (Exception recoveryException)
        {
            Error?.Invoke(recoveryException);
        }
    }

    private async Task SendPlayErrorAsync(long queueId, long? fileId, string message, long generation)
    {
        try
        {
            var result = await socket.SendPlayErrorAsync(
                    queueId, fileId, Utf8ByteBudget.TruncateToByteLimit(message, 8 * 1024),
                    generation, lifetime.Token)
                .ConfigureAwait(false);
            if (result == ReliableSendResult.Rejected)
            {
                await FenceOutputAsync().ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
        catch (Exception sendException) when (sendException is IOException or InvalidOperationException)
        {
            Error?.Invoke(sendException);
        }
    }

    private async Task ReportFinishedAsync(long fileId)
    {
        var identity = coordinator.ActiveOutput;
        if (identity is null || identity.FileId != fileId
            || !coordinator.TryGetActiveQueueId(identity, out var queueId))
        {
            return;
        }

        if (lifetime.IsCancellationRequested) return;
        try
        {
            // The socket owns the durable outbox and will attach the generation
            // that is valid when the report is actually sent.
            var result = await socket.SendFinishedAsync(queueId, cancellationToken: lifetime.Token)
                .ConfigureAwait(false);
            if (result is PendingPlaybackReportEnqueueResult.Full
                or PendingPlaybackReportEnqueueResult.PersistenceFailed
                or PendingPlaybackReportEnqueueResult.Invalid)
            {
                Error?.Invoke(new InvalidOperationException(
                    $"Finished playback report rejected: {result}."));
                await FenceOutputAsync().ConfigureAwait(false);
            }
        }
        catch (Exception exception) when (exception is IOException or InvalidOperationException)
        {
            Error?.Invoke(exception);
        }
    }

    private async Task RecoverOutputAsync(Exception exception)
    {
        if (!await recoveryLock.WaitAsync(0).ConfigureAwait(false)) return;

        try
        {
            Error?.Invoke(exception);
            if (!recoveryPolicy.TryBegin(out var backoff))
            {
                Error?.Invoke(new InvalidOperationException(
                    "Automatic mpv recovery stopped after repeated failures."));
                return;
            }

            if (backoff > TimeSpan.Zero)
            {
                await Task.Delay(backoff, lifetime.Token).ConfigureAwait(false);
            }

            var current = Volatile.Read(ref snapshot);
            if (current is null || lifetime.IsCancellationRequested) return;

            await coordinator.InvalidateOutputProjectionAsync(lifetime.Token).ConfigureAwait(false);
            var latest = Volatile.Read(ref snapshot);
            if (latest is not null)
            {
                snapshotPump.Submit("sync_full", latest);
                await snapshotPump.WaitForIdleAsync(lifetime.Token).ConfigureAwait(false);
            }
            if (output.IsMpvRunning) recoveryPolicy.MarkSuccess();
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
        catch (Exception recoveryException) when (recoveryException is IOException or InvalidOperationException)
        {
            Error?.Invoke(recoveryException);
        }
        catch (Exception recoveryException)
        {
            Error?.Invoke(recoveryException);
        }
        finally
        {
            recoveryLock.Release();
        }
    }

    private async Task SendProgressLoopAsync(CancellationToken cancellationToken)
    {
        await PlaybackProgressLoop.RunAsync(
            SendProgressOnceAsync,
            TimeSpan.FromSeconds(1),
            cancellationToken,
            exception => Error?.Invoke(exception)).ConfigureAwait(false);
    }

    private async Task SendProgressOnceAsync(CancellationToken cancellationToken)
    {
        if (!leaseGate.TryGetGeneration(out var generation)) return;
        var identity = coordinator.ActiveOutput;
        if (identity is null) return;

        var position = await output.GetPositionMsAsync(cancellationToken).ConfigureAwait(false);
        if (position is not { } currentPosition
            || !coordinator.TryGetActiveQueueId(identity, out var queueId))
        {
            return;
        }

        CurrentPositionMs = currentPosition;
        PositionChanged?.Invoke(currentPosition);
        await socket.SendProgressAsync(currentPosition, queueId, generation,
            cancellationToken).ConfigureAwait(false);
    }

    private async Task ApplyAssignmentAsync(PlayerAssignment assignment)
    {
        leaseGate.Apply(assignment);
        if (!leaseGate.TryGetGeneration(out _))
        {
            await FenceOutputAsync().ConfigureAwait(false);
        }
    }

    private async Task FenceOutputAsync()
    {
        try
        {
            snapshotPump.Fence();
            await coordinator.InvalidateOutputProjectionAsync(lifetime.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
        catch (Exception exception) { Error?.Invoke(exception); }
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0) return;
        lifetime.Cancel();
        await socket.DisposeAsync().ConfigureAwait(false);
        if (socketTask is not null)
        {
            try { await socketTask.ConfigureAwait(false); } catch (OperationCanceledException) { }
        }
        if (progressTask is not null)
        {
            try { await progressTask.ConfigureAwait(false); } catch (OperationCanceledException) { }
        }
        await snapshotPump.DisposeAsync().ConfigureAwait(false);
        await output.DisposeAsync().ConfigureAwait(false);
        server.Dispose();
        lifetime.Dispose();
    }
}
