using System.Text.Json;
using System.Text.Json.Serialization;

namespace HomeKtv.Windows.ServerConnection;

public sealed record DiscoveredServer(string Name, ServerEndpoint Endpoint);

public static class DiscoveryResponseParser
{
    public static DiscoveredServer? Parse(string payload, string sourceHost)
    {
        if (string.IsNullOrWhiteSpace(sourceHost)) return null;

        try
        {
            var response = JsonSerializer.Deserialize<DiscoveryResponse>(payload);
            if (response is null
                || !string.Equals(response.Service, "home-ktv", StringComparison.Ordinal)
                || response.ProtocolVersion != 1
                || response.Port is < 1 or > 65_535)
            {
                return null;
            }

            var host = sourceHost.Contains(':', StringComparison.Ordinal)
                && !sourceHost.StartsWith("[", StringComparison.Ordinal)
                ? $"[{sourceHost}]"
                : sourceHost;
            var endpoint = ServerEndpoint.Parse($"http://{host}:{response.Port}");
            var name = string.IsNullOrWhiteSpace(response.Name) ? sourceHost : response.Name.Trim();
            return new DiscoveredServer(name, endpoint);
        }
        catch (JsonException)
        {
            return null;
        }
        catch (FormatException)
        {
            return null;
        }
    }

    private sealed record DiscoveryResponse(
        [property: JsonPropertyName("service")] string? Service,
        [property: JsonPropertyName("protocolVersion")] int ProtocolVersion,
        [property: JsonPropertyName("name")] string? Name,
        [property: JsonPropertyName("port")] int Port);
}

public static class DiscoveryProtocol
{
    public const string Request = "HOME_KTV_DISCOVER_V1";
    public const int UdpPort = 18_888;
}
