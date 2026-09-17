using System.Text.Json;
using HomeKtv.Windows.Mpv;
using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.Tests;

public sealed class MpvIpcProtocolTests
{
    /// <summary>
    /// mpv 的对象型选项（af/vf）条目只接受 name / label / enabled / params 四个键。
    /// options/m_option.c 的 set_obj_settings_list 对未知键没有任何 else 分支，会被静默丢弃，
    /// 于是 lavfi 拿不到 graph —— 命令仍返回成功，但左右声道映射完全没生效，
    /// DUAL_CHANNEL 的原唱/伴唱在 Windows 上静默失效。
    /// </summary>
    [Theory]
    [InlineData(ChannelMapMode.LEFT_MONO, "pan=stereo|c0=c0|c1=c0")]
    [InlineData(ChannelMapMode.RIGHT_MONO, "pan=stereo|c0=c1|c1=c1")]
    public void Channel_filter_entry_uses_the_mpv_params_key(ChannelMapMode mode, string expectedGraph)
    {
        var json = MpvIpcProtocol.SerializeCommand(1, MpvCommands.SetChannelFilter(mode));

        using var document = JsonDocument.Parse(json);
        var command = document.RootElement.GetProperty("command");
        Assert.Equal("set_property", command[0].GetString());
        Assert.Equal("af", command[1].GetString());

        var entry = command[2][0];
        Assert.Equal("lavfi", entry.GetProperty("name").GetString());
        Assert.Equal(expectedGraph, entry.GetProperty("params").GetProperty("graph").GetString());
        Assert.False(entry.TryGetProperty("options", out _));
    }

    [Fact]
    public void Stereo_mode_clears_the_audio_filter_chain()
    {
        var json = MpvIpcProtocol.SerializeCommand(2, MpvCommands.SetChannelFilter(ChannelMapMode.STEREO));

        using var document = JsonDocument.Parse(json);
        var command = document.RootElement.GetProperty("command");
        Assert.Equal("af", command[1].GetString());
        Assert.Equal(JsonValueKind.Array, command[2].ValueKind);
        Assert.Equal(0, command[2].GetArrayLength());
    }

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
