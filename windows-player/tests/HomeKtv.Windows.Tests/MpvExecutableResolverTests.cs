using HomeKtv.Windows.Mpv;

namespace HomeKtv.Windows.Tests;

public sealed class MpvExecutableResolverTests
{
    [Fact]
    public void Prefers_mpv_next_to_the_player_before_using_path_lookup()
    {
        var applicationDirectory = Path.GetFullPath(
            Path.Combine(Path.GetTempPath(), "home-ktv-player"));
        var expected = Path.Combine(applicationDirectory, "mpv.exe");

        var resolved = MpvExecutableResolver.Resolve(
            "mpv.exe",
            applicationDirectory,
            path => string.Equals(path, expected, StringComparison.OrdinalIgnoreCase));

        Assert.Equal(expected, resolved);
    }

    [Fact]
    public void Falls_back_to_configured_name_when_adjacent_mpv_is_missing()
    {
        var resolved = MpvExecutableResolver.Resolve(
            "mpv.exe",
            Path.GetTempPath(),
            _ => false);

        Assert.Equal("mpv.exe", resolved);
    }

    [Fact]
    public void Preserves_an_absolute_configured_path()
    {
        var configured = Path.Combine(Path.GetTempPath(), "tools", "mpv.exe");

        var resolved = MpvExecutableResolver.Resolve(
            configured,
            Path.GetTempPath(),
            _ => false);

        Assert.Equal(configured, resolved);
    }
}
