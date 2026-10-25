using System.Diagnostics;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using HomeKtv.Windows.Mpv;

namespace HomeKtv.Windows.Tests;

public sealed class MpvJsonIpcSessionTests
{
    [Fact]
    public async Task ExecuteAsync_correlates_responses_and_delivers_notifications_while_ignoring_invalid_lines()
    {
        var connection = await ConnectAsync();
        await using var session = connection.Session;
        using var server = connection.Server;
        using var reader = connection.Reader;
        using var writer = connection.Writer;
        using var processMonitor = Process.GetProcessById(connection.Process.Id);
        var notification = new TaskCompletionSource<MpvNotification>(TaskCreationOptions.RunContinuationsAsynchronously);
        Action<MpvNotification> notificationHandler = value => notification.TrySetResult(value);
        session.NotificationReceived += notificationHandler;

        var execution = session.ExecuteAsync(["get_property", "volume"]);
        using var request = await ReadRequestAsync(reader);
        Assert.Equal("get_property", request.RootElement.GetProperty("command")[0].GetString());
        Assert.Equal("volume", request.RootElement.GetProperty("command")[1].GetString());
        var requestId = request.RootElement.GetProperty("request_id").GetInt64();

        await writer.WriteLineAsync("not json");
        await writer.WriteLineAsync("{\"event\":\"property-change\",\"event_data\":{\"name\":\"volume\",\"data\":75}}");
        await writer.WriteLineAsync($"{{\"request_id\":{requestId},\"error\":\"success\",\"data\":75}}");

        var result = await execution.WaitAsync(TimeSpan.FromSeconds(2));
        var receivedNotification = await notification.Task.WaitAsync(TimeSpan.FromSeconds(2));
        Assert.Equal(75, result?.GetInt32());
        Assert.Equal("property-change", receivedNotification.Name);
        Assert.Equal(75, receivedNotification.Data?.GetProperty("data").GetInt32());

        session.NotificationReceived -= notificationHandler;
        var nextExecution = session.ExecuteAsync(["get_property", "mute"]);
        using var nextRequest = await ReadRequestAsync(reader);
        var nextRequestId = nextRequest.RootElement.GetProperty("request_id").GetInt64();
        await writer.WriteLineAsync("{\"event\":\"property-change\",\"event_data\":{\"name\":\"mute\",\"data\":true}}");
        await writer.WriteLineAsync($"{{\"request_id\":{nextRequestId},\"error\":\"success\",\"data\":false}}");
        Assert.False((await nextExecution.WaitAsync(TimeSpan.FromSeconds(2)))?.GetBoolean());
        Assert.True(session.IsAlive);

        await session.DisposeAsync();
        Assert.True(processMonitor.HasExited);
        Assert.False(session.IsAlive);
        await Assert.ThrowsAsync<MpvConnectionException>(() => session.ExecuteAsync(["get_property", "volume"]));
    }

    [Fact]
    public async Task ExecuteAsync_rejects_a_duplicate_pending_id_without_replacing_the_original_request()
    {
        var connection = await ConnectAsync(new SequenceRequestIdGenerator(41, 41));
        await using var session = connection.Session;
        using var server = connection.Server;
        using var reader = connection.Reader;
        using var writer = connection.Writer;

        var firstExecution = session.ExecuteAsync(["get_property", "volume"]);
        using var firstRequest = await ReadRequestAsync(reader);
        Assert.Equal(41, firstRequest.RootElement.GetProperty("request_id").GetInt64());

        var duplicate = await Assert.ThrowsAsync<MpvConnectionException>(() =>
            session.ExecuteAsync(["get_property", "mute"]));
        Assert.Contains("Unable to register mpv IPC request 41", duplicate.Message);
        Assert.False(firstExecution.IsCompleted);

        await writer.WriteLineAsync("{\"request_id\":41,\"error\":\"success\",\"data\":75}");
        Assert.Equal(75, (await firstExecution.WaitAsync(TimeSpan.FromSeconds(2)))?.GetInt32());
        Assert.True(session.IsAlive);
    }

