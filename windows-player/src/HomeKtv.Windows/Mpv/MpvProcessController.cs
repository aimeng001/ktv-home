using System.IO;
using System.Text.Json;
using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.Mpv;

public sealed class MpvProcessController : IPlaybackOutput, IAsyncDisposable
{
    private readonly IMpvSessionFactory sessionFactory;
    private readonly SemaphoreSlim commandLock = new(1, 1);
    private IMpvSession? session;
    private string? loadedUrl;
    private long? loadedFileId;
    private int? volume;
    private bool? muted;
    private ChannelMapMode channelMode = ChannelMapMode.STEREO;
    private int? audioTrackRelativeIndex;
    private long positionMs;
    private bool? paused;
    private int disposed;

    public MpvProcessController(IMpvSessionFactory sessionFactory)
    {
        this.sessionFactory = sessionFactory;
    }

    public bool IsMpvRunning => session?.IsAlive == true;
    public long? CurrentFileId => loadedFileId;

    public event Action? PlaybackFinished;
    public event Action<Exception>? SessionFaulted;

    public async Task<long?> GetPositionMsAsync(CancellationToken cancellationToken = default)
    {
        if (loadedUrl is null) return null;
        var value = await ExecuteWithRecoveryAsync(
                active => active.ExecuteAsync(["get_property", "time-pos"], cancellationToken),
                cancellationToken)
            .ConfigureAwait(false);
        if (value is not { } position || position.ValueKind != JsonValueKind.Number)
        {
            return null;
        }

        return position.TryGetDouble(out var seconds)
            ? Math.Max(0, (long)Math.Round(seconds * 1000, MidpointRounding.AwayFromZero))
            : null;
    }

