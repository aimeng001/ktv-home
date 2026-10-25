using HomeKtv.Windows.Settings;
using System.IO;

namespace HomeKtv.Windows.Tests;

public sealed class PlayerSettingsTests
{
    [Fact]
    public void Can_create_a_store_with_the_default_application_settings_location()
    {
        Assert.NotNull(new PlayerSettingsStore());
    }

    [Fact]
    public void Reports_success_when_the_save_action_completes()
    {
        var saveCalled = false;

        var result = PlayerSettingsSaveFeedback.Execute(() => saveCalled = true);

        Assert.True(saveCalled);
        Assert.True(result.Succeeded);
        Assert.Null(result.ErrorMessage);
    }

    [Fact]
    public void Reports_file_permission_failures_instead_of_succeeding_silently()
    {
        var result = PlayerSettingsSaveFeedback.Execute(() => throw new IOException("read-only"));

        Assert.False(result.Succeeded);
        Assert.Equal("设置保存失败，请检查目录权限。", result.ErrorMessage);
    }

    [Fact]
    public void Reports_unauthorized_access_failures_instead_of_succeeding_silently()
    {
        var result = PlayerSettingsSaveFeedback.Execute(() => throw new UnauthorizedAccessException("denied"));

        Assert.False(result.Succeeded);
        Assert.Equal("设置保存失败，请检查目录权限。", result.ErrorMessage);
    }

    [Fact]
    public void Does_not_hide_unexpected_save_failures()
    {
        Assert.Throws<InvalidOperationException>(() =>
            PlayerSettingsSaveFeedback.Execute(() => throw new InvalidOperationException("unexpected")));
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

    [Fact]
    public void Loads_defaults_when_settings_file_is_missing()
    {
        var path = Path.Combine(Path.GetTempPath(), $"home-ktv-missing-settings-{Guid.NewGuid():N}.json");

        var loaded = new PlayerSettingsStore(path).Load();

        Assert.Equal(string.Empty, loaded.ServerAddress);
        Assert.False(string.IsNullOrWhiteSpace(loaded.ClientToken));
        Assert.Equal(string.Empty, loaded.PlayerCredential);
        Assert.Equal("mpv.exe", loaded.MpvExecutablePath);
        Assert.Equal(0, loaded.DisplayIndex);
        Assert.NotNull(loaded.Window);
    }

    [Fact]
    public void Loads_defaults_when_the_settings_file_is_temporarily_unreadable()
    {
        var path = Path.Combine(Path.GetTempPath(), $"home-ktv-locked-settings-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(path, "{\"ServerAddress\":\"should-not-load\"}");
            using var exclusive = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.None);

            var loaded = new PlayerSettingsStore(path).Load();

            Assert.Equal(string.Empty, loaded.ServerAddress);
            Assert.False(string.IsNullOrWhiteSpace(loaded.ClientToken));
            Assert.NotNull(loaded.Window);
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }

    [Fact]
    public void Loads_defaults_when_json_is_null_or_malformed()
    {
        var path = Path.Combine(Path.GetTempPath(), $"home-ktv-invalid-settings-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(path, "null");
            var nullSettings = new PlayerSettingsStore(path).Load();

            Assert.Equal(string.Empty, nullSettings.ServerAddress);
            Assert.False(string.IsNullOrWhiteSpace(nullSettings.ClientToken));

            File.WriteAllText(path, "{not-json");
            var malformedSettings = new PlayerSettingsStore(path).Load();

            Assert.Equal(string.Empty, malformedSettings.ServerAddress);
            Assert.False(string.IsNullOrWhiteSpace(malformedSettings.ClientToken));
            Assert.NotEqual(nullSettings.ClientToken, malformedSettings.ClientToken);
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }

    [Fact]
    public void Replaces_blank_client_token_and_null_optional_settings_after_deserialization()
    {
        var path = Path.Combine(Path.GetTempPath(), $"home-ktv-null-settings-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(path,
                "{\"ClientToken\":\"  \",\"PlayerCredential\":null,\"DisplayId\":null,\"Window\":null}");

            var loaded = new PlayerSettingsStore(path).Load();

            Assert.StartsWith("windows-", loaded.ClientToken);
            Assert.Equal(40, loaded.ClientToken.Length);
            Assert.Equal(string.Empty, loaded.PlayerCredential);
            Assert.Equal(string.Empty, loaded.DisplayId);
            Assert.NotNull(loaded.Window);
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }
}
