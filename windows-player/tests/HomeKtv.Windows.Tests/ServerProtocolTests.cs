using System.Text.Json;
using HomeKtv.Windows.Protocol;
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
        Assert.Contains("protocol_version=2", uri.Query);
        Assert.Contains("platform=WINDOWS", uri.Query);
    }

    [Fact]
    public void Websocket_uri_never_contains_the_player_credential()
    {
        var endpoint = ServerEndpoint.Parse("https://192.168.1.10:8443");

        var uri = endpoint.WebSocketUri("windows-token");

        Assert.Equal("wss", uri.Scheme);
        Assert.DoesNotContain("player_credential", uri.Query);
    }

    [Fact]
    public void Player_credential_header_requires_https()
    {
        var secureEndpoint = ServerEndpoint.Parse("https://192.168.1.10:8443");
        var insecureEndpoint = ServerEndpoint.Parse("http://192.168.1.10:8080");

        Assert.Equal("secret/电视 1", secureEndpoint.PlayerCredentialHeader(" secret/电视 1 "));
        Assert.Throws<InvalidOperationException>(() =>
            insecureEndpoint.PlayerCredentialHeader("secret/电视 1"));
    }

    [Fact]
    public void Player_credential_header_omits_blank_values()
    {
        var endpoint = ServerEndpoint.Parse("https://192.168.1.10:8443");

        Assert.Null(endpoint.PlayerCredentialHeader(null));
        Assert.Null(endpoint.PlayerCredentialHeader(string.Empty));
        Assert.Null(endpoint.PlayerCredentialHeader(" \t "));
    }

    [Theory]
    [InlineData("")]
    [InlineData(" \t ")]
    [InlineData("ftp://192.168.1.10:21")]
    [InlineData("http://")]
    [InlineData("http://192.168.1.10:0")]
    [InlineData("https://192.168.1.10:65536")]
    [InlineData("http://[::1")]
    public void Manual_address_rejects_empty_unsupported_or_invalid_authorities(string raw)
    {
        Assert.Throws<FormatException>(() => ServerEndpoint.Parse(raw));
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

    [Fact]
    public void Discovery_response_uses_source_host_when_name_is_blank_and_brackets_ipv6_hosts()
    {
        var response = DiscoveryResponseParser.Parse(
            "{\"service\":\"home-ktv\",\"protocolVersion\":1,\"name\":\"  \",\"port\":9090}",
            "2001:db8::10");

        Assert.NotNull(response);
        Assert.Equal("2001:db8::10", response!.Name);
        Assert.Equal("http://[2001:db8::10]:9090/api/", response.Endpoint.ApiBaseUri.ToString());
    }

    [Fact]
    public void Discovery_response_rejects_empty_source_host_null_json_and_malformed_json()
    {
        const string validPayload = "{\"service\":\"home-ktv\",\"protocolVersion\":1,\"port\":9090}";

        Assert.Null(DiscoveryResponseParser.Parse(validPayload, string.Empty));
        Assert.Null(DiscoveryResponseParser.Parse(validPayload, " \t "));
        Assert.Null(DiscoveryResponseParser.Parse("null", "192.168.1.10"));
        Assert.Null(DiscoveryResponseParser.Parse("{invalid", "192.168.1.10"));
    }

    [Theory]
    [InlineData("{\"service\":\"other\",\"protocolVersion\":1,\"port\":8080}")]
    [InlineData("{\"service\":\"home-ktv\",\"protocolVersion\":2,\"port\":8080}")]
    [InlineData("{\"service\":\"home-ktv\",\"protocolVersion\":1,\"port\":0}")]
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
    public void Progress_message_clamps_negative_position_and_keeps_queue_identity()
    {
        using var document = JsonDocument.Parse(ServerMessageFactory.Progress(-1, 42, 7));
        var payload = document.RootElement.GetProperty("payload");

        Assert.Equal(0, payload.GetProperty("position_ms").GetInt64());
        Assert.Equal(42, payload.GetProperty("queue_id").GetInt64());
        Assert.Equal(7, payload.GetProperty("generation").GetInt64());
    }

    [Fact]
    public void Play_error_message_escapes_text_and_preserves_nullable_file_identity()
    {
        using var document = JsonDocument.Parse(
            ServerMessageFactory.PlayError(42, null, "bad \\\"source\\\""));
        var payload = document.RootElement.GetProperty("payload");

        Assert.Equal(42, payload.GetProperty("queue_id").GetInt64());
        Assert.Equal(JsonValueKind.Null, payload.GetProperty("file_id").ValueKind);
        Assert.Equal("bad \\\"source\\\"", payload.GetProperty("message").GetString());
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
        Assert.Equal(0, snapshot.StateRevision);
    }

    [Fact]
    public void Revisioned_snapshot_keeps_state_revision_through_json_protocol()
    {
        var snapshot = ProtocolParser.ParseSnapshot(
            "{\"playing\":null,\"list\":[],\"state\":\"idle\",\"volume\":60,\"muted\":false,\"vocalMode\":\"accompaniment\",\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"},\"tvOnline\":false,\"connectedPhones\":0,\"stateRevision\":42}");

        Assert.NotNull(snapshot);
        Assert.Equal(42, snapshot!.StateRevision);
    }

    [Fact]
    public void Json_element_snapshot_parser_returns_null_for_null_and_undefined_payloads()
    {
        using var document = JsonDocument.Parse("null");

        Assert.Null(ProtocolParser.ParseSnapshot(document.RootElement));
        Assert.Null(ProtocolParser.ParseSnapshot(default(JsonElement)));
    }

    [Fact]
    public void Json_element_snapshot_parser_deserializes_snapshot_payload()
    {
        using var document = JsonDocument.Parse(
            "{\"playing\":null,\"list\":[],\"state\":\"idle\",\"volume\":60,\"muted\":false,\"vocalMode\":\"accompaniment\",\"audioLayout\":{\"layout\":\"DUAL_CHANNEL\"},\"tvOnline\":false,\"connectedPhones\":0,\"positionMs\":321}");

        var snapshot = ProtocolParser.ParseSnapshot(document.RootElement);

        Assert.NotNull(snapshot);
        Assert.Equal(321, snapshot!.PositionMs);
        Assert.Equal(AudioLayout.DUAL_CHANNEL, snapshot.AudioLayout.Layout);
    }
}
