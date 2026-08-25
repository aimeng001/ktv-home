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
    private readonly CancellationTokenSource lifetime = new();
    private Task? socketTask;
    private Task? progressTask;
    private QueueSnapshot? snapshot;
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

        socket.ConnectionChanged += connected => ConnectionChanged?.Invoke(connected);
        socket.ConnectionError += exception => Error?.Invoke(exception);
        socket.ProgressReceived += position =>
        {
            CurrentPositionMs = position;
            PositionChanged?.Invoke(position);
        };
        socket.SnapshotReceived += ApplySnapshotFromSocketAsync;
        output.PlaybackFinished += () => _ = ReportFinishedAsync();
        output.SessionFaulted += exception => _ = RecoverOutputAsync(exception);
    }

    public bool IsConnected => socket.IsConnected;
    public QueueSnapshot? CurrentSnapshot => snapshot;
    public long CurrentPositionMs { get; private set; }

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
        if (initial is not null)
        {
            await ApplySnapshotAsync("sync_full", initial, cancellationToken).ConfigureAwait(false);
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

    private async Task ApplySnapshotFromSocketAsync(
        string eventType,
        QueueSnapshot incoming,
        CancellationToken cancellationToken)
    {
        await ApplySnapshotAsync(eventType, incoming, cancellationToken).ConfigureAwait(false);
    }

    private async Task ApplySnapshotAsync(
        string eventType,
        QueueSnapshot incoming,
        CancellationToken cancellationToken)
    {
        snapshot = incoming;
        if (eventType is "sync_full" or "playback_seeked" or "playback_restarted")
        {
            CurrentPositionMs = Math.Max(0, incoming.PositionMs);
            PositionChanged?.Invoke(CurrentPositionMs);
        }

        SnapshotChanged?.Invoke(incoming);
        try
        {
            await coordinator.ApplySnapshotAsync(eventType, incoming, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (Exception exception)
        {
            Error?.Invoke(exception);
            if (incoming.Playing?.QueueId is { } queueId)
            {
                await socket.SendPlayErrorAsync(queueId, output.CurrentFileId, exception.Message, cancellationToken)
                    .ConfigureAwait(false);
            }
        }
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
        }
    }

    private async Task ReportFinishedAsync()
    {
        var queueId = snapshot?.Playing?.QueueId;
        if (queueId is null) return;
        try
        {
            await socket.SendFinishedAsync(queueId.Value, lifetime.Token).ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is IOException or InvalidOperationException)
        {
            Error?.Invoke(exception);
        }
    }

    private async Task RecoverOutputAsync(Exception exception)
    {
        Error?.Invoke(exception);
        var current = snapshot;
        if (current is null || lifetime.IsCancellationRequested) return;

        try
        {
            coordinator.InvalidateOutputProjection();
            await coordinator.ApplySnapshotAsync("sync_full", current, lifetime.Token)
                .ConfigureAwait(false);
        }
        catch (Exception recoveryException)
        {
            Error?.Invoke(recoveryException);
        }
    }

    private async Task SendProgressLoopAsync(CancellationToken cancellationToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        while (await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false))
        {
            try
            {
                var position = await output.GetPositionMsAsync(cancellationToken).ConfigureAwait(false);
                if (position is not { } currentPosition) continue;
                CurrentPositionMs = currentPosition;
                PositionChanged?.Invoke(currentPosition);
                await socket.SendProgressAsync(currentPosition, snapshot?.Playing?.QueueId,
                    cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception exception) when (exception is IOException or InvalidOperationException)
            {
                Error?.Invoke(exception);
            }
        }
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
        await output.DisposeAsync().ConfigureAwait(false);
        server.Dispose();
        lifetime.Dispose();
    }
}