    public Task LoadAsync(string streamUrl, long fileId, CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            await active.ExecuteAsync(MpvCommands.LoadFile(streamUrl), cancellationToken)
                .ConfigureAwait(false);
            loadedUrl = streamUrl;
            loadedFileId = fileId;
            positionMs = 0;
            audioTrackRelativeIndex = null;
            channelMode = ChannelMapMode.STEREO;
            paused = null;
        }, cancellationToken);

    public Task PlayAsync(CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            await active.ExecuteAsync(MpvCommands.Play(), cancellationToken).ConfigureAwait(false);
            paused = false;
        }, cancellationToken);

    public Task PauseAsync(CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            await active.ExecuteAsync(MpvCommands.Pause(), cancellationToken).ConfigureAwait(false);
            paused = true;
        }, cancellationToken);

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        await ExecuteWithRecoveryAsync(async active =>
        {
            await active.ExecuteAsync(MpvCommands.Stop(), cancellationToken).ConfigureAwait(false);
        }, cancellationToken).ConfigureAwait(false);
        loadedUrl = null;
        loadedFileId = null;
        positionMs = 0;
        audioTrackRelativeIndex = null;
        paused = null;
    }

    public Task SeekAsync(long requestedPositionMs, CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            var target = Math.Max(0, requestedPositionMs);
            await active.ExecuteAsync(MpvCommands.Seek(target), cancellationToken).ConfigureAwait(false);
            positionMs = target;
        }, cancellationToken);

    public Task SetVolumeAsync(int requestedVolume, bool requestedMuted,
        CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            var targetVolume = Math.Clamp(requestedVolume, 0, 100);
            await active.ExecuteAsync(MpvCommands.SetVolume(targetVolume), cancellationToken)
                .ConfigureAwait(false);
            await active.ExecuteAsync(MpvCommands.SetMuted(requestedMuted), cancellationToken)
                .ConfigureAwait(false);
            volume = targetVolume;
            muted = requestedMuted;
        }, cancellationToken);

    public Task SetAudioTrackAsync(int requestedRelativeIndex,
        CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            var trackId = await ResolveAudioTrackIdAsync(active, requestedRelativeIndex, cancellationToken)
                .ConfigureAwait(false);
            await active.ExecuteAsync(MpvCommands.SetAudioTrack(trackId), cancellationToken)
                .ConfigureAwait(false);
            audioTrackRelativeIndex = requestedRelativeIndex;
        }, cancellationToken);

    public Task SetChannelModeAsync(ChannelMapMode requestedMode,
        CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            await active.ExecuteAsync(MpvCommands.SetChannelFilter(requestedMode), cancellationToken)
                .ConfigureAwait(false);
            channelMode = requestedMode;
        }, cancellationToken);

    public Task SetDisplayAsync(int requestedScreenIndex,
        CancellationToken cancellationToken = default)
    {
        var screenIndex = Math.Max(0, requestedScreenIndex);
        if (sessionFactory is IMpvDisplaySessionFactory displayFactory)
        {
            displayFactory.SetScreenIndex(screenIndex);
        }

        // Keep the selection for the next session without starting mpv just
        // because the user changed the target before the first song plays.
        if (!IsMpvRunning) return Task.CompletedTask;

        return ExecuteWithRecoveryAsync(async active =>
        {
            await active.ExecuteAsync(MpvCommands.SetFullscreenScreen(screenIndex), cancellationToken)
                .ConfigureAwait(false);
        }, cancellationToken);
    }

    /** Ensures a replacement mpv session exists after an asynchronous process exit. */
    public async Task RecoverAsync(CancellationToken cancellationToken = default)
    {
        await commandLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (session?.IsAlive == true) return;
            await StartSessionLockedAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            commandLock.Release();
        }
    }

    private async Task ExecuteWithRecoveryAsync(
        Func<IMpvSession, Task> operation,
        CancellationToken cancellationToken)
    {
        await ExecuteWithRecoveryAsync(async active =>
        {
            await operation(active).ConfigureAwait(false);
            return true;
        }, cancellationToken).ConfigureAwait(false);
    }

    private async Task<T> ExecuteWithRecoveryAsync<T>(
        Func<IMpvSession, Task<T>> operation,
        CancellationToken cancellationToken)
    {
        await commandLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            for (var attempt = 0; attempt < 2; attempt++)
            {
                try
                {
                    var active = await EnsureSessionLockedAsync(cancellationToken).ConfigureAwait(false);
                    return await operation(active).ConfigureAwait(false);
                }
                catch (Exception exception) when (attempt == 0 && IsRecoverable(exception))
                {
                    await ReplaceSessionLockedAsync(cancellationToken).ConfigureAwait(false);
                }
            }

            throw new MpvConnectionException("mpv command could not be recovered.");
        }
        finally
        {
            commandLock.Release();
        }
    }

    private async Task<IMpvSession> EnsureSessionLockedAsync(CancellationToken cancellationToken)
    {
        if (session?.IsAlive == true) return session;
        return await StartSessionLockedAsync(cancellationToken).ConfigureAwait(false);
    }

    private async Task<IMpvSession> StartSessionLockedAsync(CancellationToken cancellationToken)
    {
        var previous = session;
        session = null;
        if (previous is not null)
        {
            try { await previous.DisposeAsync().ConfigureAwait(false); }
            catch (Exception) { }
        }

        var started = await sessionFactory.StartAsync(cancellationToken).ConfigureAwait(false);
        session = started;
        started.NotificationReceived += notification => HandleNotification(started, notification);
        started.Disconnected += exception => HandleDisconnected(started, exception);
        try
        {
            if (loadedUrl is not null)
            {
                await RestoreProjectionLockedAsync(started, cancellationToken).ConfigureAwait(false);
            }

            return started;
        }
        catch
        {
            if (ReferenceEquals(session, started)) session = null;
            await started.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    private async Task ReplaceSessionLockedAsync(CancellationToken cancellationToken)
    {
        var old = session;
        session = null;
        if (old is not null)
        {
            await old.DisposeAsync().ConfigureAwait(false);
        }

        await StartSessionLockedAsync(cancellationToken).ConfigureAwait(false);
    }

    private async Task RestoreProjectionLockedAsync(IMpvSession active,
        CancellationToken cancellationToken)
    {
        await active.ExecuteAsync(MpvCommands.LoadFile(loadedUrl!), cancellationToken)
            .ConfigureAwait(false);
        if (volume is { } targetVolume && muted is { } targetMuted)
        {
            await active.ExecuteAsync(MpvCommands.SetVolume(targetVolume), cancellationToken)
                .ConfigureAwait(false);
            await active.ExecuteAsync(MpvCommands.SetMuted(targetMuted), cancellationToken)
                .ConfigureAwait(false);
        }

        await active.ExecuteAsync(MpvCommands.SetChannelFilter(channelMode), cancellationToken)
            .ConfigureAwait(false);
        if (audioTrackRelativeIndex is { } relativeIndex)
        {
            var trackId = await ResolveAudioTrackIdAsync(active, relativeIndex, cancellationToken)
                .ConfigureAwait(false);
            await active.ExecuteAsync(MpvCommands.SetAudioTrack(trackId), cancellationToken)
                .ConfigureAwait(false);
        }

        if (positionMs > 0)
        {
            await active.ExecuteAsync(MpvCommands.Seek(positionMs), cancellationToken)
                .ConfigureAwait(false);
        }

        if (paused is { } shouldPause)
        {
            await active.ExecuteAsync(shouldPause ? MpvCommands.Pause() : MpvCommands.Play(),
                cancellationToken).ConfigureAwait(false);
        }
    }

    private static async Task<int> ResolveAudioTrackIdAsync(
        IMpvSession active,
        int relativeIndex,
        CancellationToken cancellationToken)
    {
        // mpv accepts loadfile before demuxing has exposed its tracks. Polling
        // this read-only property avoids treating that normal startup window as
        // a playback failure and never reloads or seeks the media.
        for (var attempt = 0; attempt < 30; attempt++)
        {
            var trackList = await active.ExecuteAsync(MpvCommands.GetTrackList(), cancellationToken)
                .ConfigureAwait(false);
            var trackId = MpvTrackMapper.ResolveAudioTrackId(trackList, relativeIndex);
            if (trackId is { } resolved) return resolved;
            await Task.Delay(TimeSpan.FromMilliseconds(100), cancellationToken).ConfigureAwait(false);
        }

        throw new MpvCommandException($"mpv did not expose audio track {relativeIndex}.");
    }

    private void HandleNotification(IMpvSession source, MpvNotification notification)
    {
        if (!ReferenceEquals(source, session)) return;
        if (!string.Equals(notification.Name, "end-file", StringComparison.OrdinalIgnoreCase)) return;
        if (notification.Data is { } data
            && data.TryGetProperty("reason", out var reason)
            && string.Equals(reason.GetString(), "eof", StringComparison.OrdinalIgnoreCase))
        {
            PlaybackFinished?.Invoke();
        }
    }

    private void HandleDisconnected(IMpvSession source, Exception exception)
    {
        if (!ReferenceEquals(source, session)) return;
        SessionFaulted?.Invoke(exception);
    }

    private static bool IsRecoverable(Exception exception) => exception is MpvConnectionException
        or IOException
        or ObjectDisposedException;

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0) return;
        await commandLock.WaitAsync().ConfigureAwait(false);
        try
        {
            var active = session;
            session = null;
            if (active is not null) await active.DisposeAsync().ConfigureAwait(false);
        }
        finally
        {
            commandLock.Release();
            commandLock.Dispose();
        }
    }
}
