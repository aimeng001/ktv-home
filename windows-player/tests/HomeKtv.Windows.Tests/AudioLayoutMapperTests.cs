using HomeKtv.Windows.Playback;
using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Tests;

public sealed class AudioLayoutMapperTests
{
    [Theory]
    [InlineData(100, 200, ChannelMapMode.STEREO, 100, 200)]
    [InlineData(100, 200, ChannelMapMode.LEFT_MONO, 100, 100)]
    [InlineData(100, 200, ChannelMapMode.RIGHT_MONO, 200, 200)]
    public void Stereo_frames_are_mapped_without_swapping(int left, int right, ChannelMapMode mode,
        int expectedLeft, int expectedRight)
    {
        Assert.Equal(new[] { expectedLeft, expectedRight }, AudioLayoutMapper.MapStereoFrame(left, right, mode));
    }

    [Fact]
    public void Non_dual_channel_layouts_keep_stereo_output()
    {
        Assert.Equal(
            ChannelMapMode.STEREO,
            AudioLayoutMapper.ChannelModeFor(
                "original",
                new AudioLayoutDto(AudioLayout.DUAL_TRACK)));
    }

    [Fact]
    public void Swapped_dual_channel_definition_changes_the_selected_role()
    {
        var layout = new AudioLayoutDto(
            AudioLayout.DUAL_CHANNEL,
            OriginalChannel: AudioChannel.RIGHT,
            AccompanimentChannel: AudioChannel.LEFT);

        Assert.Equal(ChannelMapMode.RIGHT_MONO, AudioLayoutMapper.ChannelModeFor("original", layout));
        Assert.Equal(ChannelMapMode.LEFT_MONO, AudioLayoutMapper.ChannelModeFor("accompaniment", layout));
    }

    [Fact]
    public void Dual_channel_roles_are_case_insensitive_and_unknown_roles_keep_stereo()
    {
        var layout = new AudioLayoutDto(AudioLayout.DUAL_CHANNEL);

        Assert.Equal(ChannelMapMode.LEFT_MONO, AudioLayoutMapper.ChannelModeFor("ORIGINAL", layout));
        Assert.Equal(ChannelMapMode.RIGHT_MONO, AudioLayoutMapper.ChannelModeFor("AcCoMpAnImEnT", layout));
        Assert.Equal(ChannelMapMode.STEREO, AudioLayoutMapper.ChannelModeFor(null, layout));
        Assert.Equal(ChannelMapMode.STEREO, AudioLayoutMapper.ChannelModeFor("unknown", layout));
    }

    [Fact]
    public void Track_selection_prefers_explicit_role_indices_then_uses_legacy_fallbacks()
    {
        var explicitIndices = new AudioLayoutDto(
            AudioLayout.DUAL_TRACK,
            OriginalTrackIndex: 2,
            AccompanimentTrackIndex: 3);
        Assert.Equal(2, AudioLayoutMapper.AudioTrackIndexFor("original", explicitIndices, 4));
        Assert.Equal(3, AudioLayoutMapper.AudioTrackIndexFor("accompaniment", explicitIndices, 4));

        var vocalTrackFallback = new AudioLayoutDto(AudioLayout.DUAL_TRACK);
        Assert.Equal(1, AudioLayoutMapper.AudioTrackIndexFor("accompaniment", vocalTrackFallback, 2, 1));
        Assert.Equal(1, AudioLayoutMapper.AudioTrackIndexFor("accompaniment", vocalTrackFallback, 2));
        Assert.Equal(0, AudioLayoutMapper.AudioTrackIndexFor("accompaniment", vocalTrackFallback, 1));
        Assert.Equal(0, AudioLayoutMapper.AudioTrackIndexFor("original", vocalTrackFallback, 2));
    }

    [Fact]
    public void Original_track_fallback_avoids_a_track_marked_as_accompaniment()
    {
        var layout = new AudioLayoutDto(AudioLayout.DUAL_TRACK, AccompanimentTrackIndex: 0);

        Assert.Equal(1, AudioLayoutMapper.AudioTrackIndexFor("original", layout, 2));
        Assert.Equal(0, AudioLayoutMapper.AudioTrackIndexFor("original", layout, 1));
        Assert.Equal(1, AudioLayoutMapper.AudioTrackIndexFor("ORIGINAL", layout, 2));
    }

    [Fact]
    public void File_source_overload_uses_its_track_count_and_vocal_track_index()
    {
        var file = new FileSource(1, "mkv", 2, 1, null, 0,
            new AudioLayoutDto(AudioLayout.DUAL_TRACK));

        Assert.Equal(1, AudioLayoutMapper.AudioTrackIndexFor("accompaniment", file));
    }

    [Fact]
    public void Unknown_channel_map_mode_is_rejected()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            AudioLayoutMapper.MapStereoFrame(1, 2, (ChannelMapMode)999));
    }
}
