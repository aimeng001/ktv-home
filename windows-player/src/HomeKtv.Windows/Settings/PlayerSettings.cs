using System.IO;
using System.Text.Json;

namespace HomeKtv.Windows.Settings;

public sealed class PlayerSettings
{
    public string ServerAddress { get; set; } = "";
    public string ClientToken { get; set; } = $"windows-{Guid.NewGuid():N}";
    public string MpvExecutablePath { get; set; } = "mpv.exe";
    public int DisplayIndex { get; set; }
}

public sealed class PlayerSettingsStore
{
    private readonly string path;

    public PlayerSettingsStore(string? path = null)
    {
        this.path = path ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "HomeKtv.Windows", "settings.json");
    }

    public PlayerSettings Load()
    {
        try
        {
            if (File.Exists(path))
            {
                var settings = JsonSerializer.Deserialize<PlayerSettings>(File.ReadAllText(path));
                if (settings is not null)
                {
                    if (string.IsNullOrWhiteSpace(settings.ClientToken))
                    {
                        settings.ClientToken = $"windows-{Guid.NewGuid():N}";
                    }
                    return settings;
                }
            }
        }
        catch (IOException) { }
        catch (JsonException) { }

        return new PlayerSettings();
    }

    public void Save(PlayerSettings settings)
    {
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(directory)) Directory.CreateDirectory(directory);
        var json = JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(path, json);
    }
}
