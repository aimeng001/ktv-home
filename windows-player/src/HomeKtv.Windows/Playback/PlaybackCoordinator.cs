using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Playback;

/**
 * Projects the server's platform-neutral playback state onto a local output.
 * Server snapshots are the source of truth; mpv is only a local projection.
 */
public sealed class PlaybackCoordinator
{
    private readonly IPlaybackServerApi server;
    private readonly IPlaybackOutput output;
    private readonly object projectionStateLock = new();
    private long? loadedQueueId;
    private FileSource? loadedFile;
    private long outputGeneration;
    private ActiveOutputIdentity? activeOutput;
    private string? lastVocalMode;
    private AudioLayoutDto? lastAudioLayout;
    private int? lastVolume;
    private bool? lastMuted;
    private long lastSeekSequence = -1;
    private readonly SemaphoreSlim snapshotLock = new(1, 1);

    public PlaybackCoordinator(IPlaybackServerApi server, IPlaybackOutput output)
    {
        this.server = server;
        this.output = output;
    }

    public ActiveOutputIdentity? ActiveOutput
    {
        get
        {
            lock (projectionStateLock)
            {
                return activeOutput;
            }
        }
    }

    public long? ActiveOutputQueueId => ActiveOutput?.QueueId;

    /** Rechecks the same output generation after an asynchronous position read. */
    public bool TryGetActiveQueueId(ActiveOutputIdentity expected, out long queueId)
    {
        lock (projectionStateLock)
        {
            if (activeOutput == expected && expected.QueueId is { } activeQueueId)
            {
                queueId = activeQueueId;
                return true;
            }
        }

        queueId = default;
        return false;
    }

    public Task ApplySnapshotAsync(
        string eventType,
        QueueSnapshot snapshot,
        CancellationToken cancellationToken = default) =>
        ApplySnapshotAsync(eventType, snapshot, cancellationToken, static () => true);

