using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class HttpServerApiTests
{
    [Fact]
    public async Task Control_request_reuses_shared_action_and_params_shape()
    {
        HttpRequestMessage? request = null;
        var handler = new RecordingHandler(message =>
        {
            request = message;
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = JsonContent.Create(new { state = "playing" }),
            };
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), "windows-token", handler);

        await api.SendControlAsync("seek",
            new Dictionary<string, object?> { ["position_ms"] = 12_345L });

        Assert.Equal("POST", request!.Method.Method);
        Assert.Equal("http://server:8080/api/control", request.RequestUri!.ToString());
        using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
        Assert.Equal("seek", body.RootElement.GetProperty("action").GetString());
        Assert.Equal(12_345L,
            body.RootElement.GetProperty("params").GetProperty("position_ms").GetInt64());
        Assert.Equal("windows-token", body.RootElement.GetProperty("client_token").GetString());
    }

    [Fact]
    public void Stream_url_stays_on_the_shared_api_path()
    {
        using var api = new HttpServerApi(new ServerEndpoint(new Uri("http://server:8080/")));

        Assert.Equal("http://server:8080/api/stream/42", api.StreamUrl(42));
    }

    [Fact]
    public async Task Artist_avatar_asset_uses_the_configured_server_only()
    {
        HttpRequestMessage? request = null;
        var handler = new RecordingHandler(message =>
        {
            request = message;
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent(new byte[] { 1, 2, 3 }),
            };
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var bytes = await api.GetAssetBytesAsync("/api/artists/avatar?key=%E5%91%A8");

        Assert.Equal(new byte[] { 1, 2, 3 }, bytes);
        Assert.Equal(
            "http://server:8080/api/artists/avatar?key=%E5%91%A8",
            request!.RequestUri!.GetComponents(UriComponents.AbsoluteUri, UriFormat.UriEscaped));
    }

    [Fact]
    public async Task Artist_avatar_asset_rejects_absolute_external_urls()
    {
        var handler = new RecordingHandler(_ => throw new InvalidOperationException("network must not be used"));
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var bytes = await api.GetAssetBytesAsync("https://evil.example/avatar.jpg");

        Assert.Null(bytes);
    }

    [Theory]
    [InlineData(HttpStatusCode.Conflict, "TV_OFFLINE", "电视离线")]
    [InlineData(HttpStatusCode.InternalServerError, "SERVER_ERROR", "服务端异常")]
    public async Task Control_non_success_response_is_not_silently_treated_as_success(
        HttpStatusCode status, string code, string message)
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(status)
        {
            Content = JsonContent.Create(new { code, message }),
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), "windows-token", handler);

        var error = await Assert.ThrowsAsync<KtvApiException>(() => api.SendControlAsync("play"));

        Assert.Equal((int)status, error.StatusCode);
        Assert.Equal(code, error.Code);
        Assert.Equal(message, error.Message);
    }

    [Fact]
    public async Task Control_empty_error_body_still_surfaces_http_status()
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.BadGateway));
        using var api = new HttpServerApi(new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var error = await Assert.ThrowsAsync<KtvApiException>(() => api.SendControlAsync("pause"));

        Assert.Equal(502, error.StatusCode);
        Assert.Equal("HTTP 502", error.Message);
    }

    private sealed class RecordingHandler(
        Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) => Task.FromResult(responder(request));
    }
}
