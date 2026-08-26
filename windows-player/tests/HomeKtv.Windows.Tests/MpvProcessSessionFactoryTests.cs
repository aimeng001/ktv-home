using HomeKtv.Windows.Mpv;

namespace HomeKtv.Windows.Tests;

public sealed class MpvProcessSessionFactoryTests
{
    [Fact]
    public async Task Missing_mpv_reports_the_install_or_path_prerequisite()
    {
        var configuredPath = Path.Combine(
            Path.GetTempPath(), $"missing-home-ktv-mpv-{Guid.NewGuid():N}.exe");
        var factory = new MpvProcessSessionFactory(new MpvLaunchOptions(configuredPath));

        var exception = await Assert.ThrowsAsync<MpvConnectionException>(
            () => factory.StartAsync());

        Assert.Contains("mpv", exception.Message, StringComparison.OrdinalIgnoreCase);
        Assert.Contains(configuredPath, exception.Message, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("Install", exception.Message, StringComparison.OrdinalIgnoreCase);
    }
}
