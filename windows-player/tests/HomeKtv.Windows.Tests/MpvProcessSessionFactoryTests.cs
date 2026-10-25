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

    [Fact]
    public async Task Cancellation_while_connecting_is_preserved_and_not_wrapped_as_a_start_failure()
    {
        var currentExecutable = Environment.ProcessPath;
        Assert.False(string.IsNullOrWhiteSpace(currentExecutable));
        var factory = new MpvProcessSessionFactory(new MpvLaunchOptions(currentExecutable!));
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => factory.StartAsync(cancellation.Token));
    }

    [Fact]
    public async Task Missing_ipc_server_times_out_with_a_connection_error()
    {
        var currentExecutable = Environment.ProcessPath;
        Assert.False(string.IsNullOrWhiteSpace(currentExecutable));
        var factory = new MpvProcessSessionFactory(new MpvLaunchOptions(currentExecutable!));

        var exception = await Assert.ThrowsAsync<MpvConnectionException>(() => factory.StartAsync());

        Assert.Equal("Unable to connect to mpv IPC.", exception.Message);
        Assert.IsType<TimeoutException>(exception.InnerException);
    }

    [Fact]
    public void Start_arguments_include_hardware_decoding_with_safe_software_fallback()
    {
        var factory = new MpvProcessSessionFactory(new MpvLaunchOptions());
        var startInfo = factory.CreateStartInfo("test-pipe");

        Assert.Contains("--hwdec=auto-safe", startInfo.ArgumentList);
    }

    [Fact]
    public void Start_arguments_respect_fullscreen_and_borderless_options_and_clamp_the_screen_index()
    {
        var factory = new MpvProcessSessionFactory(new MpvLaunchOptions(
            ExecutablePath: "mpv.exe", ScreenIndex: -3, Fullscreen: false, Borderless: false));
        var startInfo = factory.CreateStartInfo("test-pipe");

        Assert.Contains("--fs-screen=0", startInfo.ArgumentList);
        Assert.Contains("--fullscreen=no", startInfo.ArgumentList);
        Assert.DoesNotContain("--border=no", startInfo.ArgumentList);
    }

    [Fact]
    public void SetScreenIndex_updates_future_process_arguments_and_clamps_negative_values()
    {
        var factory = new MpvProcessSessionFactory();
        factory.SetScreenIndex(2);
        Assert.Contains("--fs-screen=2", factory.CreateStartInfo("first-pipe").ArgumentList);

        factory.SetScreenIndex(-1);
        Assert.Contains("--fs-screen=0", factory.CreateStartInfo("second-pipe").ArgumentList);
    }
}
