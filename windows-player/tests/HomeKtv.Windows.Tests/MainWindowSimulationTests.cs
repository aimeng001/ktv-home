using HomeKtv.Windows.Display;
using HomeKtv.Windows.Protocol;
using HomeKtv.Windows.Settings;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using Xunit;

namespace HomeKtv.Windows.Tests;

public sealed class MainWindowSimulationTests
{
    [Fact]
    public void ConstructorRequiresSettingsStoreAndDisplayManager()
    {
        RunOnSta(() =>
        {
            var settingsStore = new PlayerSettingsStore(Path.Combine(Path.GetTempPath(), "ktv-null-check-settings.json"));
            var displayManager = new DisplayManager(() => Array.Empty<DisplayInfo>());

            Assert.Throws<ArgumentNullException>(() => new MainWindow(null!, displayManager));
            Assert.Throws<ArgumentNullException>(() => new MainWindow(settingsStore, null!));
        });
    }

    [Fact]
    public void FirstStartupPersistsThePrimaryDisplayAndRestoresMaximizedWindow()
    {
        var directory = Directory.CreateTempSubdirectory("ktv-main-window-first-start-");
        try
        {
            var settingsStore = new PlayerSettingsStore(Path.Combine(directory.FullName, "settings.json"));
            settingsStore.Save(new PlayerSettings
            {
                Window = new WindowPlacementSettings { Width = 960, Height = 600, IsMaximized = true },
            });
            var primary = new DisplayInfo(0, "Primary simulated display", 1_920, 1_080, true)
            {
                Id = "SIMULATED-PRIMARY",
                WorkAreaLeft = 100,
                WorkAreaTop = 50,
                WorkAreaWidth = 1_600,
                WorkAreaHeight = 900,
            };

            RunOnSta(() =>
            {
                var window = new MainWindow(settingsStore, new DisplayManager(() => [primary]));
                try
                {
                    var selector = Assert.IsType<ComboBox>(window.FindName("DisplaySelectorCombo"));
                    Assert.Same(primary, selector.SelectedItem);
                    Assert.Equal(WindowState.Maximized, window.WindowState);

                    var persisted = settingsStore.Load();
                    Assert.Equal("SIMULATED-PRIMARY", persisted.DisplayId);
                    Assert.Equal("SIMULATED-PRIMARY", persisted.Window.DisplayId);
                }
                finally
                {
                    window.Close();
                }
            });
        }
        finally
        {
            directory.Delete(recursive: true);
        }
    }

    [Fact]
    public void SnapshotRenderingHandlesIdleAndPlayingStatesAndClampsControls()
    {
        var directory = Directory.CreateTempSubdirectory("ktv-main-window-snapshot-");
        try
        {
            var settingsStore = new PlayerSettingsStore(Path.Combine(directory.FullName, "settings.json"));
            var display = new DisplayInfo(0, "Simulated display", 1_920, 1_080, true) { Id = "SIMULATED-DISPLAY" };

            RunOnSta(() =>
            {
                var window = new MainWindow(settingsStore, new DisplayManager(() => [display]));
                try
                {
                    InvokePrivate(window, "RenderSnapshot", new QueueSnapshot(
                        Playing: null,
                        List: [],
                        State: "IDLE",
                        Volume: 140,
                        Muted: false,
                        VocalMode: "original",
                        AudioLayout: new AudioLayoutDto(),
                        TvOnline: false,
                        ConnectedPhones: 0,
                        PositionMs: 150_000));

                    Assert.Equal("暂无播放", Assert.IsType<TextBlock>(window.FindName("CurrentTitleText")).Text);
                    Assert.Equal(100, Assert.IsType<Slider>(window.FindName("VolumeSlider")).Value);
                    Assert.Equal(100_000, Assert.IsType<Slider>(window.FindName("SeekSlider")).Value);
                    Assert.Equal("02:30", Assert.IsType<TextBlock>(window.FindName("PositionText")).Text);

                    InvokePrivate(window, "RenderSnapshot", new QueueSnapshot(
                        Playing: new NowPlaying(42, new SongDto(
                            7, "Synthetic song", "Test artist", DurationMs: 5_000,
                            ArtistAvatarUrl: "http://127.0.0.1/unused-avatar"), null),
                        List: [],
                        State: "PLAYING",
                        Volume: 35,
                        Muted: false,
                        VocalMode: "accompaniment",
                        AudioLayout: new AudioLayoutDto(AudioLayout.DUAL_CHANNEL),
                        TvOnline: true,
                        ConnectedPhones: 1,
                        PositionMs: 4_000));

                    Assert.Equal("Synthetic song · Test artist", Assert.IsType<TextBlock>(window.FindName("CurrentTitleText")).Text);
                    Assert.Equal("PLAYING · accompaniment · DUAL_CHANNEL", Assert.IsType<TextBlock>(window.FindName("CurrentStateText")).Text);
                    Assert.Equal(35, Assert.IsType<Slider>(window.FindName("VolumeSlider")).Value);
                    Assert.Equal(5_000, Assert.IsType<Slider>(window.FindName("SeekSlider")).Maximum);
                    Assert.Equal(4_000, Assert.IsType<Slider>(window.FindName("SeekSlider")).Value);
                    Assert.Equal("00:04", Assert.IsType<TextBlock>(window.FindName("PositionText")).Text);
                }
                finally
                {
                    window.Close();
                }
            });
        }
        finally
        {
            directory.Delete(recursive: true);
        }
    }