    public async Task ApplySnapshotAsync(
        string eventType,
        QueueSnapshot snapshot,
        CancellationToken cancellationToken,
        Func<bool> isCurrent)
    {
        ArgumentNullException.ThrowIfNull(isCurrent);
        await snapshotLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            EnsureCurrent(cancellationToken, isCurrent);
            await ApplySnapshotCoreAsync(snapshot, cancellationToken, isCurrent)
                .ConfigureAwait(false);
        }
        finally
        {
            snapshotLock.Release();
        }
    }

    private async Task ApplySnapshotCoreAsync(
        QueueSnapshot snapshot,
        CancellationToken cancellationToken,
        Func<bool> isCurrent)
    {
        EnsureCurrent(cancellationToken, isCurrent);
        var playing = snapshot.Playing;
        if (snapshot.State.Equals("idle", StringComparison.OrdinalIgnoreCase)
            || playing?.QueueId is null
            || playing.Song?.Id is null)
        {
            if (loadedQueueId is not null)
            {
                await output.StopAsync(cancellationToken).ConfigureAwait(false);
                EnsureCurrent(cancellationToken, isCurrent);
            }

            ResetLocalProjection();
            return;
        }

        var queueChanged = loadedQueueId != playing.QueueId;
        if (queueChanged)
        {
            EnsureCurrent(cancellationToken, isCurrent);
            ClearActiveOutput();
            var detail = await server.GetSongDetailAsync(playing.Song.Id, cancellationToken)
                .ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
            var file = detail?.Files.OrderByDescending(item => item.Priority).FirstOrDefault();
            if (file is null)
            {
                throw new PlaybackAttemptException(
                    playing.QueueId.Value,
                    null,
                    $"No playable file source for song {playing.Song.Id}.");
            }

            try
            {
                if (loadedQueueId is not null)
                {
                    await output.PauseAsync(cancellationToken).ConfigureAwait(false);
                    EnsureCurrent(cancellationToken, isCurrent);
                }
                await output.LoadAsync(server.StreamUrl(file.Id), file.Id, cancellationToken)
                    .ConfigureAwait(false);
                EnsureCurrent(cancellationToken, isCurrent);
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                throw new PlaybackAttemptException(
                    playing.QueueId.Value,
                    file.Id,
                    $"Failed to load file {file.Id} for queue item {playing.QueueId.Value}.",
                    exception);
            }
            loadedQueueId = playing.QueueId;
            loadedFile = file;
            CommitActiveOutput(playing.QueueId, file.Id);
            lastVocalMode = null;
            lastAudioLayout = null;
            lastVolume = null;
            lastMuted = null;
            lastSeekSequence = -1;
        }

        if (loadedFile is null)
        {
            return;
        }

        var audioChanged = queueChanged
            || !string.Equals(lastVocalMode, snapshot.VocalMode, StringComparison.OrdinalIgnoreCase)
            || lastAudioLayout != loadedFile.AudioLayout;
        if (audioChanged)
        {
            await ApplyAudioAsync(snapshot.VocalMode, loadedFile, cancellationToken, isCurrent)
                .ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
            lastVocalMode = snapshot.VocalMode;
            lastAudioLayout = loadedFile.AudioLayout;
        }

        if (queueChanged || lastVolume != snapshot.Volume || lastMuted != snapshot.Muted)
        {
            await output.SetVolumeAsync(snapshot.Volume, snapshot.Muted, cancellationToken)
                .ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
            lastVolume = snapshot.Volume;
            lastMuted = snapshot.Muted;
        }

        if ((queueChanged && snapshot.PositionMs > 0)
            || (!queueChanged && snapshot.SeekSequence > lastSeekSequence))
        {
            await output.SeekAsync(Math.Max(0, snapshot.PositionMs), cancellationToken)
                .ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
        }

        if (queueChanged || snapshot.SeekSequence > lastSeekSequence)
        {
            lastSeekSequence = Math.Max(lastSeekSequence, snapshot.SeekSequence);
        }

        if (snapshot.State.Equals("paused", StringComparison.OrdinalIgnoreCase))
        {
            await output.PauseAsync(cancellationToken).ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
        }
        else
        {
            await output.PlayAsync(cancellationToken).ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
        }
    }

    /** Marks the local output as lost without changing the server's state. */
    public async Task InvalidateOutputProjectionAsync(CancellationToken cancellationToken = default)
    {
        await snapshotLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ResetLocalProjection();
        }
        finally
        {
            snapshotLock.Release();
        }
    }

    private async Task ApplyAudioAsync(
        string vocalMode,
        FileSource file,
        CancellationToken cancellationToken,
        Func<bool> isCurrent)
    {
        if (file.AudioLayout.Layout == AudioLayout.DUAL_TRACK)
        {
            await output.SetChannelModeAsync(ChannelMapMode.STEREO, cancellationToken)
                .ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
            await output.SetAudioTrackAsync(
                    AudioLayoutMapper.AudioTrackIndexFor(vocalMode, file), cancellationToken)
                .ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
            return;
        }

        await output.SetChannelModeAsync(
                AudioLayoutMapper.ChannelModeFor(vocalMode, file.AudioLayout), cancellationToken)
            .ConfigureAwait(false);
        EnsureCurrent(cancellationToken, isCurrent);
    }

    private void ResetLocalProjection()
    {
        loadedQueueId = null;
        loadedFile = null;
        ClearActiveOutput();
        lastVocalMode = null;
        lastAudioLayout = null;
        lastVolume = null;
        lastMuted = null;
        lastSeekSequence = -1;
    }

    private static void EnsureCurrent(CancellationToken cancellationToken, Func<bool> isCurrent)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (!isCurrent()) throw new OperationCanceledException(cancellationToken);
    }

    private void ClearActiveOutput()
    {
        lock (projectionStateLock)
        {
            activeOutput = null;
        }
    }

    private void CommitActiveOutput(long? queueId, long fileId)
    {
        lock (projectionStateLock)
        {
            activeOutput = new ActiveOutputIdentity(queueId, fileId, ++outputGeneration);
        }
    }
}
