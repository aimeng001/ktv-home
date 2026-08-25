using System.ComponentModel;
using System.IO;
using System.Windows;
using Microsoft.Win32;
using HomeKtv.Windows.Display;
using HomeKtv.Windows.Mpv;
using HomeKtv.Windows.Playback;
using HomeKtv.Windows.ServerConnection;
using HomeKtv.Windows.Settings;

namespace HomeKtv.Windows;

public partial class MainWindow : Window
{
    private readonly PlayerSettingsStore settingsStore = new();
    private readonly DisplayManager displayManager = new();
    private IReadOnlyList<DisplayInfo> displays = [];
    private PlayerSettings settings = new();
    private PlaybackTerminal? terminal;
    private bool suppressDisplaySelection;

    public MainWindow()
    {
        InitializeComponent();
        settings = settingsStore.Load();
        ServerAddressText.Text = settings.ServerAddress;
        RefreshDisplays();
        ApplySavedWindowPlacement();
        SystemEvents.DisplaySettingsChanged += DisplaySettingsChanged;
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
            var targetDisplay = RefreshDisplays();
            var endpoint = ServerEndpoint.Parse(ServerAddressText.Text);
            settings.ServerAddress = ServerAddressText.Text.Trim();
            if (targetDisplay is not null)
            {
                settings.DisplayId = targetDisplay.StableId;
                settings.DisplayIndex = targetDisplay.Index;
            }
            PersistSettings();

            var server = new HttpServerApi(endpoint, settings.ClientToken);
            var socket = new KtvWebSocketClient(endpoint, settings.ClientToken);
            var output = new MpvProcessController(new MpvProcessSessionFactory(
                new MpvLaunchOptions(
                    settings.MpvExecutablePath,
                    targetDisplay?.Index ?? 0,
                    Fullscreen: true,
                    Borderless: true)));
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

    private void RefreshDisplays_Click(object sender, RoutedEventArgs e)
    {
        RefreshDisplays();
    }

    private async void DisplaySelector_SelectionChanged(object sender,
        System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (suppressDisplaySelection || DisplaySelectorCombo.SelectedItem is not DisplayInfo selected)
        {
            return;
        }

        settings.DisplayId = selected.StableId;
        settings.DisplayIndex = selected.Index;
        settings.Window.DisplayId = selected.StableId;
        PersistSettings();

        if (terminal?.IsOutputRunning != true) return;

        try
        {
            await terminal.SetDisplayAsync(selected.Index);
            StatusText.Text = $"播放输出已切换到 {selected.Label}";
        }
        catch (Exception exception)
        {
            StatusText.Text = $"切换播放显示器失败：{exception.Message}";
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

    private DisplayInfo? RefreshDisplays()
    {
        var previousId = settings.DisplayId;
        displays = displayManager.GetDisplays();
        var target = DisplaySelector.ResolveTarget(displays, settings.DisplayId, settings.DisplayIndex);

        suppressDisplaySelection = true;
        try
        {
            DisplaySelectorCombo.ItemsSource = displays;
            DisplaySelectorCombo.SelectedItem = target;
        }
        finally
        {
            suppressDisplaySelection = false;
        }

        if (target is null)
        {
            DisplayStatusText.Text = "未检测到显示器";
            return null;
        }

        settings.DisplayId = target.StableId;
        settings.DisplayIndex = target.Index;
        settings.Window ??= new WindowPlacementSettings();
        if (!string.Equals(settings.Window.DisplayId, target.StableId, StringComparison.OrdinalIgnoreCase)
            && string.IsNullOrWhiteSpace(settings.Window.DisplayId))
        {
            settings.Window.DisplayId = target.StableId;
        }
        PersistSettings();

        DisplayStatusText.Text = !string.IsNullOrWhiteSpace(previousId)
            && !string.Equals(previousId, target.StableId, StringComparison.OrdinalIgnoreCase)
            ? $"目标缺失，已回退到 {target.Label}"
            : target.Label;
        return target;
    }

    private void ApplySavedWindowPlacement()
    {
        settings.Window ??= new WindowPlacementSettings();
        var restored = WindowPlacementResolver.Restore(settings.Window, displays);
        settings.Window = restored;
        Width = restored.Width;
        Height = restored.Height;
        if (restored.Left is { } left && restored.Top is { } top)
        {
            WindowStartupLocation = WindowStartupLocation.Manual;
            WindowState = WindowState.Normal;
            Left = left;
            Top = top;
            if (restored.IsMaximized) WindowState = WindowState.Maximized;
        }
        PersistSettings();
    }

    private void DisplaySettingsChanged(object? sender, EventArgs e)
    {
        Dispatcher.BeginInvoke(new Action(HandleDisplaySettingsChanged));
    }

    private async void HandleDisplaySettingsChanged()
    {
        var previousId = settings.DisplayId;
        var previousIndex = settings.DisplayIndex;
        var target = RefreshDisplays();
        if (target is null)
        {
            StatusText.Text = "目标显示器已断开，暂未检测到可用显示器。";
            return;
        }

        var targetChanged = !string.Equals(previousId, target.StableId, StringComparison.OrdinalIgnoreCase)
            || previousIndex != target.Index;
        if (!targetChanged) return;

        ApplySavedWindowPlacement();
        if (terminal?.IsOutputRunning != true) return;

        try
        {
            await terminal.SetDisplayAsync(target.Index);
            StatusText.Text = $"目标显示器已变化，播放已回退到 {target.Label}";
        }
        catch (Exception exception)
        {
            StatusText.Text = $"显示器已变化，播放输出将在下次连接时使用 {target.Label}：{exception.Message}";
        }
    }

    private void PersistSettings()
    {
        try
        {
            settingsStore.Save(settings);
        }
        catch (IOException)
        {
            // A settings failure must not stop playback or close the player.
        }
    }

    private void SaveWindowPlacement()
    {
        settings.Window ??= new WindowPlacementSettings();
        var bounds = RestoreBounds;
        if (bounds.Width > 0 && bounds.Height > 0)
        {
            settings.Window.Left = bounds.Left;
            settings.Window.Top = bounds.Top;
            settings.Window.Width = bounds.Width;
            settings.Window.Height = bounds.Height;
        }

        settings.Window.IsMaximized = WindowState == WindowState.Maximized;
        settings.Window.DisplayId = (DisplaySelectorCombo.SelectedItem as DisplayInfo)?.StableId
            ?? settings.DisplayId;
        PersistSettings();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        SaveWindowPlacement();
        SystemEvents.DisplaySettingsChanged -= DisplaySettingsChanged;
        base.OnClosing(e);
    }

    protected override async void OnClosed(EventArgs e)
    {
        SystemEvents.DisplaySettingsChanged -= DisplaySettingsChanged;
        await DisposeTerminalAsync();
        base.OnClosed(e);
    }
}
