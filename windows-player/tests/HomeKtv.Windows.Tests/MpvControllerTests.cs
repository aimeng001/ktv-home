using System.Text.Json;
using HomeKtv.Windows.Mpv;
using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.Tests;

public sealed class MpvControllerTests
{
    [Fact]
    public async Task Channel_mode_changes_filter_without_reload_or_seek()
    {
        var session = new FakeMpvSession();
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));

        await controller.LoadAsync("http://server/stream/10", 10);
        session.Commands.Clear();

        await controller.SetChannelModeAsync(ChannelMapMode.RIGHT_MONO);

        Assert.DoesNotContain(session.Commands, command => command[0]?.ToString() == "loadfile");
        Assert.DoesNotContain(session.Commands, command => command[0]?.ToString() == "seek");
        var filter = session.Commands.Single(command => command[0]?.ToString() == "af");
        Assert.Equal("set", filter[1]?.ToString());
        Assert.Contains("c0=c1|c1=c1", filter[2]?.ToString());
    }

    [Fact]
    public async Task Display_changes_move_fullscreen_output_without_reload_or_seek()
    {
        var session = new FakeMpvSession();
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));

        await controller.LoadAsync("http://server/stream/10", 10);
        session.Commands.Clear();

        await controller.SetDisplayAsync(2);

        Assert.DoesNotContain(session.Commands, command => command[0]?.ToString() == "loadfile");
        Assert.DoesNotContain(session.Commands, command => command[0]?.ToString() == "seek");
        var displayCommand = Assert.Single(session.Commands);
        Assert.Equal("set_property", displayCommand[0]?.ToString());
        Assert.Equal("fs-screen", displayCommand[1]?.ToString());
        Assert.Equal(2, displayCommand[2]);
    }

    [Fact]
    public async Task Display_selection_is_retained_before_mpv_session_starts()
    {
        var factory = new FakeMpvSessionFactory();
        var controller = new MpvProcessController(factory);

        await controller.SetDisplayAsync(2);

        Assert.Equal(2, factory.ScreenIndex);
        Assert.Equal(0, factory.StartCount);
    }

    [Fact]
    public async Task Dual_track_uses_mpv_audio_track_id_not_relative_index_as_id()
    {
        var session = new FakeMpvSession
        {
            GetPropertyResponse = Json("""
                [
                  {"type":"video","id":1},
                  {"type":"audio","id":7},
                  {"type":"audio","id":12}
                ]
                """)
        };
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));

        await controller.SetAudioTrackAsync(1);

        var setAid = session.Commands.Single(command => command[0]?.ToString() == "set_property");
        Assert.Equal("aid", setAid[1]?.ToString());
        Assert.Equal(12, setAid[2]);
    }

    [Fact]
    public async Task A_crashed_mpv_session_is_replaced_and_command_retried()
    {
        var first = new FakeMpvSession();
        var replacement = new FakeMpvSession();
        var factory = new FakeMpvSessionFactory(first, replacement);
        var controller = new MpvProcessController(factory);

        await controller.LoadAsync("http://server/stream/10", 10);
        first.FailNextCommand = true;

        await controller.PlayAsync();

        Assert.Equal(2, factory.StartCount);
        Assert.Contains(replacement.Commands, command => command[0]?.ToString() == "loadfile");
        Assert.Contains(replacement.Commands,
            command => command[0]?.ToString() == "set_property" && command[1]?.ToString() == "pause");
    }

    [Fact]
    public async Task Last_observed_position_is_restored_after_mpv_replacement()
    {
        var first = new FakeMpvSession { GetPropertyResponse = Json("123.4") };
        var replacement = new FakeMpvSession();
        var factory = new FakeMpvSessionFactory(first, replacement);
        var controller = new MpvProcessController(factory);

        await controller.LoadAsync("http://server/stream/10", 10);
        Assert.Equal(123_400L, await controller.GetPositionMsAsync());
        first.FailNextCommand = true;

        await controller.PlayAsync();

        Assert.Contains(replacement.Commands,
            command => command[0]?.ToString() == "seek"
                && command[1] is double seconds
                && Math.Abs(seconds - 123.4d) < 0.001d);
    }

    [Fact]
    public async Task Only_eof_notification_reports_playback_finished()
    {
        var session = new FakeMpvSession();
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));
        var finished = 0;
        controller.PlaybackFinished += () => finished++;

        await controller.LoadAsync("http://server/stream/10", 10);
        session.Notify("end-file", "{\"reason\":\"stop\"}");
        session.Notify("end-file", "{\"reason\":\"eof\"}");

        Assert.Equal(1, finished);
    }

    private static JsonElement Json(string value)
    {
        using var document = JsonDocument.Parse(value);
        return document.RootElement.Clone();
    }

    private sealed class FakeMpvSessionFactory(params FakeMpvSession[] sessions) : IMpvDisplaySessionFactory
    {
        private int next;

        public int StartCount => next;
        public int ScreenIndex { get; private set; }

        public void SetScreenIndex(int screenIndex) => ScreenIndex = screenIndex;

        public Task<IMpvSession> StartAsync(CancellationToken cancellationToken = default)
        {
            if (next >= sessions.Length)
            {
                throw new InvalidOperationException("No fake mpv session available.");
            }

            return Task.FromResult<IMpvSession>(sessions[next++]);
        }
    }

    private sealed class FakeMpvSession : IMpvSession
    {
        public List<IReadOnlyList<object?>> Commands { get; } = new();
        public JsonElement? GetPropertyResponse { get; init; }
        public bool FailNextCommand { get; set; }
        public bool IsAlive { get; private set; } = true;

        public event Action<MpvNotification>? NotificationReceived;
        public event Action<Exception>? Disconnected;

        public Task<JsonElement?> ExecuteAsync(
            IReadOnlyList<object?> command,
            CancellationToken cancellationToken = default)
        {
            if (FailNextCommand)
            {
                FailNextCommand = false;
                IsAlive = false;
                var error = new MpvConnectionException("fake mpv exited");
                Disconnected?.Invoke(error);
                throw error;
            }

            Commands.Add(command.ToArray());
            return Task.FromResult<JsonElement?>(
                command.Count > 1 && command[0]?.ToString() == "get_property"
                    ? GetPropertyResponse
                    : null);
        }

        public void Notify(string name, string data)
        {
            using var document = JsonDocument.Parse(data);
            NotificationReceived?.Invoke(new MpvNotification(name, document.RootElement.Clone()));
        }

        public ValueTask DisposeAsync()
        {
            IsAlive = false;
            return ValueTask.CompletedTask;
        }
    }
}
