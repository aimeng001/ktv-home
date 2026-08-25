using System.Text.Json;

namespace HomeKtv.Windows.ServerConnection;

public static class ServerMessageFactory
{
    public static string Ping() => JsonSerializer.Serialize(new { type = "ping" });

    public static string Progress(long positionMs, long? queueId = null) =>
        JsonSerializer.Serialize(new
        {
            type = "progress",
            payload = ProgressPayload(positionMs, queueId),
        });

    public static string Finished(long queueId) => JsonSerializer.Serialize(new
    {
        type = "finished",
        payload = new { queue_id = queueId },
    });

    public static string PlayError(long queueId, long? fileId, string message) => JsonSerializer.Serialize(new
    {
        type = "play_error",
        payload = new { queue_id = queueId, file_id = fileId, message },
    });

    private static Dictionary<string, object?> ProgressPayload(long positionMs, long? queueId)
    {
        var payload = new Dictionary<string, object?>
        {
            ["position_ms"] = Math.Max(0, positionMs),
        };
        if (queueId is not null) payload["queue_id"] = queueId.Value;
        return payload;
    }
}
