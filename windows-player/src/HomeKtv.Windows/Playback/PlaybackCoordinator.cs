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
            FileSource? file = null;
            try
            {
                var detail = await server.GetSongDetailAsync(playing.Song.Id, cancellationToken)
                    .ConfigureAwait(false);
                EnsureCurrent(cancellationToken, isCurrent);
                if (detail is null)
                {
                    throw new PlaybackAttemptException(
                        playing.QueueId.Value,
                        null,
                        $"Song {playing.Song.Id} no longer exists.",
                        PlaybackFailureKind.MediaMissing);
                }

                // The server may publish a higher-priority row that has not finished probing.
                // Selecting purely on priority would then request a stream that cannot be served.
                file = detail.Files
                    .Where(item => item.Ready != false)
                    .OrderByDescending(item => item.Priority)
                    .FirstOrDefault();
                if (file is null)
                {
                    throw new PlaybackAttemptException(
                        playing.QueueId.Value,
                        null,
                        $"No ready file source for song {playing.Song.Id}.",
                        PlaybackFailureKind.SourceUnavailable);
                }

                if (loadedQueueId is not null)
                {
                    await output.PauseAsync(cancellationToken).ConfigureAwait(false);
                    EnsureCurrent(cancellationToken, isCurrent);
                }
                await output.LoadAsync(server.StreamUrl(file.Id), file.Id, cancellationToken)
                    .ConfigureAwait(false);
                EnsureCurrent(cancellationToken, isCurrent);
            }
            catch (OperationCanceledException)
            {
                ResetLocalProjection();
                throw;
            }
            catch (Exception exception)
            {
                await StopAfterProjectionFailureAsync(isCurrent).ConfigureAwait(false);
                // HTTP/auth/timeout failures must retain their original type and
                // status; only an actual output command failure is wrapped with
                // the selected file identity.
                if (exception is PlaybackAttemptException || file is null) throw;
                throw new PlaybackAttemptException(
                    playing.QueueId.Value,
                    file?.Id,
                    $"Failed to load file {file?.Id} for queue item {playing.QueueId.Value}.",
                    PlaybackFailureKind.OutputFailure,
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

        // On a new queue item, bind the layout to the file actually selected
        // from SongDetail. Later vocal_changed snapshots describe a deliberate
        // runtime layout change for that same loaded file.
        var effectiveLayout = queueChanged ? loadedFile.AudioLayout : snapshot.AudioLayout;
        var audioChanged = queueChanged
            || !string.Equals(lastVocalMode, snapshot.VocalMode, StringComparison.OrdinalIgnoreCase)
            || lastAudioLayout != effectiveLayout;
        if (audioChanged)
        {
            await ApplyAudioAsync(snapshot.VocalMode, effectiveLayout, loadedFile, cancellationToken, isCurrent)
                .ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
            lastVocalMode = snapshot.VocalMode;
            lastAudioLayout = effectiveLayout;
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
            if (output is IPlaybackOutputFence fence)
            {
                await fence.StopIfRunningAsync(cancellationToken).ConfigureAwait(false);
            }
            else if (loadedQueueId is not null)
            {
                await output.StopAsync(cancellationToken).ConfigureAwait(false);
            }
            ResetLocalProjection();
        }
        finally
        {
            snapshotLock.Release();
        }
    }

    private async Task ApplyAudioAsync(
        string vocalMode,
        AudioLayoutDto audioLayout,
        FileSource file,
        CancellationToken cancellationToken,
        Func<bool> isCurrent)
    {
        if (audioLayout.Layout == AudioLayout.DUAL_TRACK)
        {
            await output.SetChannelModeAsync(ChannelMapMode.STEREO, cancellationToken)
                .ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
            await output.SetAudioTrackAsync(
                    AudioLayoutMapper.AudioTrackIndexFor(vocalMode, audioLayout, file.AudioTracks, file.VocalTrackIndex), cancellationToken)
                .ConfigureAwait(false);
            EnsureCurrent(cancellationToken, isCurrent);
            return;
        }

        await output.SetAudioTrackAsync(0, cancellationToken).ConfigureAwait(false);
        EnsureCurrent(cancellationToken, isCurrent);
        await output.SetChannelModeAsync(
                AudioLayoutMapper.ChannelModeFor(vocalMode, audioLayout), cancellationToken)
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

    private async Task StopAfterProjectionFailureAsync(
        Func<bool> isCurrent)
    {
        if (!isCurrent())
        {
            ResetLocalProjection();
            return;
        }

        try
        {
            await output.StopAsync(CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Preserve the original resolution/load failure; the terminal will
            // surface it while the local projection is still fenced below.
        }
        finally
        {
            ResetLocalProjection();
        }
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
