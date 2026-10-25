using HomeKtv.Windows.Protocol;
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class HttpServerApiTests
{
    [Fact]
    public async Task Health_check_reads_the_home_ktv_health_contract()
    {
        HttpRequestMessage? request = null;
        var handler = new RecordingHandler(message =>
        {
            request = message;
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = JsonContent.Create(new { status = "UP", service = "home-ktv" }),
            };
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var health = await api.CheckHealthAsync();

        Assert.Equal("GET", request!.Method.Method);
        Assert.Equal("http://server:8080/api/health", request.RequestUri!.ToString());
        Assert.Equal(new ServerHealth("UP", "home-ktv"), health);
    }

    [Theory]
    [InlineData(HttpStatusCode.ServiceUnavailable, "{\"status\":\"DOWN\",\"service\":\"home-ktv\"}")]
    [InlineData(HttpStatusCode.OK, "{\"status\":\"UP\",\"service\":\"another-service\"}")]
    [InlineData(HttpStatusCode.OK, "null")]
    public async Task Health_check_returns_null_for_unavailable_or_unrecognized_services(
        HttpStatusCode status, string body)
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(status)
        {
            Content = new StringContent(body),
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        Assert.Null(await api.CheckHealthAsync());
    }

    [Fact]
    public async Task Control_request_reuses_shared_action_and_params_shape()
    {
        HttpRequestMessage? request = null;
        string? requestBody = null;
        var handler = new RecordingHandler(message =>
        {
            request = message;
            requestBody = message.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
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
        using var body = JsonDocument.Parse(requestBody!);
        Assert.Equal("seek", body.RootElement.GetProperty("action").GetString());
        Assert.Equal(12_345L,
            body.RootElement.GetProperty("params").GetProperty("position_ms").GetInt64());
        Assert.Equal("windows-token", body.RootElement.GetProperty("client_token").GetString());
    }

    [Fact]
    public async Task Playback_resolver_normalizes_relative_variant_stream_url()
    {
        HttpRequestMessage? request = null;
        var handler = new RecordingHandler(message =>
        {
            request = message;
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = JsonContent.Create(new
                {
                    kind = "TRANSCODE",
                    status = "READY",
                    sourceFileId = 7L,
                    variantId = 88L,
                    streamUrl = "/api/playback/stream/88",
                    audioTracks = 2,
                    vocalTrackIndex = 1,
                    audioLayout = new { layout = "DUAL_TRACK", originalTrackIndex = 0, accompanimentTrackIndex = 1 },
                }, options: ProtocolJson.Options),
            };
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:54001/")), handler: handler);

        var descriptor = await api.ResolvePlaybackAsync(7, false);

        Assert.Equal("http://server:54001/api/playback/resolve/7", request!.RequestUri!.ToString());
        Assert.Equal("TRANSCODE", descriptor!.Kind);
        Assert.True(descriptor.IsReady);
        Assert.Equal("http://server:54001/api/playback/stream/88", descriptor.StreamUrl);
        Assert.Equal(1, descriptor.VocalTrackIndex);
    }

    [Fact]
    public async Task Playback_resolver_accepts_live_transcode_without_variant_identity()
    {
        HttpRequestMessage? request = null;
        var handler = new RecordingHandler(message =>
        {
            request = message;
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = JsonContent.Create(new
                {
                    kind = "LIVE_TRANSCODE",
                    status = "READY",
                    sourceFileId = 31L,
                    variantId = (long?)null,
                    streamUrl = "/api/stream/31?transcode=true&start=65.250",
                    audioTracks = 2,
                    audioLayout = new { layout = "DUAL_TRACK", accompanimentTrackIndex = 1 },
                }, options: ProtocolJson.Options),
            };
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:54001/")), handler: handler);

        var descriptor = await api.ResolvePlaybackAsync(31, forceTranscode: true);

        Assert.Equal("http://server:54001/api/playback/resolve/31?forceTranscode=true", request!.RequestUri!.ToString());
        Assert.Equal("LIVE_TRANSCODE", descriptor!.Kind);
        Assert.Null(descriptor.VariantId);
        Assert.True(descriptor.IsReady);
        Assert.Equal("http://server:54001/api/stream/31?transcode=true&start=65.250", descriptor.StreamUrl);
        Assert.Equal(2, descriptor.AudioTracks);
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
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    public async Task Artist_avatar_asset_rejects_empty_paths_without_http(string? path)
    {
        var handler = new RecordingHandler(_ => throw new InvalidOperationException("network must not be used"));
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        Assert.Null(await api.GetAssetBytesAsync(path));
    }

    [Fact]
    public async Task Artist_avatar_asset_returns_null_for_http_errors()
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.NotFound));
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        Assert.Null(await api.GetAssetBytesAsync("/api/artists/avatar"));
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

    [Fact]
    public async Task Song_detail_404_is_the_only_http_failure_treated_as_missing_media()
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.NotFound));
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var detail = await api.GetSongDetailAsync(42);

        Assert.Null(detail);
    }

    [Fact]
    public async Task Song_detail_success_deserializes_the_song_and_file_list()
    {
        HttpRequestMessage? request = null;
        var handler = new RecordingHandler(message =>
        {
            request = message;
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(
                    "{\"id\":42,\"title\":\"Test Song\",\"artist\":\"Artist\",\"mediaType\":\"VIDEO\",\"hasVocalTrack\":true,\"durationMs\":123000,\"lyricType\":\"LRC\",\"coverUrl\":null,\"lyricUrl\":null,\"files\":[]}"),
            };
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var detail = await api.GetSongDetailAsync(42);

        Assert.Equal("GET", request!.Method.Method);
        Assert.Equal("http://server:8080/api/songs/42", request.RequestUri!.ToString());
        Assert.NotNull(detail);
        Assert.Equal(42, detail!.Id);
        Assert.Equal("Test Song", detail.Title);
        Assert.Equal(123000, detail.DurationMs);
        Assert.Empty(detail.Files);
    }

    [Theory]
    [InlineData(HttpStatusCode.Unauthorized)]
    [InlineData(HttpStatusCode.Forbidden)]
    [InlineData(HttpStatusCode.InternalServerError)]
    [InlineData(HttpStatusCode.BadGateway)]
    public async Task Song_detail_auth_and_server_failures_retain_http_status(
        HttpStatusCode status)
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(status));
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var error = await Assert.ThrowsAsync<KtvApiException>(() => api.GetSongDetailAsync(42));

        Assert.Equal((int)status, error.StatusCode);
    }

    [Fact]
    public async Task Queue_http_error_returns_null()
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable));
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        Assert.Null(await api.GetQueueAsync());
    }

    [Theory]
    [InlineData("not-json", null, "HTTP 502")]
    [InlineData("{}", null, "HTTP 502")]
    [InlineData("{\"code\":\"BAD_REQUEST\"}", "BAD_REQUEST", "HTTP 502")]
    [InlineData("{\"message\":\"rejected\"}", null, "rejected")]
    public async Task Api_error_parser_uses_safe_fallbacks_for_partial_or_invalid_json(
        string body, string? expectedCode, string expectedMessage)
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.BadGateway)
        {
            Content = new StringContent(body),
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var error = await Assert.ThrowsAsync<KtvApiException>(() => api.SendControlAsync("play"));

        Assert.Equal(502, error.StatusCode);
        Assert.Equal(expectedCode, error.Code);
        Assert.Equal(expectedMessage, error.Message);
    }

    [Fact]
    public async Task Playback_resolver_returns_null_for_legacy_server_endpoint()
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.NotFound));
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        Assert.Null(await api.ResolvePlaybackAsync(42, forceTranscode: false));
    }

    [Fact]
    public async Task Playback_resolver_preserves_null_descriptor_and_blank_stream_url()
    {
        var responses = new Queue<string>(["null", "{\"kind\":\"NATIVE\",\"status\":\"READY\",\"streamUrl\":\" \"}"]);
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent(responses.Dequeue()),
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        Assert.Null(await api.ResolvePlaybackAsync(42, forceTranscode: false));
        var blank = await api.ResolvePlaybackAsync(42, forceTranscode: false);
        Assert.NotNull(blank);
        Assert.Equal(" ", blank!.StreamUrl);
    }

    [Fact]
    public async Task Playback_resolver_preserves_absolute_stream_urls()
    {
        const string streamUrl = "https://media.example.test/stream/42";
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = JsonContent.Create(new { kind = "NATIVE", status = "READY", streamUrl }),
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var descriptor = await api.ResolvePlaybackAsync(42, forceTranscode: false);

        Assert.Equal(streamUrl, descriptor!.StreamUrl);
    }

    [Fact]
    public async Task Playback_resolver_surfaces_server_failure_status()
    {
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.BadGateway));
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var error = await Assert.ThrowsAsync<KtvApiException>(
            () => api.ResolvePlaybackAsync(42, forceTranscode: false));

        Assert.Equal(502, error.StatusCode);
    }

    [Fact]
    public async Task Oversized_json_is_bounded_before_httpclient_buffers_the_response()
    {
        var content = new CountingContent((int)(BoundedHttpContentReader.MaxJsonBytes * 2));
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = content,
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var result = await api.GetQueueAsync();

        Assert.Null(result);
        Assert.InRange(content.BytesRead, 1, BoundedHttpContentReader.MaxJsonBytes + 64 * 1024);
    }

    private sealed class RecordingHandler(
        Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) => Task.FromResult(responder(request));
    }

    private sealed class CountingContent(int length) : HttpContent
    {
        private readonly byte[] data = new byte[length];
        private long bytesRead;

        public long BytesRead => Interlocked.Read(ref bytesRead);

        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) =>
            WriteAllAsync(stream);

        protected override Task<Stream> CreateContentReadStreamAsync() =>
            Task.FromResult<Stream>(new CountingStream(data, AddRead));

        protected override bool TryComputeLength(out long length)
        {
            length = -1;
            return false;
        }

        private async Task WriteAllAsync(Stream stream)
        {
            const int chunkSize = 64 * 1024;
            for (var offset = 0; offset < data.Length; offset += chunkSize)
            {
                var count = Math.Min(chunkSize, data.Length - offset);
                await stream.WriteAsync(data.AsMemory(offset, count));
                AddRead(count);
            }
        }

        private void AddRead(int count) => Interlocked.Add(ref bytesRead, count);
    }

    private sealed class CountingStream : Stream
    {
        private readonly MemoryStream inner;
        private readonly Action<int> onRead;

        public CountingStream(byte[] data, Action<int> onRead)
        {
            inner = new MemoryStream(data, writable: false);
            this.onRead = onRead;
        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            var read = inner.Read(buffer, offset, count);
            onRead(read);
            return read;
        }

        public override int Read(Span<byte> buffer)
        {
            var read = inner.Read(buffer);
            onRead(read);
            return read;
        }

        public override ValueTask<int> ReadAsync(
            Memory<byte> buffer,
            CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var count = inner.Read(buffer.Span);
            onRead(count);

            return ValueTask.FromResult(count);
        }

        public override bool CanRead => inner.CanRead;
        public override bool CanSeek => inner.CanSeek;
        public override bool CanWrite => false;
        public override long Length => inner.Length;
        public override long Position
        {
            get => inner.Position;
            set => inner.Position = value;
        }

        public override long Seek(long offset, SeekOrigin origin) => inner.Seek(offset, origin);
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Flush() => inner.Flush();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Write(ReadOnlySpan<byte> buffer) => throw new NotSupportedException();

        protected override void Dispose(bool disposing)
        {
            if (disposing) inner.Dispose();
            base.Dispose(disposing);
        }
    }
}
