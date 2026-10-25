using System.Net;
using System.Text;
using System.Text.Json;
using HomeKtv.Windows.Protocol;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class SharedProtocolFixtureTests
{
    [Fact]
    public void Snapshot_chunk_fixture_preserves_queue_identity_position_and_track_layout()
    {
        var wire = ReadFixture("snapshot_chunk.json");
        var message = ProtocolJson.Deserialize<WsMessage>(wire);
        var chunk = message!.Payload!.Value.Deserialize<QueueSnapshotChunk>(ProtocolJson.Options);

        Assert.Equal("snapshot_chunk", message.Type);
        Assert.NotNull(chunk);
        var assembled = new SyncChunkAssembler().Accept(chunk!, Encoding.UTF8.GetByteCount(wire));

        Assert.NotNull(assembled);
        var snapshot = assembled!.Snapshot;
        Assert.Equal("sync_full", assembled.EventType);
        Assert.Equal(4101, snapshot.Playing!.QueueId);
        Assert.Equal(123456, snapshot.PositionMs);
        Assert.Equal(19, snapshot.SeekSequence);
        Assert.Equal(86, snapshot.StateRevision);
        Assert.Equal(AudioLayout.DUAL_TRACK, snapshot.AudioLayout.Layout);
        Assert.Equal(0, snapshot.AudioLayout.OriginalTrackIndex);
        Assert.Equal(1, snapshot.AudioLayout.AccompanimentTrackIndex);
        var playback = Assert.IsType<PlaybackDescriptor>(snapshot.Playback);
        Assert.Equal("READY", playback.Status);
        Assert.Equal(7101, playback.SourceFileId);
        Assert.Equal(4102, Assert.Single(snapshot.List).QueueId);
    }

    [Fact]
    public void Song_detail_fixture_keeps_not_ready_and_reversed_dual_channel_values()
    {
        var detail = ProtocolJson.Deserialize<SongDetail>(ReadFixture("song_detail_not_ready.json"));

        Assert.NotNull(detail);
        var source = Assert.Single(detail!.Files);
        Assert.Equal(7201, source.Id);
        Assert.False(source.Ready);
        Assert.Equal(AudioLayout.DUAL_CHANNEL, source.AudioLayout.Layout);
        Assert.Equal(AudioChannel.RIGHT, source.AudioLayout.OriginalChannel);
        Assert.Equal(AudioChannel.LEFT, source.AudioLayout.AccompanimentChannel);
    }

    [Fact]
    public async Task Song_not_ready_error_fixture_keeps_http_and_business_semantics()
    {
        var body = ReadFixture("business_error_song_not_ready.json");
        var handler = new RecordingHandler(_ => new HttpResponseMessage(HttpStatusCode.Conflict)
        {
            Content = new StringContent(body, Encoding.UTF8, "application/json"),
        });
        using var api = new HttpServerApi(
            new ServerEndpoint(new Uri("http://server:8080/")), handler: handler);

        var error = await Assert.ThrowsAsync<KtvApiException>(() => api.GetSongDetailAsync(72));

        Assert.Equal(409, error.StatusCode);
        Assert.Equal("SONG_NOT_READY", error.Code);
        Assert.Equal("歌曲尚未完成媒体探测", error.Message);
    }

    private static string ReadFixture(string name)
    {
        var starts = new[] { Environment.CurrentDirectory, AppContext.BaseDirectory };
        foreach (var start in starts)
        {
            var directory = new DirectoryInfo(start);
            while (directory is not null)
            {
                var candidate = Path.Combine(directory.FullName, "test-fixtures", "ktv-protocol", "v1", name);
                if (File.Exists(candidate)) return File.ReadAllText(candidate, Encoding.UTF8);
                directory = directory.Parent;
            }
        }
        throw new FileNotFoundException($"Shared protocol fixture not found: {name}");
    }

    private sealed class RecordingHandler(Func<HttpRequestMessage, HttpResponseMessage> responder)
        : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(responder(request));
    }
}
