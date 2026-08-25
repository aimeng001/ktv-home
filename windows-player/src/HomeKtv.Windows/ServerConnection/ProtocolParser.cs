using System.Text.Json;
using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.ServerConnection;

public static class ProtocolParser
{
    public static QueueSnapshot? ParseSnapshot(string json) =>
        ProtocolJson.Deserialize<QueueSnapshot>(json);

    public static QueueSnapshot? ParseSnapshot(JsonElement payload)
    {
        return payload.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined
            ? null
            : payload.Deserialize<QueueSnapshot>(ProtocolJson.Options);
    }
}
