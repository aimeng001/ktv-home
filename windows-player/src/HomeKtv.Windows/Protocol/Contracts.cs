using System.Text.Json;
using System.Text.Json.Serialization;

namespace HomeKtv.Windows.Protocol;

public enum AudioLayout
{
    NORMAL_STEREO,
    DUAL_TRACK,
    DUAL_CHANNEL,
}

public enum AudioChannel
{
    LEFT,
    RIGHT,
}

public sealed record AudioLayoutDto(
    [property: JsonPropertyName("layout")] AudioLayout Layout = AudioLayout.NORMAL_STEREO,
    [property: JsonPropertyName("originalTrackIndex")] int? OriginalTrackIndex = null,
    [property: JsonPropertyName("accompanimentTrackIndex")] int? AccompanimentTrackIndex = null,
    [property: JsonPropertyName("originalChannel")] AudioChannel OriginalChannel = AudioChannel.LEFT,
    [property: JsonPropertyName("accompanimentChannel")] AudioChannel AccompanimentChannel = AudioChannel.RIGHT);

public sealed record SongDto(
    [property: JsonPropertyName("id")] long Id,
    [property: JsonPropertyName("title")] string Title,
    [property: JsonPropertyName("artist")] string Artist,
    [property: JsonPropertyName("mediaType")] string MediaType = "AUDIO",
    [property: JsonPropertyName("hasVocalTrack")] bool HasVocalTrack = false,
    [property: JsonPropertyName("durationMs")] int DurationMs = 0,
    [property: JsonPropertyName("lyricType")] string LyricType = "none",
    [property: JsonPropertyName("coverUrl")] string? CoverUrl = null,
    [property: JsonPropertyName("playCount")] int PlayCount = 0,
    [property: JsonPropertyName("artistAvatarUrl")] string? ArtistAvatarUrl = null);

public sealed record NowPlaying(
    [property: JsonPropertyName("queueId")] long? QueueId,
    [property: JsonPropertyName("song")] SongDto? Song,
    [property: JsonPropertyName("orderedByNick")] string? OrderedByNick);

public sealed record QueueEntry(
    [property: JsonPropertyName("queueId")] long? QueueId,
    [property: JsonPropertyName("song")] SongDto? Song,
    [property: JsonPropertyName("orderedBy")] long? OrderedBy,
    [property: JsonPropertyName("orderedByNick")] string? OrderedByNick,
    [property: JsonPropertyName("status")] string Status = "waiting");

public sealed record QueueSnapshot(
    [property: JsonPropertyName("playing")] NowPlaying? Playing,
    [property: JsonPropertyName("list")] IReadOnlyList<QueueEntry> List,
    [property: JsonPropertyName("state")] string State,
    [property: JsonPropertyName("volume")] int Volume,
    [property: JsonPropertyName("muted")] bool Muted,
    [property: JsonPropertyName("vocalMode")] string VocalMode,
    [property: JsonPropertyName("audioLayout")] AudioLayoutDto AudioLayout,
    [property: JsonPropertyName("tvOnline")] bool TvOnline,
    [property: JsonPropertyName("connectedPhones")] long ConnectedPhones,
    [property: JsonPropertyName("positionMs")] long PositionMs = 0,
    [property: JsonPropertyName("seekSequence")] long SeekSequence = 0);

public sealed record FileSource(
    [property: JsonPropertyName("id")] long Id,
    [property: JsonPropertyName("format")] string Format,
    [property: JsonPropertyName("audioTracks")] int AudioTracks,
    [property: JsonPropertyName("vocalTrackIndex")] int? VocalTrackIndex,
    [property: JsonPropertyName("resolution")] string? Resolution,
    [property: JsonPropertyName("priority")] int Priority,
    [property: JsonPropertyName("audioLayout")] AudioLayoutDto AudioLayout);

public sealed record SongDetail(
    [property: JsonPropertyName("id")] long Id,
    [property: JsonPropertyName("title")] string Title,
    [property: JsonPropertyName("artist")] string Artist,
    [property: JsonPropertyName("mediaType")] string MediaType,
    [property: JsonPropertyName("hasVocalTrack")] bool HasVocalTrack,
    [property: JsonPropertyName("durationMs")] int DurationMs,
    [property: JsonPropertyName("lyricType")] string LyricType,
    [property: JsonPropertyName("coverUrl")] string? CoverUrl,
    [property: JsonPropertyName("lyricUrl")] string? LyricUrl,
    [property: JsonPropertyName("files")] IReadOnlyList<FileSource> Files,
    [property: JsonPropertyName("artistAvatarUrl")] string? ArtistAvatarUrl = null);

public sealed record WsMessage(
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("payload")] JsonElement? Payload);

public static class ProtocolJson
{
    public static readonly JsonSerializerOptions Options = CreateOptions();

    public static string Serialize<T>(T value) => JsonSerializer.Serialize(value, Options);

    public static T? Deserialize<T>(string json) => JsonSerializer.Deserialize<T>(json, Options);

    private static JsonSerializerOptions CreateOptions()
    {
        var options = new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true,
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            ReadCommentHandling = JsonCommentHandling.Skip,
            AllowTrailingCommas = true,
        };
        options.Converters.Add(new JsonStringEnumConverter());
        return options;
    }
}
