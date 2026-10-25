using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Tests;

public sealed class PlaybackDescriptorReadinessTests
{
    [Theory]
    [InlineData("READY", "http://server/api/stream/42", true)]
    [InlineData("ready", "http://server/api/stream/42", true)]
    [InlineData("PREPARING", "http://server/api/stream/42", false)]
    [InlineData("READY", "", false)]
    [InlineData("FAILED", "http://server/api/stream/42", false)]
    public void Ready_requires_ready_status_and_a_nonblank_stream_url(
        string status, string? streamUrl, bool expected)
    {
        var descriptor = new PlaybackDescriptor(Status: status, StreamUrl: streamUrl);

        Assert.Equal(expected, descriptor.IsReady);
    }

    [Theory]
    [InlineData("PREPARING", true)]
    [InlineData("preparing", true)]
    [InlineData("READY", false)]
    public void Preparing_status_is_case_insensitive(string status, bool expected)
    {
        Assert.Equal(expected, new PlaybackDescriptor(Status: status).IsPreparing);
    }

    [Theory]
    [InlineData("FAILED", true)]
    [InlineData("failed", true)]
    [InlineData("PREPARING", false)]
    public void Failed_status_is_case_insensitive(string status, bool expected)
    {
        Assert.Equal(expected, new PlaybackDescriptor(Status: status).IsFailed);
    }
}
