using System.Text.Json;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class ServerProtocolTests
{
    [Theory]
    [InlineData("192.168.1.10", "http://192.168.1.10:8080/api/")]
    [InlineData("http://192.168.1.10:9090/m", "http://192.168.1.10:9090/api/")]
    [InlineData("https://[::1]:9443/anything", "https://[::1]:9443/api/")]
    public void Manual_address_is_normalized_to_the_server_api(string raw, string expectedApiBase)
    {
        var endpoint = ServerEndpoint.Parse(raw);

        Assert.Equal(expectedApiBase, endpoint.ApiBaseUri.ToString());
    }

    [Fact]
    public void Websocket_uri_reuses_tv_client_type_and_escapes_the_stable_token()
    {
        var endpoint = ServerEndpoint.Parse("http://192.168.1.10:8080");

        var uri = endpoint.WebSocketUri("win player/1");

        Assert.Equal("ws", uri.Scheme);
        Assert.Equal("/ws", uri.AbsolutePath);
        Assert.Contains("client_type=tv", uri.Query);
        Assert.Contains("client_token=win%20player%2F1", uri.Query);
    }

    [Fact]
    public void Discovery_response_requires_the_existing_home_ktv_protocol_marker()
    {
        var response = DiscoveryResponseParser.Parse(
            "{\"service\":\"home-ktv\",\"protocolVersion\":1,\"name\":\"客厅\",\"port\":9090}",
            "192.168.1.10");

        Assert.NotNull(response);
        Assert.Equal("客厅", response!.Name);
        Assert.Equal("http://192.168.1.10:9090/api/", response.Endpoint.ApiBaseUri.ToString());
    }

    [Theory]
    [InlineData("{\"service\":\"other\",\"protocolVersion\":1,\"port\":8080}")]
    [InlineData("{\"service\":\"home-ktv\",\"protocolVersion\":2,\"port\":8080}")]
    [InlineData("{\"service\":\"home-ktv\",\"protocolVersion\":1,\"port\":70000}")]
    public void Discovery_response_rejects_unknown_or_invalid_payloads(string payload)
    {
        Assert.Null(DiscoveryResponseParser.Parse(payload, "192.168.1.10"));
    }

    [Fact]
    public void Websocket_backoff_matches_the_existing_android_contract()
    {
        Assert.Equal(
            new[] { 1_000, 2_000, 5_000, 10_000, 10_000 },
            Enumerable.Range(0, 5).Select(ReconnectPolicy.DelayMilliseconds).ToArray());
    }

    [Fact]
    public void Upstream_finished_message_contains_queue_identity_for_retry_safety()
    {
        var json = ServerMessageFactory.Finished(42);
        using var document = JsonDocument.Parse(json);

        Assert.Equal("finished", document.RootElement.GetProperty("type").GetString());
        Assert.Equal(42, document.RootElement.GetProperty("payload").GetProperty("queue_id").GetInt64());
    }

    [Fact]
    public void Older_snapshot_without_position_fields_remains_readable()
    {
        var snapshot = ProtocolParser.ParseSnapshot(
            "{\"playing\":null,\"list\":[],\"state\":\"idle\",\"volume\":60,\"muted\":false,\"vocalMode\":\"accompaniment\",\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"},\"tvOnline\":false,\"connectedPhones\":0}");

        Assert.NotNull(snapshot);
        Assert.Equal(0, snapshot!.PositionMs);
        Assert.Equal(0, snapshot.SeekSequence);
    }
}
