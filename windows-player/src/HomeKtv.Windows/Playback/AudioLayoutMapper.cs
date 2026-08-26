using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Playback;

public static class AudioLayoutMapper
{
    public static int[] MapStereoFrame(int left, int right, ChannelMapMode mode) => mode switch
    {
        ChannelMapMode.STEREO => [left, right],
        ChannelMapMode.LEFT_MONO => [left, left],
        ChannelMapMode.RIGHT_MONO => [right, right],
        _ => throw new ArgumentOutOfRangeException(nameof(mode)),
    };

    public static ChannelMapMode ChannelModeFor(string? vocalMode, AudioLayoutDto layout)
    {
        if (layout.Layout != AudioLayout.DUAL_CHANNEL)
        {
            return ChannelMapMode.STEREO;
        }

        var selected = string.Equals(vocalMode, "original", StringComparison.OrdinalIgnoreCase)
            ? layout.OriginalChannel
            : string.Equals(vocalMode, "accompaniment", StringComparison.OrdinalIgnoreCase)
                ? layout.AccompanimentChannel
                : (AudioChannel?)null;

        return selected switch
        {
            AudioChannel.LEFT => ChannelMapMode.LEFT_MONO,
            AudioChannel.RIGHT => ChannelMapMode.RIGHT_MONO,
            _ => ChannelMapMode.STEREO,
        };
    }

    public static int AudioTrackIndexFor(string? vocalMode, FileSource file)
    {
        if (string.Equals(vocalMode, "accompaniment", StringComparison.OrdinalIgnoreCase))
        {
            return file.AudioLayout.AccompanimentTrackIndex
                ?? file.VocalTrackIndex
                ?? (file.AudioTracks > 1 ? 1 : 0);
        }

        return file.AudioLayout.OriginalTrackIndex
            ?? (file.AudioLayout.AccompanimentTrackIndex == 0 && file.AudioTracks > 1 ? 1 : 0);
    }
}
