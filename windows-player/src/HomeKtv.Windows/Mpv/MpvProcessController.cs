using System.IO;
using System.Text.Json;
using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.Mpv;

public sealed class MpvProcessController : IPlaybackOutput, IPlaybackOutputFence, IAsyncDisposable
{
    private readonly IMpvSessionFactory sessionFactory;
    private readonly TimeSpan commandTimeout;
    private readonly SemaphoreSlim commandLock = new(1, 1);
    private readonly object mediaIdentityLock = new();
    private IMpvSession? session;
    private string? loadedUrl;
    private long? loadedFileId;
    private int? volume;
    private bool? muted;
    private ChannelMapMode channelMode = ChannelMapMode.STEREO;
    private int? audioTrackRelativeIndex;
    private long positionMs;
    private bool? paused;
    private long? playlistEntryId;
    private bool mediaReady;
    private int disposed;

    public MpvProcessController(IMpvSessionFactory sessionFactory, TimeSpan? commandTimeout = null)
    {
        this.sessionFactory = sessionFactory;
        this.commandTimeout = commandTimeout ?? TimeSpan.FromSeconds(5);
        if (this.commandTimeout <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(commandTimeout));
    }

    public bool IsMpvRunning => session?.IsAlive == true;
    public long? CurrentFileId => loadedFileId;

    public event Action<long>? PlaybackFinished;
    public event Action<Exception>? SessionFaulted;

    /// <summary>mpv 判定当前媒体播放失败（流打不开、解码失败等）时触发，参数为 fileId。</summary>
    public event Action<long>? PlaybackFailed;

