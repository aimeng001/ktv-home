using HomeKtv.Windows.Playback;

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
}