    [Fact]
    public void StartupUsesInjectedDisplaysAndRestoresSavedWindowOnFallbackDisplay()
    {
        var directory = Directory.CreateTempSubdirectory("ktv-main-window-");
        try
        {
            var settingsStore = new PlayerSettingsStore(Path.Combine(directory.FullName, "settings.json"));
            settingsStore.Save(new PlayerSettings
            {
                DisplayId = "REMOVED-DISPLAY",
                DisplayIndex = 7,
                Window = new WindowPlacementSettings
                {
                    DisplayId = "REMOVED-DISPLAY",
                    Left = 4_000,
                    Top = 4_000,
                    Width = 1_000,
                    Height = 700,
                },
            });
            var display = new DisplayInfo(0, "Simulated display", 1_920, 1_080, true)
            {
                Id = "SIMULATED-DISPLAY-1",
                WorkAreaWidth = 1_280,
                WorkAreaHeight = 800,
            };
            var alternateDisplay = new DisplayInfo(1, "Second simulated display", 1_920, 1_080, false)
            {
                Id = "SIMULATED-DISPLAY-ALTERNATE",
                Left = 1_920,
                WorkAreaLeft = 1_920,
                WorkAreaWidth = 1_280,
                WorkAreaHeight = 800,
            };
            IReadOnlyList<DisplayInfo> displays = new[] { display, alternateDisplay };

            RunOnSta(() =>
            {
                var window = new MainWindow(settingsStore, new DisplayManager(() => displays));
                try
                {
                    var selector = Assert.IsType<ComboBox>(window.FindName("DisplaySelectorCombo"));
                    var status = Assert.IsType<TextBlock>(window.FindName("DisplayStatusText"));
                    Assert.Same(display, selector.SelectedItem);
                    Assert.Contains("回退", status.Text);
                    Assert.Equal(1_000, window.Width);
                    Assert.Equal(700, window.Height);
                    Assert.Equal(140, window.Left);
                    Assert.Equal(50, window.Top);

                    var persisted = settingsStore.Load();
                    Assert.Equal("SIMULATED-DISPLAY-1", persisted.DisplayId);
                    Assert.Equal(0, persisted.DisplayIndex);
                    Assert.Equal("SIMULATED-DISPLAY-1", persisted.Window.DisplayId);

                    InvokePrivate(window, "HandleDisplaySettingsChanged");
                    Assert.Equal(140, window.Left);
                    Assert.Equal(50, window.Top);

                    selector.SelectedItem = alternateDisplay;
                    var selectedDisplaySettings = settingsStore.Load();
                    Assert.Equal("SIMULATED-DISPLAY-ALTERNATE", selectedDisplaySettings.DisplayId);
                    Assert.Equal(1, selectedDisplaySettings.DisplayIndex);
                    Assert.Equal("SIMULATED-DISPLAY-ALTERNATE", selectedDisplaySettings.Window.DisplayId);

                    var replacementDisplay = new DisplayInfo(0, "Replacement simulated display", 1_920, 1_080, true)
                    {
                        Id = "SIMULATED-DISPLAY-2",
                        Left = 1_920,
                        WorkAreaLeft = 1_920,
                        WorkAreaWidth = 1_280,
                        WorkAreaHeight = 800,
                    };
                    displays = new[] { replacementDisplay };
                    InvokePrivate(window, "HandleDisplaySettingsChanged");

                    var afterTopologyChange = settingsStore.Load();
                    Assert.Same(replacementDisplay, selector.SelectedItem);
                    Assert.Equal("SIMULATED-DISPLAY-2", afterTopologyChange.DisplayId);
                    Assert.Equal("SIMULATED-DISPLAY-2", afterTopologyChange.Window.DisplayId);
                    Assert.Equal(2_060, window.Left);
                }
                finally
                {
                    window.Close();
                }
            });
        }
        finally
        {
            directory.Delete(recursive: true);
        }
    }