    [Theory]
    [InlineData("property unavailable", "property unavailable")]
    [InlineData(null, "unknown error")]
    public async Task ExecuteAsync_surfaces_mpv_command_errors_without_disconnect(
        string? errorMessage, string expectedMessage)
    {
        var connection = await ConnectAsync();
        await using var session = connection.Session;
        using var server = connection.Server;
        using var reader = connection.Reader;
        using var writer = connection.Writer;

        var execution = session.ExecuteAsync(["set_property", "invalid-property", 1]);
        using var request = await ReadRequestAsync(reader);
        var requestId = request.RootElement.GetProperty("request_id").GetInt64();
        var response = errorMessage is null
            ? $"{{\"request_id\":{requestId}}}"
            : $"{{\"request_id\":{requestId},\"error\":{JsonSerializer.Serialize(errorMessage)}}}";
        await writer.WriteLineAsync(response);

        var error = await Assert.ThrowsAsync<MpvCommandException>(() => execution);
        Assert.Contains(expectedMessage, error.Message);
        Assert.True(session.IsAlive);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Receive_loop_preserves_connection_failures_and_wraps_other_observer_failures(
        bool observerThrowsConnectionException)
    {
        var connection = await ConnectAsync();
        await using var session = connection.Session;
        using var server = connection.Server;
        using var reader = connection.Reader;
        using var writer = connection.Writer;
        Exception observerError = observerThrowsConnectionException
            ? new MpvConnectionException("synthetic observer connection failure")
            : new InvalidOperationException("synthetic observer failure");
        session.NotificationReceived += _ => throw observerError;
        var execution = session.ExecuteAsync(["get_property", "volume"]);
        using var request = await ReadRequestAsync(reader);

        await writer.WriteLineAsync("{\"event\":\"property-change\",\"event_data\":{\"name\":\"volume\"}}");

        var failure = await Assert.ThrowsAsync<MpvConnectionException>(
            () => execution.WaitAsync(TimeSpan.FromSeconds(2)));
        var receiveFailure = Assert.IsType<MpvConnectionException>(failure.InnerException);
        if (observerThrowsConnectionException)
        {
            Assert.Same(observerError, receiveFailure);
        }
        else
        {
            Assert.Same(observerError, receiveFailure.InnerException);
        }
    }

    [Fact]
    public async Task ExecuteAsync_cancellation_removes_the_pending_request_and_allows_later_commands()
    {
        var connection = await ConnectAsync();
        await using var session = connection.Session;
        using var server = connection.Server;
        using var reader = connection.Reader;
        using var writer = connection.Writer;
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));

