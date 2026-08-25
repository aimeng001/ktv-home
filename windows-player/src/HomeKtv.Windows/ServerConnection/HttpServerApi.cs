using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using HomeKtv.Windows.Playback;
using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.ServerConnection;

public sealed record ServerHealth(
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("service")] string Service);

public sealed class HttpServerApi : IPlaybackServerApi, IDisposable
{
    private readonly HttpClient http;
    private readonly ServerEndpoint endpoint;
    private readonly string? clientToken;

    public HttpServerApi(ServerEndpoint endpoint, string? clientToken = null, HttpMessageHandler? handler = null)
    {
        this.endpoint = endpoint;
        this.clientToken = clientToken;
        http = handler is null ? new HttpClient() : new HttpClient(handler);
        http.Timeout = TimeSpan.FromSeconds(8);
    }

    public async Task<ServerHealth?> CheckHealthAsync(CancellationToken cancellationToken = default)
    {
        using var response = await http.GetAsync(new Uri(endpoint.ApiBaseUri, "health"), cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return null;
        var health = await response.Content.ReadFromJsonAsync<ServerHealth>(ProtocolJson.Options, cancellationToken)
            .ConfigureAwait(false);
        return health?.Service == "home-ktv" ? health : null;
    }

    public async Task<QueueSnapshot?> GetQueueAsync(CancellationToken cancellationToken = default)
    {
        using var response = await http.GetAsync(new Uri(endpoint.ApiBaseUri, "queue"), cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return null;
        return await response.Content.ReadFromJsonAsync<QueueSnapshot>(ProtocolJson.Options, cancellationToken)
            .ConfigureAwait(false);
    }

    public async Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default)
    {
        using var response = await http.GetAsync(
                new Uri(endpoint.ApiBaseUri, $"songs/{songId}"), cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return null;
        return await response.Content.ReadFromJsonAsync<SongDetail>(ProtocolJson.Options, cancellationToken)
            .ConfigureAwait(false);
    }

    public async Task<QueueSnapshot?> SendControlAsync(
        string action,
        IReadOnlyDictionary<string, object?>? parameters = null,
        CancellationToken cancellationToken = default)
    {
        var request = new
        {
            action,
            @params = parameters ?? new Dictionary<string, object?>(),
            client_token = clientToken,
        };
        using var response = await http.PostAsJsonAsync(
                new Uri(endpoint.ApiBaseUri, "control"), request, ProtocolJson.Options, cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return null;
        return await response.Content.ReadFromJsonAsync<QueueSnapshot>(ProtocolJson.Options, cancellationToken)
            .ConfigureAwait(false);
    }

    public string StreamUrl(long fileId) => new Uri(endpoint.ApiBaseUri, $"stream/{fileId}").ToString();

    public void Dispose() => http.Dispose();
}
