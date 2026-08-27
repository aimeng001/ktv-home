using System.Text.Json;

namespace HomeKtv.Windows.ServerConnection;

public static class ServerMessageFactory
{
    public static string Ping(long? generation = null) => JsonSerializer.Serialize(new
    {
        type = "ping",
        payload = new { generation },
    });

    public static string Progress(long positionMs, long? queueId = null, long? generation = null) =>
        JsonSerializer.Serialize(new
        {
            type = "progress",
            payload = ProgressPayload(positionMs, queueId, generation),
        });

    public static string Finished(long queueId, long? generation = null) => JsonSerializer.Serialize(new
    {
        type = "finished",
        payload = new { queue_id = queueId, generation },
    });

    public static string PlayError(long queueId, long? fileId, string message, long? generation = null) => JsonSerializer.Serialize(new
    {
        type = "play_error",
        payload = new { queue_id = queueId, file_id = fileId, message, generation },
    });

    private static Dictionary<string, object?> ProgressPayload(long positionMs, long? queueId, long? generation)
    {
        var payload = new Dictionary<string, object?>
        {
            ["position_ms"] = Math.Max(0, positionMs),
        };
        if (queueId is not null) payload["queue_id"] = queueId.Value;
        if (generation is not null) payload["generation"] = generation.Value;
        return payload;
    }
}