        var cancelledExecution = session.ExecuteAsync(["get_property", "volume"], cancellation.Token);
        using var cancelledRequest = await ReadRequestAsync(reader);
        var cancelledId = cancelledRequest.RootElement.GetProperty("request_id").GetInt64();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => cancelledExecution);
        await writer.WriteLineAsync($"{{\"request_id\":{cancelledId},\"error\":\"success\",\"data\":10}}");

        var nextExecution = session.ExecuteAsync(["get_property", "mute"]);
        using var nextRequest = await ReadRequestAsync(reader);
        var nextId = nextRequest.RootElement.GetProperty("request_id").GetInt64();
        Assert.NotEqual(cancelledId, nextId);
        await writer.WriteLineAsync($"{{\"request_id\":{nextId},\"error\":\"success\",\"data\":false}}");

        Assert.False((await nextExecution.WaitAsync(TimeSpan.FromSeconds(2)))?.GetBoolean());
    }

    [Fact]
    public async Task DisposeAsync_fails_any_commands_that_are_still_pending()
    {
        var connection = await ConnectAsync();
        await using var session = connection.Session;
        using var server = connection.Server;
        using var reader = connection.Reader;
        using var writer = connection.Writer;

        var execution = session.ExecuteAsync(["get_property", "volume"]);
        using var request = await ReadRequestAsync(reader);
        await session.DisposeAsync();

        var error = await Assert.ThrowsAsync<MpvConnectionException>(
            () => execution.WaitAsync(TimeSpan.FromSeconds(2)));
        Assert.Contains("mpv IPC", error.Message, StringComparison.OrdinalIgnoreCase);
        Assert.False(session.IsAlive);
    }

    [Fact]
    public async Task ExecuteAsync_rechecks_connection_after_waiting_for_the_write_lock()
    {
        var connection = await ConnectAsync();
        await using var session = connection.Session;
        using var server = connection.Server;
        using var reader = connection.Reader;
        using var writer = connection.Writer;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));

        var largeWrite = session.ExecuteAsync(["set_property", "metadata", new string('x', 2_000_000)]);
        var firstByte = new byte[1];
        Assert.Equal(1, await server.ReadAsync(firstByte, timeout.Token));

        var waitingCommand = session.ExecuteAsync(["get_property", "volume"]);
        await Task.Delay(25, timeout.Token);
        await session.DisposeAsync().AsTask().WaitAsync(timeout.Token);

        var largeWriteFailure = await Record.ExceptionAsync(
            () => largeWrite.WaitAsync(timeout.Token));
        Assert.True(largeWriteFailure is MpvConnectionException or OperationCanceledException);
        Assert.False(timeout.IsCancellationRequested);
        await Assert.ThrowsAsync<MpvConnectionException>(
            () => waitingCommand.WaitAsync(timeout.Token));
        Assert.False(session.IsAlive);
    }

    [Fact]
    public async Task Pipe_eof_fails_pending_commands_and_notifies_disconnect()
    {
        var connection = await ConnectAsync();
        await using var session = connection.Session;
        var server = connection.Server;
        using var reader = connection.Reader;
        var writer = connection.Writer;
        var disconnected = new TaskCompletionSource<Exception>(TaskCreationOptions.RunContinuationsAsynchronously);
        session.Disconnected += error => disconnected.TrySetResult(error);

        var execution = session.ExecuteAsync(["get_property", "volume"]);
        using var request = await ReadRequestAsync(reader);
        writer.Dispose();
        server.Dispose();

        var failure = await disconnected.Task.WaitAsync(TimeSpan.FromSeconds(2));
        var commandError = await Assert.ThrowsAsync<MpvConnectionException>(() => execution);
        Assert.Same(failure, commandError.InnerException);
    }

    [Fact]
    public async Task DisposeAsync_handles_a_process_that_exited_before_the_pipe_closed()
    {
        var connection = await ConnectAsync();
        await using var session = connection.Session;
        using var server = connection.Server;
        using var reader = connection.Reader;
        using var writer = connection.Writer;
        using var processMonitor = Process.GetProcessById(connection.Process.Id);

        connection.Process.Kill(entireProcessTree: true);
        await connection.Process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(2));
        await session.DisposeAsync();

        Assert.True(processMonitor.HasExited);
        Assert.False(session.IsAlive);
    }

    private static async Task<JsonDocument> ReadRequestAsync(StreamReader reader)
    {
        var line = await reader.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(2));
        Assert.NotNull(line);
        return JsonDocument.Parse(line);
    }

    private static async Task<SessionConnection> ConnectAsync(
        MpvJsonIpcSession.IRequestIdGenerator? requestIds = null,
        int fakeProcessLifetimeSeconds = 60)
    {
        var pipeName = $"home-ktv-session-test-{Guid.NewGuid():N}";
        var server = new NamedPipeServerStream(pipeName, PipeDirection.InOut, 1,
            PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
        var client = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        var accept = server.WaitForConnectionAsync();
        await client.ConnectAsync(2_000);
        await accept.WaitAsync(TimeSpan.FromSeconds(2));

        var process = StartFakeMpvProcess(fakeProcessLifetimeSeconds);
        var session = requestIds is null
            ? new MpvJsonIpcSession(client, process)
            : new MpvJsonIpcSession(client, process, requestIds);
        var reader = new StreamReader(server, Encoding.UTF8, detectEncodingFromByteOrderMarks: false,
            bufferSize: 1024, leaveOpen: true);
        var writer = new StreamWriter(server, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false),
            bufferSize: 1024, leaveOpen: true) { AutoFlush = true };
        return new SessionConnection(server, reader, writer, process, session);
    }

    private static Process StartFakeMpvProcess(int lifetimeSeconds)
    {
        var startInfo = new ProcessStartInfo("powershell.exe")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        startInfo.ArgumentList.Add("-NoProfile");
        startInfo.ArgumentList.Add("-NonInteractive");
        startInfo.ArgumentList.Add("-Command");
        startInfo.ArgumentList.Add($"Start-Sleep -Seconds {lifetimeSeconds}");
        return Process.Start(startInfo) ?? throw new InvalidOperationException("Could not start the fake mpv process.");
    }

    private sealed class SequenceRequestIdGenerator(params long[] ids) : MpvJsonIpcSession.IRequestIdGenerator
    {
        private readonly Queue<long> remaining = new(ids);

        public long Next() => remaining.Dequeue();
    }

    private sealed record SessionConnection(
        NamedPipeServerStream Server,
        StreamReader Reader,
        StreamWriter Writer,
        Process Process,
        MpvJsonIpcSession Session);
}
