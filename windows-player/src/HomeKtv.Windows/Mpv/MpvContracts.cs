using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace HomeKtv.Windows.Mpv;

public sealed record MpvNotification(string Name, JsonElement? Data);

public interface IMpvSession : IAsyncDisposable
{
    bool IsAlive { get; }

    event Action<MpvNotification>? NotificationReceived;

    event Action<Exception>? Disconnected;

    Task<JsonElement?> ExecuteAsync(
        IReadOnlyList<object?> command,
        CancellationToken cancellationToken = default);
}

public interface IMpvSessionFactory
{
    Task<IMpvSession> StartAsync(CancellationToken cancellationToken = default);
}

public interface IMpvDisplaySessionFactory : IMpvSessionFactory
{
    void SetScreenIndex(int screenIndex);
}

public class MpvConnectionException : IOException
{
    public MpvConnectionException(string message, Exception? innerException = null)
        : base(message, innerException) { }
}

public sealed class MpvCommandException : Exception
{
    public MpvCommandException(string message) : base(message) { }
}

public sealed class MpvCommandTimeoutException : MpvConnectionException
{
    public MpvCommandTimeoutException(string message) : base(message) { }
}

public sealed record MpvIpcMessage(
    [property: JsonPropertyName("request_id")] long? RequestId,
    [property: JsonPropertyName("error")] string? Error,
    [property: JsonPropertyName("data")] JsonElement? Data,
    [property: JsonPropertyName("event")] string? Event,
    [property: JsonPropertyName("event_data")] JsonElement? EventData);

public static class MpvIpcProtocol
{
    public static string SerializeCommand(long requestId, IReadOnlyList<object?> command) =>
        JsonSerializer.Serialize(new { command, request_id = requestId });

    public static bool TryParseMessage(string json, out MpvIpcMessage? message)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            long? requestId = root.TryGetProperty("request_id", out var request)
                && request.ValueKind == JsonValueKind.Number
                && request.TryGetInt64(out var id)
                ? id
                : null;
            var error = root.TryGetProperty("error", out var errorNode)
                ? errorNode.GetString()
                : null;
            var data = root.TryGetProperty("data", out var dataNode)
                ? dataNode.Clone()
                : (JsonElement?)null;
            var eventName = root.TryGetProperty("event", out var eventNode)
                ? eventNode.GetString()
                : null;
            var eventData = root.TryGetProperty("event_data", out var eventDataNode)
                ? eventDataNode.Clone()
                : (JsonElement?)null;
            message = new MpvIpcMessage(requestId, error, data, eventName, eventData);
            return true;
        }
        catch (JsonException)
        {
            message = null;
            return false;
        }
    }
}

public static class MpvCommands
{
    public static object?[] LoadFile(string url) => ["loadfile", url, "replace"];

    public static object?[] Play() => ["set_property", "pause", false];

    public static object?[] Pause() => ["set_property", "pause", true];

    public static object?[] Stop() => ["stop"];

    public static object?[] Seek(long positionMs) =>
        ["seek", Math.Max(0, positionMs) / 1000d, "absolute+exact"];

    public static object?[] SetVolume(int volume) =>
        ["set_property", "volume", Math.Clamp(volume, 0, 100)];

    public static object?[] SetMuted(bool muted) => ["set_property", "mute", muted];

    public static object?[] SetFullscreenScreen(int screenIndex) =>
        ["set_property", "fs-screen", Math.Max(0, screenIndex)];

    public static object?[] GetTrackList() => ["get_property", "track-list"];

    public static object?[] SetAudioTrack(int trackId) =>
        ["set_property", "aid", trackId];

    public static object?[] SetChannelFilter(Playback.ChannelMapMode mode) =>
        mode switch
        {
            Playback.ChannelMapMode.STEREO => ["set_property", "af", Array.Empty<object>()],
            // mpv 的对象型选项条目只认 name / label / enabled / params 四个键
            // （见 mpv options/m_option.c set_obj_settings_list）。用别的键会被静默丢弃：
            // lavfi 拿不到 graph，声道映射不生效，而命令仍返回成功。
            // 这里用显式字典而不是匿名类型，避免 params 作为 C# 关键字带来的歧义。
            Playback.ChannelMapMode.LEFT_MONO => ["set_property", "af", new object[]
            {
                ChannelFilterEntry("pan=stereo|c0=c0|c1=c0")
            }],
            Playback.ChannelMapMode.RIGHT_MONO => ["set_property", "af", new object[]
            {
                ChannelFilterEntry("pan=stereo|c0=c1|c1=c1")
            }],
            _ => throw new ArgumentOutOfRangeException(nameof(mode)),
        };

    /// <summary>
    /// 构造一个 mpv 对象型选项条目（af / vf 通用形状）。
    ///
    /// <p>字段名必须是 <c>name</c> 与 <c>params</c>：mpv 解析 <c>af</c> 属性时只识别
    /// name / label / enabled / params，其它键会被静默忽略（<c>set_obj_settings_list</c> 无 else 分支）。
    /// </summary>
    private static Dictionary<string, object> ChannelFilterEntry(string graph) =>
        new()
        {
            ["name"] = "lavfi",
            ["params"] = new Dictionary<string, string> { ["graph"] = graph },
        };
}

public static class MpvTrackMapper
{
    public static int? ResolveAudioTrackId(JsonElement? trackList, int audioRelativeIndex)
    {
        if (audioRelativeIndex < 0 || trackList is not { ValueKind: JsonValueKind.Array })
        {
            return null;
        }

        var audioIndex = 0;
        foreach (var track in trackList.Value.EnumerateArray())
        {
            if (!track.TryGetProperty("type", out var type)
                || !string.Equals(type.GetString(), "audio", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (audioIndex++ != audioRelativeIndex
                || !track.TryGetProperty("id", out var id)
                || !id.TryGetInt32(out var trackId))
            {
                continue;
            }

            return trackId;
        }

        return null;
    }
}
