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

    private sealed class RecordingHandler(
        Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) => Task.FromResult(responder(request));
    }
}
