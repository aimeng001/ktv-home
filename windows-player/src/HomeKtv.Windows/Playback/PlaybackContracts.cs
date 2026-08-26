using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Playback;

public enum ChannelMapMode
{
    STEREO,
    LEFT_MONO,
    RIGHT_MONO,
}

public interface IPlaybackServerApi
{
    Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default);

    string StreamUrl(long fileId);
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
