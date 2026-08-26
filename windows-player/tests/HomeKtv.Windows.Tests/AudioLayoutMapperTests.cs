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
}
