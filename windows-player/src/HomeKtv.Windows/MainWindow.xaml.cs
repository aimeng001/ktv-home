using System.Windows;
using HomeKtv.Windows.Mpv;
using HomeKtv.Windows.Playback;
using HomeKtv.Windows.ServerConnection;
using HomeKtv.Windows.Settings;

namespace HomeKtv.Windows;

public partial class MainWindow : Window
{
    private readonly PlayerSettingsStore settingsStore = new();
    private PlayerSettings settings = new();
    private PlaybackTerminal? terminal;

    public MainWindow()
    {
        InitializeComponent();
        settings = settingsStore.Load();
        ServerAddressText.Text = settings.ServerAddress;
    }

    private async void Discover_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            StatusText.Text = "正在发现局域网 Home KTV 服务…";
            var servers = await new UdpDiscoveryClient().DiscoverAsync(TimeSpan.FromSeconds(1.5));
            var server = servers.FirstOrDefault();
            if (server is null)
            {
                StatusText.Text = "未发现服务，请手动输入地址。";
                return;
            }

            ServerAddressText.Text = server.Endpoint.BaseUri.ToString().TrimEnd('/');
            StatusText.Text = $"已发现：{server.Name}，请点击连接。";
        }
        catch (Exception exception)
        {
            StatusText.Text = $"发现失败：{exception.Message}";
        }
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(ServerAddressText.Text))
            {
                StatusText.Text = "请先输入服务端地址或点击发现。";
                return;
            }

            await DisposeTerminalAsync();
            var endpoint = ServerEndpoint.Parse(ServerAddressText.Text);
            settings.ServerAddress = ServerAddressText.Text.Trim();
            settingsStore.Save(settings);

            var server = new HttpServerApi(endpoint, settings.ClientToken);
            var socket = new KtvWebSocketClient(endpoint, settings.ClientToken);
            var output = new MpvProcessController(new MpvProcessSessionFactory(
                new MpvLaunchOptions(settings.MpvExecutablePath, settings.DisplayIndex)));
            terminal = new PlaybackTerminal(server, socket, output);
            terminal.ConnectionChanged += connected => Dispatcher.Invoke(() =>
                StatusText.Text = connected ? "WebSocket 已连接" : "WebSocket 断开，正在重连…");
            terminal.SnapshotChanged += snapshot => Dispatcher.Invoke(() => RenderSnapshot(snapshot));
            terminal.PositionChanged += position => Dispatcher.Invoke(() => RenderPosition(position));
            terminal.Error += exception => Dispatcher.Invoke(() =>
                StatusText.Text = $"播放端提示：{exception.Message}");

            StatusText.Text = "正在连接…";
            await terminal.ConnectAsync();
            StatusText.Text = "服务端已连接，等待播放状态。";
        }
        catch (Exception exception)
        {
            StatusText.Text = $"连接失败：{exception.Message}";
            await DisposeTerminalAsync();
        }
    }

    private async void Play_Click(object sender, RoutedEventArgs e) => await RunAsync(t => t.PlayAsync());
    private async void Pause_Click(object sender, RoutedEventArgs e) => await RunAsync(t => t.PauseAsync());
    private async void Stop_Click(object sender, RoutedEventArgs e) => await RunAsync(t => t.StopAsync());
    private async void Replay_Click(object sender, RoutedEventArgs e) => await RunAsync(t => t.ReplayAsync());
    private async void Next_Click(object sender, RoutedEventArgs e) => await RunAsync(t => t.NextAsync());
    private async void Seek_Click(object sender, RoutedEventArgs e) =>
        await RunAsync(t => t.SeekAsync((long)SeekSlider.Value));
    private async void Original_Click(object sender, RoutedEventArgs e) =>
        await RunAsync(t => t.SetVocalModeAsync("original"));
    private async void Accompaniment_Click(object sender, RoutedEventArgs e) =>
        await RunAsync(t => t.SetVocalModeAsync("accompaniment"));
    private async void Swap_Click(object sender, RoutedEventArgs e) =>
        await RunAsync(t => t.SwapVocalTracksAsync());
    private async void Volume_Click(object sender, RoutedEventArgs e) =>
        await RunAsync(t => t.SetVolumeAsync((int)VolumeSlider.Value));

    private async Task RunAsync(Func<PlaybackTerminal, Task> command)
    {
        if (terminal is null)
        {
            StatusText.Text = "请先连接服务端。";
            return;
        }

        try
        {
            await command(terminal);
        }
        catch (Exception exception)
        {
            StatusText.Text = $"控制失败：{exception.Message}";
        }
    }

    private void RenderSnapshot(Protocol.QueueSnapshot snapshot)
    {
        var song = snapshot.Playing?.Song;
        CurrentTitleText.Text = song is null ? "暂无播放" : $"{song.Title} · {song.Artist}";
        CurrentStateText.Text = $"{snapshot.State} · {snapshot.VocalMode} · {snapshot.AudioLayout.Layout}";
        VolumeSlider.Value = Math.Clamp(snapshot.Volume, 0, 100);
        if (song is { DurationMs: > 0 }) SeekSlider.Maximum = song.DurationMs;
        RenderPosition(snapshot.PositionMs);
    }

    private void RenderPosition(long positionMs)
    {
        if (SeekSlider.Maximum > 0)
        {
            SeekSlider.Value = Math.Clamp(positionMs, 0, (long)SeekSlider.Maximum);
        }
        PositionText.Text = FormatMilliseconds(positionMs);
    }

    private static string FormatMilliseconds(long value)
    {
        var seconds = Math.Max(0, value) / 1000;
        return $"{seconds / 60:00}:{seconds % 60:00}";
    }

    private async Task DisposeTerminalAsync()
    {
        var old = terminal;
        terminal = null;
        if (old is not null) await old.DisposeAsync();
    }

    protected override async void OnClosed(EventArgs e)
    {
        await DisposeTerminalAsync();
        base.OnClosed(e);
    }
}