    [Fact]
    public void StartupWithNoDisplaysKeepsWindowUsableWithoutSelectingHardware()
    {
        var directory = Directory.CreateTempSubdirectory("ktv-main-window-");
        try
        {
            var settingsStore = new PlayerSettingsStore(Path.Combine(directory.FullName, "settings.json"));
            var settings = new PlayerSettings { DisplayId = "REMOVED-DISPLAY", DisplayIndex = 3 };
            settingsStore.Save(settings);

            RunOnSta(() =>
            {
                var window = new MainWindow(settingsStore, new DisplayManager(() => Array.Empty<DisplayInfo>()));
                try
                {
                    var selector = Assert.IsType<ComboBox>(window.FindName("DisplaySelectorCombo"));
                    var status = Assert.IsType<TextBlock>(window.FindName("DisplayStatusText"));
                    Assert.Null(selector.SelectedItem);
                    Assert.Equal("未检测到显示器", status.Text);
                    Assert.Equal(820, window.Width);
                    Assert.Equal(520, window.Height);
                    Assert.Equal("REMOVED-DISPLAY", settingsStore.Load().DisplayId);

                    InvokePrivate(window, "Play_Click", null, new RoutedEventArgs());
                    var connectionStatus = Assert.IsType<TextBlock>(window.FindName("StatusText"));
                    Assert.Equal("请先连接服务端。", connectionStatus.Text);

                    foreach (var action in new[]
                    {
                        "Pause_Click", "Stop_Click", "Replay_Click", "Next_Click", "Seek_Click",
                        "Original_Click", "Accompaniment_Click", "Swap_Click", "Volume_Click",
                    })
                    {
                        InvokePrivate(window, action, null, new RoutedEventArgs());
                        Assert.Equal("请先连接服务端。", connectionStatus.Text);
                    }

                    InvokePrivate(window, "Connect_Click", null, new RoutedEventArgs());
                    Assert.Equal("请先输入服务端地址或点击发现。", connectionStatus.Text);

                    Assert.IsType<TextBox>(window.FindName("ServerAddressText")).Text = "ftp://simulated.invalid";
                    InvokePrivate(window, "Connect_Click", null, new RoutedEventArgs());
                    Assert.Equal("连接失败：Server address must be an HTTP(S) host and port.", connectionStatus.Text);
                }
                finally
                {
                    window.Close();
                }
            });
        }
        finally
        {
            directory.Delete(recursive: true);
        }
    }

    [Fact]
    public void HotUnpluggingTheOnlySimulatedDisplayReportsNoOutputTarget()
    {
        var directory = Directory.CreateTempSubdirectory("ktv-main-window-");
        try
        {
            var settingsStore = new PlayerSettingsStore(Path.Combine(directory.FullName, "settings.json"));
            var display = new DisplayInfo(0, "Simulated display", 1_920, 1_080, true)
            {
                Id = "SIMULATED-DISPLAY-1",
            };
            IReadOnlyList<DisplayInfo> displays = [display];

            RunOnSta(() =>
            {
                var window = new MainWindow(settingsStore, new DisplayManager(() => displays));
                try
                {
                    displays = Array.Empty<DisplayInfo>();
                    InvokePrivate(window, "HandleDisplaySettingsChanged");

                    var selector = Assert.IsType<ComboBox>(window.FindName("DisplaySelectorCombo"));
                    var displayStatus = Assert.IsType<TextBlock>(window.FindName("DisplayStatusText"));
                    var connectionStatus = Assert.IsType<TextBlock>(window.FindName("StatusText"));
                    Assert.Null(selector.SelectedItem);
                    Assert.Equal("未检测到显示器", displayStatus.Text);
                    Assert.Equal("目标显示器已断开，暂未检测到可用显示器。", connectionStatus.Text);
                }
                finally
                {
                    window.Close();
                }
            });
        }
        finally
        {
            directory.Delete(recursive: true);
        }
    }

    [Fact]
    public void SettingsWriteFailureIsShownWithoutBreakingWindowStartup()
    {
        var directory = Directory.CreateTempSubdirectory("ktv-main-window-settings-");
        try
        {
            var settingsStore = new PlayerSettingsStore(directory.FullName);

            RunOnSta(() =>
            {
                var window = new MainWindow(settingsStore, new DisplayManager(() => Array.Empty<DisplayInfo>()));
                try
                {
                    var status = Assert.IsType<TextBlock>(window.FindName("StatusText"));
                    Assert.Equal("设置保存失败，请检查目录权限。", status.Text);
                    Assert.Equal(820, window.Width);
                    Assert.Equal(520, window.Height);
                }
                finally
                {
                    window.Close();
                }
            });
        }
        finally
        {
            directory.Delete(recursive: true);
        }
    }

    private static void RunOnSta(Action action)
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                action();
            }
            catch (Exception exception)
            {
                failure = exception;
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();
        if (failure is not null) System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(failure).Throw();
    }

    private static void InvokePrivate(MainWindow window, string methodName, params object?[] arguments)
    {
        var method = typeof(MainWindow).GetMethod(methodName, BindingFlags.Instance | BindingFlags.NonPublic);
        Assert.NotNull(method);
        method.Invoke(window, arguments);
    }
}
