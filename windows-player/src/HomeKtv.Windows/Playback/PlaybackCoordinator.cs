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
    private long? loadedQueueId;
    private FileSource? loadedFile;
    private string? lastVocalMode;
    private AudioLayoutDto? lastAudioLayout;
    private int? lastVolume;
    private bool? lastMuted;
    private readonly SemaphoreSlim snapshotLock = new(1, 1);

    public PlaybackCoordinator(IPlaybackServerApi server, IPlaybackOutput output)
    {
        this.server = server;
        this.output = output;
    }

    public async Task ApplySnapshotAsync(
        string eventType,
        QueueSnapshot snapshot,
        CancellationToken cancellationToken = default)
    {
        await snapshotLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await ApplySnapshotCoreAsync(eventType, snapshot, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            snapshotLock.Release();
        }
    }

    private async Task ApplySnapshotCoreAsync(
        string eventType,
        QueueSnapshot snapshot,
        CancellationToken cancellationToken)
    {
        var playing = snapshot.Playing;
        if (snapshot.State.Equals("idle", StringComparison.OrdinalIgnoreCase)
            || playing?.QueueId is null
            || playing.Song?.Id is null)
        {
            if (loadedQueueId is not null)
            {
                await output.StopAsync(cancellationToken).ConfigureAwait(false);
            }

            ResetLocalProjection();
            return;
        }

        var queueChanged = loadedQueueId != playing.QueueId;
        if (queueChanged)
        {
            var detail = await server.GetSongDetailAsync(playing.Song.Id, cancellationToken)
                .ConfigureAwait(false);
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
                await output.LoadAsync(server.StreamUrl(file.Id), file.Id, cancellationToken)
                    .ConfigureAwait(false);
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
            lastVocalMode = null;
            lastAudioLayout = null;
            lastVolume = null;
            lastMuted = null;
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
            await ApplyAudioAsync(snapshot.VocalMode, loadedFile, cancellationToken).ConfigureAwait(false);
            lastVocalMode = snapshot.VocalMode;
            lastAudioLayout = loadedFile.AudioLayout;
        }

        if (queueChanged || lastVolume != snapshot.Volume || lastMuted != snapshot.Muted)
        {
            await output.SetVolumeAsync(snapshot.Volume, snapshot.Muted, cancellationToken)
                .ConfigureAwait(false);
            lastVolume = snapshot.Volume;
            lastMuted = snapshot.Muted;
        }

        var explicitSeek = eventType is "playback_seeked" or "playback_restarted";
        if (explicitSeek || (queueChanged && snapshot.PositionMs > 0))
        {
            await output.SeekAsync(Math.Max(0, snapshot.PositionMs), cancellationToken)
                .ConfigureAwait(false);
        }

        if (snapshot.State.Equals("paused", StringComparison.OrdinalIgnoreCase))
        {
            await output.PauseAsync(cancellationToken).ConfigureAwait(false);
        }
        else
        {
            await output.PlayAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    /** Marks the local output as lost without changing the server's state. */
    public void InvalidateOutputProjection() => ResetLocalProjection();

    private async Task ApplyAudioAsync(
        string vocalMode,
        FileSource file,
        CancellationToken cancellationToken)
    {
        if (file.AudioLayout.Layout == AudioLayout.DUAL_TRACK)
        {
            await output.SetChannelModeAsync(ChannelMapMode.STEREO, cancellationToken)
                .ConfigureAwait(false);
            await output.SetAudioTrackAsync(
                    AudioLayoutMapper.AudioTrackIndexFor(vocalMode, file), cancellationToken)
                .ConfigureAwait(false);
            return;
        }

        await output.SetChannelModeAsync(
                AudioLayoutMapper.ChannelModeFor(vocalMode, file.AudioLayout), cancellationToken)
            .ConfigureAwait(false);
    }

    private void ResetLocalProjection()
    {
        loadedQueueId = null;
        loadedFile = null;
        lastVocalMode = null;
        lastAudioLayout = null;
        lastVolume = null;
        lastMuted = null;
    }
}
