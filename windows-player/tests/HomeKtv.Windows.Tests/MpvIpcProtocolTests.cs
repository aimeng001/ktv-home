using HomeKtv.Windows.Mpv;

namespace HomeKtv.Windows.Tests;

public sealed class MpvIpcProtocolTests
{
    [Fact]
    public void Command_serialization_uses_mpv_json_ipc_shape()
    {
        var json = MpvIpcProtocol.SerializeCommand(17, MpvCommands.Seek(12_345));

        using var document = System.Text.Json.JsonDocument.Parse(json);
        Assert.Equal(17, document.RootElement.GetProperty("request_id").GetInt64());
        var command = document.RootElement.GetProperty("command");
        Assert.Equal("seek", command[0].GetString());
        Assert.Equal(12.345, command[1].GetDouble());
        Assert.Equal("absolute+exact", command[2].GetString());
    }

    [Fact]
    public void Response_and_event_lines_are_parsed_without_leaking_document_lifetime()
    {
        Assert.True(MpvIpcProtocol.TryParseMessage(
            "{\"request_id\":3,\"error\":\"success\",\"data\":[{\"id\":8}]}",
            out var response));
        Assert.NotNull(response);
        Assert.Equal(3, response!.RequestId);
        Assert.Equal(8, response.Data!.Value[0].GetProperty("id").GetInt32());

        Assert.True(MpvIpcProtocol.TryParseMessage(
            "{\"event\":\"end-file\",\"event_data\":{\"reason\":\"eof\"}}",
            out var notification));
        Assert.Equal("end-file", notification!.Event);
        Assert.Equal("eof", notification.EventData!.Value.GetProperty("reason").GetString());
    }

    [Fact]
    public void Audio_track_mapper_ignores_video_tracks()
    {
        using var document = System.Text.Json.JsonDocument.Parse(
            "[{\"type\":\"video\",\"id\":1},{\"type\":\"audio\",\"id\":7}]");

        Assert.Equal(7, MpvTrackMapper.ResolveAudioTrackId(document.RootElement, 0));
        Assert.Null(MpvTrackMapper.ResolveAudioTrackId(document.RootElement, 1));
    }
}
