using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Playback;

public enum ChannelMapMode
{
    STEREO,
    LEFT_MONO,
    RIGHT_MONO,
}

/** Identifies the media instance currently committed to the local output. */
public sealed record ActiveOutputIdentity(long? QueueId, long FileId, long Generation);

public interface IPlaybackServerApi
{
    Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default);

    string StreamUrl(long fileId);

    /** Returns the server-authoritative native/sidecar playback choice. */
    Task<PlaybackDescriptor?> ResolvePlaybackAsync(
        long fileId,
        bool forceTranscode,
        CancellationToken cancellationToken = default) => Task.FromResult<PlaybackDescriptor?>(null);
}

public interface IPlaybackOutput
{
    Task LoadAsync(string streamUrl, long fileId, CancellationToken cancellationToken = default);

    Task PlayAsync(CancellationToken cancellationToken = default);

    Task PauseAsync(CancellationToken cancellationToken = default);

    Task StopAsync(CancellationToken cancellationToken = default);

    Task SeekAsync(long positionMs, CancellationToken cancellationToken = default);

    Task SetVolumeAsync(int volume, bool muted, CancellationToken cancellationToken = default);

    /** The index is relative to the audio streams exposed by the server. */
    Task SetAudioTrackAsync(int audioRelativeIndex, CancellationToken cancellationToken = default);

    Task SetChannelModeAsync(ChannelMapMode mode, CancellationToken cancellationToken = default);
}

/** Stops an existing local renderer without creating a replacement renderer. */
public interface IPlaybackOutputFence
{
    Task StopIfRunningAsync(CancellationToken cancellationToken = default);
}
