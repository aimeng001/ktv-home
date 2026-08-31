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
        if (!response.IsSuccessStatusCode)
        {
            throw await ReadApiExceptionAsync(response, cancellationToken).ConfigureAwait(false);
        }
        return await response.Content.ReadFromJsonAsync<QueueSnapshot>(ProtocolJson.Options, cancellationToken)
            .ConfigureAwait(false);
    }

    public string StreamUrl(long fileId) => new Uri(endpoint.ApiBaseUri, $"stream/{fileId}").ToString();

    /** Downloads a server-local image such as artistAvatarUrl; remote absolute URLs are rejected. */
    public async Task<byte[]?> GetAssetBytesAsync(string? path, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(path) || Uri.IsWellFormedUriString(path, UriKind.Absolute)) return null;
        var uri = new Uri(endpoint.BaseUri, path.TrimStart('/'));
        using var response = await http.GetAsync(uri, cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return null;
        return await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async Task<KtvApiException> ReadApiExceptionAsync(
        HttpResponseMessage response, CancellationToken cancellationToken)
    {
        var fallback = $"HTTP {(int)response.StatusCode}";
        var body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        if (string.IsNullOrWhiteSpace(body))
        {
            return new KtvApiException((int)response.StatusCode, null, fallback);
        }

        try
        {
            using var document = JsonDocument.Parse(body);
            var root = document.RootElement;
            var code = root.TryGetProperty("code", out var codeValue)
                ? codeValue.GetString()
                : null;
            var message = root.TryGetProperty("message", out var messageValue)
                ? messageValue.GetString()
                : null;
            return new KtvApiException((int)response.StatusCode, code,
                string.IsNullOrWhiteSpace(message) ? fallback : message);
        }
        catch (JsonException)
        {
            return new KtvApiException((int)response.StatusCode, null, fallback);
        }
    }

    public void Dispose() => http.Dispose();
}