    public async Task<long?> GetPositionMsAsync(CancellationToken cancellationToken = default)
    {
        await commandLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (loadedUrl is null) return null;
            var value = await ExecuteWithRecoveryLockedAsync(
                    active => ExecuteCommandAsync(active, ["get_property", "time-pos"], cancellationToken),
                    cancellationToken)
                .ConfigureAwait(false);
            if (value is not { } position || position.ValueKind != JsonValueKind.Number)
            {
                return null;
            }

            if (!position.TryGetDouble(out var seconds))
            {
                return null;
            }

            positionMs = Math.Max(0, (long)Math.Round(seconds * 1000, MidpointRounding.AwayFromZero));
            return positionMs;
        }
        finally
        {
            commandLock.Release();
        }
    }

    public Task LoadAsync(string streamUrl, long fileId, CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            // A replacement must never become audible before the coordinator has applied the
            // latest server snapshot. This also protects callers other than PlaybackCoordinator.
            await ExecuteCommandAsync(active, MpvCommands.Pause(), cancellationToken)
                .ConfigureAwait(false);
            await ExecuteCommandAsync(active, MpvCommands.LoadFile(streamUrl), cancellationToken)
                .ConfigureAwait(false);
            loadedUrl = streamUrl;
            loadedFileId = fileId;
            ResetMediaIdentity();
            positionMs = 0;
            audioTrackRelativeIndex = null;
            channelMode = ChannelMapMode.STEREO;
            paused = null;
        }, cancellationToken);

    public Task PlayAsync(CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            await ExecuteCommandAsync(active, MpvCommands.Play(), cancellationToken).ConfigureAwait(false);
            paused = false;
        }, cancellationToken);

    public Task PauseAsync(CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            await ExecuteCommandAsync(active, MpvCommands.Pause(), cancellationToken).ConfigureAwait(false);
            paused = true;
        }, cancellationToken);

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        await ExecuteWithRecoveryAsync(async active =>
        {
            await ExecuteCommandAsync(active, MpvCommands.Stop(), cancellationToken).ConfigureAwait(false);
            loadedUrl = null;
            loadedFileId = null;
            ResetMediaIdentity();
            positionMs = 0;
            audioTrackRelativeIndex = null;
            channelMode = ChannelMapMode.STEREO;
            paused = null;
        }, cancellationToken).ConfigureAwait(false);
    }

    public async Task StopIfRunningAsync(CancellationToken cancellationToken = default)
    {
        await commandLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var active = session;
            if (active?.IsAlive != true)
            {
                ResetPlaybackState();
                return;
            }

            try
            {
                await ExecuteCommandAsync(active, MpvCommands.Stop(), cancellationToken)
                    .ConfigureAwait(false);
            }
            catch
            {
                session = null;
                try { await active.DisposeAsync().ConfigureAwait(false); } catch { }
                throw;
            }
            finally
            {
                ResetPlaybackState();
            }
        }
        finally
        {
            commandLock.Release();
        }
    }

    public Task SeekAsync(long requestedPositionMs, CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            var target = Math.Max(0, requestedPositionMs);
            await ExecuteCommandAsync(active, MpvCommands.Seek(target), cancellationToken).ConfigureAwait(false);
            positionMs = target;
        }, cancellationToken);

    public Task SetVolumeAsync(int requestedVolume, bool requestedMuted,
        CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            var targetVolume = Math.Clamp(requestedVolume, 0, 100);
            await ExecuteCommandAsync(active, MpvCommands.SetVolume(targetVolume), cancellationToken)
                .ConfigureAwait(false);
            await ExecuteCommandAsync(active, MpvCommands.SetMuted(requestedMuted), cancellationToken)
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
            await ExecuteCommandAsync(active, MpvCommands.SetAudioTrack(trackId), cancellationToken)
                .ConfigureAwait(false);
            audioTrackRelativeIndex = requestedRelativeIndex;
        }, cancellationToken);

    public Task SetChannelModeAsync(ChannelMapMode requestedMode,
        CancellationToken cancellationToken = default) =>
        ExecuteWithRecoveryAsync(async active =>
        {
            await ExecuteCommandAsync(active, MpvCommands.SetChannelFilter(requestedMode), cancellationToken)
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
            await ExecuteCommandAsync(active, MpvCommands.SetFullscreenScreen(screenIndex), cancellationToken)
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
            return await ExecuteWithRecoveryLockedAsync(operation, cancellationToken)
                .ConfigureAwait(false);
        }
        finally
        {
            commandLock.Release();
        }
    }

    private async Task<T> ExecuteWithRecoveryLockedAsync<T>(
        Func<IMpvSession, Task<T>> operation,
        CancellationToken cancellationToken)
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
        ResetMediaIdentity();
        await ExecuteCommandAsync(active, MpvCommands.Pause(), cancellationToken)
            .ConfigureAwait(false);
        await ExecuteCommandAsync(active, MpvCommands.LoadFile(loadedUrl!), cancellationToken)
            .ConfigureAwait(false);
        if (volume is { } targetVolume && muted is { } targetMuted)
        {
            await ExecuteCommandAsync(active, MpvCommands.SetVolume(targetVolume), cancellationToken)
                .ConfigureAwait(false);
            await ExecuteCommandAsync(active, MpvCommands.SetMuted(targetMuted), cancellationToken)
                .ConfigureAwait(false);
        }

        await ExecuteCommandAsync(active, MpvCommands.SetChannelFilter(channelMode), cancellationToken)
            .ConfigureAwait(false);
        if (audioTrackRelativeIndex is { } relativeIndex)
        {
            var trackId = await ResolveAudioTrackIdAsync(active, relativeIndex, cancellationToken)
                .ConfigureAwait(false);
            await ExecuteCommandAsync(active, MpvCommands.SetAudioTrack(trackId), cancellationToken)
                .ConfigureAwait(false);
        }

        if (positionMs > 0)
        {
            await ExecuteCommandAsync(active, MpvCommands.Seek(positionMs), cancellationToken)
                .ConfigureAwait(false);
        }

        if (paused is { } shouldPause)
        {
        await ExecuteCommandAsync(active, shouldPause ? MpvCommands.Pause() : MpvCommands.Play(),
            cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task<JsonElement?> ExecuteCommandAsync(
        IMpvSession active,
        IReadOnlyList<object?> command,
        CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(commandTimeout);
        try
        {
            return await active.ExecuteAsync(command, timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (
            !cancellationToken.IsCancellationRequested && timeout.IsCancellationRequested)
        {
            throw new MpvCommandTimeoutException(
                $"mpv command timed out after {commandTimeout.TotalMilliseconds:0} ms.");
        }
    }

    private async Task<int> ResolveAudioTrackIdAsync(
        IMpvSession active,
        int relativeIndex,
        CancellationToken cancellationToken)
    {
        // mpv accepts loadfile before demuxing has exposed its tracks. Polling
        // this read-only property avoids treating that normal startup window as
        // a playback failure and never reloads or seeks the media.
        for (var attempt = 0; attempt < 30; attempt++)
        {
        var trackList = await ExecuteCommandAsync(active, MpvCommands.GetTrackList(), cancellationToken)
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

        if (string.Equals(notification.Name, "start-file", StringComparison.OrdinalIgnoreCase))
        {
            // 装载一开始就记住对应的 playlist entry：装载失败时只会收到 end-file，
            // 那时还没有 file-loaded，无法再用 mediaReady 判断这次失败属于谁。
            if (TryGetPlaylistEntryId(notification.Data, out var startedEntryId))
            {
                lock (mediaIdentityLock)
                {
                    if (loadedFileId is not null)
                    {
                        playlistEntryId = startedEntryId;
                        mediaReady = false;
                    }
                }
            }

            return;
        }

        if (string.Equals(notification.Name, "file-loaded", StringComparison.OrdinalIgnoreCase))
        {
            if (TryGetPlaylistEntryId(notification.Data, out var entryId))
            {
                lock (mediaIdentityLock)
                {
                    if (loadedFileId is not null)
                    {
                        playlistEntryId = entryId;
                        mediaReady = true;
                    }
                }
            }

            return;
        }

        if (!string.Equals(notification.Name, "end-file", StringComparison.OrdinalIgnoreCase)
            || notification.Data is not { } data
            || !data.TryGetProperty("reason", out var reasonElement))
        {
            return;
        }

        var reason = reasonElement.GetString();
        if (string.Equals(reason, "eof", StringComparison.OrdinalIgnoreCase))
        {
            HandleEndOfFile(notification);
            return;
        }

        // mpv 的 reason 只有 eof / stop / quit / error / redirect / unknown。
        // 只有 error 表示真正的播放失败；stop（被命令结束）、quit、redirect（播放列表跳转）
        // 都是预期内的终止，不能当作失败上报，否则会把正常切歌误报成故障。
        if (!string.Equals(reason, "error", StringComparison.OrdinalIgnoreCase)) return;

        HandlePlaybackFailure(notification);
    }

    private void HandleEndOfFile(MpvNotification notification)
    {
        if (!TryGetPlaylistEntryId(notification.Data, out var finishedEntryId))
        {
            return;
        }

        long? fileId;
        lock (mediaIdentityLock)
        {
            if (!mediaReady || playlistEntryId != finishedEntryId || loadedFileId is not { } currentFileId)
            {
                return;
            }

            mediaReady = false;
            fileId = currentFileId;
        }

        if (fileId is { } completedFileId)
        {
            PlaybackFinished?.Invoke(completedFileId);
        }
    }

    /**
     * 流级失败：mpv 的 loadfile 命令本身是成功的，失败是在之后异步出现的。
     * 若不在这里上报，PlaybackTerminal 的 play_error 就没有任何出口，
     * 表现是画面黑屏卡死、不跳歌、不报错、不重连。
     */
    private void HandlePlaybackFailure(MpvNotification notification)
    {
        long? fileId;
        lock (mediaIdentityLock)
        {
            // 已知本次装载的 entry 时，只接受与之匹配的失败事件：
            // 上一次装载迟到的 end-file 不能算到当前媒体头上。
            if (playlistEntryId is { } expectedEntry
                && (!TryGetPlaylistEntryId(notification.Data, out var failedEntryId)
                    || failedEntryId != expectedEntry))
            {
                return;
            }

            fileId = loadedFileId;
            mediaReady = false;
            playlistEntryId = null;
        }

        if (fileId is { } failedFileId)
        {
            PlaybackFailed?.Invoke(failedFileId);
        }
    }

    private static bool TryGetPlaylistEntryId(JsonElement? data, out long entryId)
    {
        if (data is { ValueKind: JsonValueKind.Object } value
            && value.TryGetProperty("playlist_entry_id", out var entry)
            && entry.ValueKind == JsonValueKind.Number
            && entry.TryGetInt64(out entryId))
        {
            return true;
        }

        entryId = default;
        return false;
    }

    private void ResetMediaIdentity()
    {
        lock (mediaIdentityLock)
        {
            playlistEntryId = null;
            mediaReady = false;
        }
    }

    private void ResetPlaybackState()
    {
        loadedUrl = null;
        loadedFileId = null;
        ResetMediaIdentity();
        positionMs = 0;
        audioTrackRelativeIndex = null;
        channelMode = ChannelMapMode.STEREO;
        paused = null;
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
