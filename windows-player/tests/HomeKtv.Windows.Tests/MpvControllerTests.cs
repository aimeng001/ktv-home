using System.Text.Json;
using HomeKtv.Windows.Mpv;
using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.Tests;

public sealed class MpvControllerTests
{
    [Fact]
    public async Task Load_pauses_before_replacing_media()
    {
        var session = new FakeMpvSession();
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));

        await controller.LoadAsync("http://server/stream/10", 10);

        var pauseIndex = session.Commands.ToList().FindIndex(
            command => command.SequenceEqual(MpvCommands.Pause()));
        var loadIndex = session.Commands.ToList().FindIndex(
            command => command.SequenceEqual(MpvCommands.LoadFile("http://server/stream/10")));

        Assert.True(pauseIndex >= 0);
        Assert.True(loadIndex > pauseIndex);
    }

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
        var filter = session.Commands.Single(command => command[0]?.ToString() == "set_property" && command[1]?.ToString() == "af");
        var serialized = JsonSerializer.Serialize(filter[2]);
        Assert.Contains("c0=c1|c1=c1", serialized);
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
    public async Task A_nonresponsive_command_is_bounded_and_cancellation_reaches_the_session()
    {
        var session = new CancellationAwareHungMpvSession();
        var controller = new MpvProcessController(
            new SingleMpvSessionFactory(session), TimeSpan.FromMilliseconds(25));

        await Assert.ThrowsAnyAsync<Exception>(() => controller.PlayAsync().WaitAsync(TimeSpan.FromSeconds(1)));
        Assert.True(session.CancellationObserved);
    }

    [Fact]
    public async Task Stop_if_running_stops_without_starting_a_replacement_session()
    {
        var session = new FakeMpvSession();
        var factory = new FakeMpvSessionFactory(session);
        var controller = new MpvProcessController(factory);

        await controller.LoadAsync("http://server/stream/10", 10);
        session.Commands.Clear();
        await controller.StopIfRunningAsync();

        Assert.Equal(1, factory.StartCount);
        Assert.Contains(session.Commands, command => command.SequenceEqual(MpvCommands.Stop()));
        Assert.Null(controller.CurrentFileId);
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
    public async Task Stop_projection_is_serialized_before_a_waiting_load()
    {
        var session = new GatedMpvSession();
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));

        await controller.LoadAsync("http://server/stream/10", 10);
        session.GateStop = true;
        var stopTask = controller.StopAsync();
        await session.StopStarted.Task;

        var loadTask = controller.LoadAsync("http://server/stream/20", 20);
        session.ReleaseStop.TrySetResult(true);

        await Task.WhenAll(stopTask, loadTask);

        Assert.Equal(20L, controller.CurrentFileId);
    }

    [Fact]
    public async Task Position_projection_is_serialized_before_a_waiting_load()
    {
        var first = new GatedMpvSession { GetPropertyResponse = Json("123.4") };
        var replacement = new FakeMpvSession();
        var factory = new FakeMpvSessionFactory(first, replacement);
        var controller = new MpvProcessController(factory);

        await controller.LoadAsync("http://server/stream/10", 10);
        first.GatePositionQuery = true;
        var positionTask = controller.GetPositionMsAsync();
        await first.PositionQueryStarted.Task;

        var loadTask = controller.LoadAsync("http://server/stream/20", 20);
        first.ReleasePositionQuery.TrySetResult(true);

        await Task.WhenAll(positionTask, loadTask);
        first.FailNextCommand = true;
        await controller.PlayAsync();

        Assert.DoesNotContain(replacement.Commands,
            command => command[0]?.ToString() == "seek");
    }

    [Fact]
    public async Task Only_eof_notification_reports_playback_finished()
    {
        var session = new FakeMpvSession();
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));
        var finished = new List<long>();
        controller.PlaybackFinished += fileId => finished.Add(fileId);

        await controller.LoadAsync("http://server/stream/10", 10);
        session.Notify("file-loaded", "{\"playlist_entry_id\":1}");
        session.Notify("end-file", "{\"reason\":\"stop\"}");
        session.Notify("end-file", "{\"reason\":\"eof\"}");
        session.Notify("end-file", "{\"reason\":\"eof\",\"playlist_entry_id\":1}");

        Assert.Equal(new long[] { 10 }, finished);
    }

    [Fact]
    public async Task Eof_for_an_old_playlist_entry_is_ignored_after_a_replacement_load()
    {
        var session = new FakeMpvSession();
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));
        var finished = new List<long>();
        controller.PlaybackFinished += fileId => finished.Add(fileId);

        await controller.LoadAsync("http://server/stream/10", 10);
        session.Notify("file-loaded", "{\"playlist_entry_id\":1}");

        await controller.LoadAsync("http://server/stream/20", 20);
        session.Notify("file-loaded", "{\"playlist_entry_id\":2}");
        session.Notify("end-file", "{\"reason\":\"eof\",\"playlist_entry_id\":1}");
        session.Notify("end-file", "{\"reason\":\"eof\",\"playlist_entry_id\":2}");

        Assert.Equal(new long[] { 20 }, finished);
    }

    /// <summary>
    /// mpv 在 <c>loadfile</c> 命令成功之后才发现流拉不下来，此时发出的是
    /// <c>end-file</c> + <c>reason="error"</c>。原先只处理 <c>reason="eof"</c>，
    /// 于是这类失败被静默丢弃：不上报 finished、不触发 SessionFaulted、
    /// PlaybackTerminal 的唯一 play_error 出口永远不会被走到 —— 画面黑屏卡死、
    /// 不跳歌、不报错、不重连，队列永久停在这一首。
    ///
    /// <p>mpv 的 reason 取值只有 eof / stop / quit / error / redirect / unknown
    /// （见 DOCS/man/input.rst 的 end-file 一节），load-fail 并不是一个 reason。
    /// </summary>
    [Fact]
    public async Task End_file_with_error_reason_reports_playback_failure()
    {
        var session = new FakeMpvSession();
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));
        var failed = new List<long>();
        controller.PlaybackFailed += fileId => failed.Add(fileId);

        await controller.LoadAsync("http://server/stream/10", 10);
        session.Notify("start-file", "{\"playlist_entry_id\":1}");
        session.Notify("end-file",
            "{\"reason\":\"error\",\"playlist_entry_id\":1,\"file_error\":\"loading failed\"}");

        Assert.Equal(new long[] { 10 }, failed);
    }

    /// <summary>stop / quit / redirect 都是预期内的正常终止，不得被当成播放失败上报。</summary>
    [Fact]
    public async Task End_file_with_normal_reasons_never_reports_failure()
    {
        var session = new FakeMpvSession();
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));
        var failed = new List<long>();
        controller.PlaybackFailed += fileId => failed.Add(fileId);

        await controller.LoadAsync("http://server/stream/10", 10);
        session.Notify("start-file", "{\"playlist_entry_id\":1}");
        session.Notify("end-file", "{\"reason\":\"stop\",\"playlist_entry_id\":1}");
        session.Notify("end-file", "{\"reason\":\"quit\",\"playlist_entry_id\":1}");
        session.Notify("end-file", "{\"reason\":\"redirect\",\"playlist_entry_id\":1}");

        Assert.Empty(failed);
    }

    /// <summary>上一次装载迟到的失败事件不能算到当前媒体头上。</summary>
    [Fact]
    public async Task Failure_for_an_old_playlist_entry_is_ignored_after_a_replacement_load()
    {
        var session = new FakeMpvSession();
        var controller = new MpvProcessController(new FakeMpvSessionFactory(session));
        var failed = new List<long>();
        controller.PlaybackFailed += fileId => failed.Add(fileId);

        await controller.LoadAsync("http://server/stream/10", 10);
        session.Notify("start-file", "{\"playlist_entry_id\":1}");

        await controller.LoadAsync("http://server/stream/20", 20);
        session.Notify("start-file", "{\"playlist_entry_id\":2}");

        session.Notify("end-file", "{\"reason\":\"error\",\"playlist_entry_id\":1}");
        session.Notify("end-file", "{\"reason\":\"error\",\"playlist_entry_id\":2}");

        Assert.Equal(new long[] { 20 }, failed);
    }

    private static JsonElement Json(string value)
    {
        using var document = JsonDocument.Parse(value);
        return document.RootElement.Clone();
    }

    private sealed class FakeMpvSessionFactory(params IMpvSession[] sessions) : IMpvDisplaySessionFactory
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

    private sealed class SingleMpvSessionFactory(IMpvSession session) : IMpvSessionFactory
    {
        public Task<IMpvSession> StartAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult(session);
    }

    private sealed class CancellationAwareHungMpvSession : IMpvSession
    {
        public bool CancellationObserved { get; private set; }
        public bool IsAlive => true;
        public event Action<MpvNotification>? NotificationReceived;
        public event Action<Exception>? Disconnected;

        public async Task<JsonElement?> ExecuteAsync(
            IReadOnlyList<object?> command, CancellationToken cancellationToken = default)
        {
            try
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                CancellationObserved = true;
                throw;
            }

            return null;
        }

        public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    }

    private sealed class GatedMpvSession : IMpvSession
    {
        public List<IReadOnlyList<object?>> Commands { get; } = new();
        public JsonElement? GetPropertyResponse { get; init; }
        public bool GateStop { get; set; }
        public bool GatePositionQuery { get; set; }
        public TaskCompletionSource<bool> StopStarted { get; } = NewSignal();
        public TaskCompletionSource<bool> ReleaseStop { get; } = NewSignal();
        public TaskCompletionSource<bool> PositionQueryStarted { get; } = NewSignal();
        public TaskCompletionSource<bool> ReleasePositionQuery { get; } = NewSignal();
        public bool FailNextCommand { get; set; }
        public bool IsAlive { get; private set; } = true;

        public event Action<MpvNotification>? NotificationReceived;
        public event Action<Exception>? Disconnected;

        public async Task<JsonElement?> ExecuteAsync(
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
            if (command[0]?.ToString() == "stop" && GateStop)
            {
                StopStarted.TrySetResult(true);
                await ReleaseStop.Task.WaitAsync(cancellationToken);
            }

            if (command.Count > 1 && command[0]?.ToString() == "get_property")
            {
                if (GatePositionQuery)
                {
                    PositionQueryStarted.TrySetResult(true);
                    await ReleasePositionQuery.Task.WaitAsync(cancellationToken);
                }

                return GetPropertyResponse;
            }

            return null;
        }

        public ValueTask DisposeAsync()
        {
            IsAlive = false;
            return ValueTask.CompletedTask;
        }

        public void Notify(string name, string data)
        {
            using var document = JsonDocument.Parse(data);
            NotificationReceived?.Invoke(new MpvNotification(name, document.RootElement.Clone()));
        }

        private static TaskCompletionSource<bool> NewSignal() =>
            new(TaskCreationOptions.RunContinuationsAsynchronously);
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
