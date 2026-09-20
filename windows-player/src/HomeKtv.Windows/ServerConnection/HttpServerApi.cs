using System.Net.Http;
using System.Net.Http.Json;
using System.Net;
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
        using var response = await http.GetAsync(
                new Uri(endpoint.ApiBaseUri, "health"), HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return null;
        var health = await ReadJsonAsync<ServerHealth>(response.Content, BoundedHttpContentReader.MaxJsonBytes,
            cancellationToken).ConfigureAwait(false);
        return health?.Service == "home-ktv" ? health : null;
    }

    public async Task<QueueSnapshot?> GetQueueAsync(CancellationToken cancellationToken = default)
    {
        using var response = await http.GetAsync(
                new Uri(endpoint.ApiBaseUri, "queue"), HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return null;
        return await ReadJsonAsync<QueueSnapshot>(response.Content, BoundedHttpContentReader.MaxJsonBytes,
            cancellationToken).ConfigureAwait(false);
    }

    public async Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default)
    {
        using var response = await http.GetAsync(
                new Uri(endpoint.ApiBaseUri, $"songs/{songId}"), HttpCompletionOption.ResponseHeadersRead,
                cancellationToken)
            .ConfigureAwait(false);
        // A missing song is the only detail lookup failure that proves the
        // queued media no longer exists.  Auth/server failures must retain
        // their status so the playback terminal can stop without skipping.
        if (response.StatusCode == HttpStatusCode.NotFound) return null;
        if (!response.IsSuccessStatusCode)
        {
            throw await ReadApiExceptionAsync(response, cancellationToken).ConfigureAwait(false);
        }
        if (!response.IsSuccessStatusCode) return null;
        return await ReadJsonAsync<SongDetail>(response.Content, BoundedHttpContentReader.MaxJsonBytes,
            cancellationToken).ConfigureAwait(false);
    }

    public async Task<QueueSnapshot?> SendControlAsync(
        string action,
        IReadOnlyDictionary<string, object?>? parameters = null,
        CancellationToken cancellationToken = default)
    {
        var command = new
        {
            action,
            @params = parameters ?? new Dictionary<string, object?>(),
            client_token = clientToken,
        };
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(endpoint.ApiBaseUri, "control"))
        {
            Content = JsonContent.Create(command, options: ProtocolJson.Options),
        };
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw await ReadApiExceptionAsync(response, cancellationToken).ConfigureAwait(false);
        }
        return await ReadJsonAsync<QueueSnapshot>(response.Content, BoundedHttpContentReader.MaxJsonBytes,
            cancellationToken).ConfigureAwait(false);
    }

    public string StreamUrl(long fileId) => new Uri(endpoint.ApiBaseUri, $"stream/{fileId}").ToString();
    public async Task<PlaybackDescriptor?> ResolvePlaybackAsync(
        long fileId,
        bool forceTranscode,
        CancellationToken cancellationToken = default)
    {
        var suffix = forceTranscode ? $"playback/resolve/{fileId}?forceTranscode=true" : $"playback/resolve/{fileId}";
        using var response = await http.GetAsync(
                new Uri(endpoint.ApiBaseUri, suffix), HttpCompletionOption.ResponseHeadersRead,
                cancellationToken)
            .ConfigureAwait(false);
        // A legacy server has no resolver endpoint. Keep the old native stream
        // path usable while upgraded servers return typed media failures.
        if (response.StatusCode == HttpStatusCode.NotFound) return null;
        if (!response.IsSuccessStatusCode)
        {
            throw await ReadApiExceptionAsync(response, cancellationToken).ConfigureAwait(false);
        }

        var descriptor = await ReadJsonAsync<PlaybackDescriptor>(
                response.Content, BoundedHttpContentReader.MaxJsonBytes, cancellationToken)
            .ConfigureAwait(false);
        if (descriptor is null || string.IsNullOrWhiteSpace(descriptor.StreamUrl)
            || Uri.IsWellFormedUriString(descriptor.StreamUrl, UriKind.Absolute))
        {
            return descriptor;
        }

        return descriptor with
        {
            StreamUrl = new Uri(endpoint.BaseUri, descriptor.StreamUrl.TrimStart('/')).ToString(),
        };
    }

    /** Downloads a server-local image such as artistAvatarUrl; remote absolute URLs are rejected. */
    public async Task<byte[]?> GetAssetBytesAsync(string? path, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(path) || Uri.IsWellFormedUriString(path, UriKind.Absolute)) return null;
        var uri = new Uri(endpoint.BaseUri, path.TrimStart('/'));
        using var response = await http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return null;
        return await BoundedHttpContentReader.ReadBytesAsync(
                response.Content, BoundedHttpContentReader.MaxAssetBytes, cancellationToken)
            .ConfigureAwait(false);
    }

    private static async Task<KtvApiException> ReadApiExceptionAsync(
        HttpResponseMessage response, CancellationToken cancellationToken)
    {
        var fallback = $"HTTP {(int)response.StatusCode}";
        var body = await BoundedHttpContentReader.ReadTextAsync(
                response.Content, BoundedHttpContentReader.MaxErrorBytes, cancellationToken)
            .ConfigureAwait(false);
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

    private static async Task<T?> ReadJsonAsync<T>(
        HttpContent content,
        long maxBytes,
        CancellationToken cancellationToken)
    {
        var bytes = await BoundedHttpContentReader.ReadBytesAsync(content, maxBytes, cancellationToken)
            .ConfigureAwait(false);
        return bytes is null ? default : JsonSerializer.Deserialize<T>(bytes, ProtocolJson.Options);
    }

    public void Dispose() => http.Dispose();
}
