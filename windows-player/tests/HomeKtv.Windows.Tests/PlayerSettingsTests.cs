using HomeKtv.Windows.Settings;
using System.IO;

namespace HomeKtv.Windows.Tests;

public sealed class PlayerSettingsTests
{
    [Fact]
    public void Reports_file_permission_failures_instead_of_succeeding_silently()
    {
        var result = PlayerSettingsSaveFeedback.Execute(() => throw new IOException("read-only"));

        Assert.False(result.Succeeded);
        Assert.Equal("设置保存失败，请检查目录权限。", result.ErrorMessage);
    }

    [Fact]
    public void Saves_and_loads_display_selection_and_window_placement()
    {
        var path = Path.Combine(Path.GetTempPath(), $"home-ktv-settings-{Guid.NewGuid():N}.json");
        try
        {
            var store = new PlayerSettingsStore(path);
            store.Save(new PlayerSettings
            {
                ServerAddress = "192.168.1.20:8080",
                PlayerCredential = "tv-secret",
                DisplayId = @"\\.\DISPLAY2",
                DisplayIndex = 1,
                Window = new WindowPlacementSettings
                {
                    DisplayId = @"\\.\DISPLAY2",
                    Left = 1920,
                    Top = 40,
                    Width = 1200,
                    Height = 800,
                    IsMaximized = true,
                },
            });

            var loaded = store.Load();

            Assert.Equal("tv-secret", loaded.PlayerCredential);
            Assert.Equal(@"\\.\DISPLAY2", loaded.DisplayId);
            Assert.Equal(1, loaded.DisplayIndex);
            Assert.Equal(@"\\.\DISPLAY2", loaded.Window.DisplayId);
            Assert.Equal(1920, loaded.Window.Left);
            Assert.Equal(40, loaded.Window.Top);
            Assert.Equal(1200, loaded.Window.Width);
            Assert.Equal(800, loaded.Window.Height);
            Assert.True(loaded.Window.IsMaximized);
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }

    [Fact]
    public void Loads_legacy_settings_without_display_id_or_window_data()
    {
        var path = Path.Combine(Path.GetTempPath(), $"home-ktv-settings-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(path, "{\"ServerAddress\":\"192.168.1.20:8080\",\"DisplayIndex\":1}");

            var loaded = new PlayerSettingsStore(path).Load();

            Assert.Equal("192.168.1.20:8080", loaded.ServerAddress);
            Assert.Equal(1, loaded.DisplayIndex);
            Assert.Equal(string.Empty, loaded.DisplayId);
            Assert.Equal(string.Empty, loaded.PlayerCredential);
            Assert.NotNull(loaded.Window);
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }
}
