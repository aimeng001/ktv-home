using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using HomeKtv.Windows.Playback;
using HomeKtv.Windows.Protocol;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class KtvWebSocketClientTests
{
    [Fact]
    public async Task Heartbeat_tracks_active_lease_and_replays_then_acknowledges_finished_reports()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var endpoint = new ServerEndpoint(new Uri(
            $"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/"));
        var finishedPath = Path.Combine(AppContext.BaseDirectory, $"ktv-finished-{Guid.NewGuid():N}.json");
        await using var client = new KtvWebSocketClient(
            endpoint,
            "heartbeat-client",
            heartbeatInterval: TimeSpan.FromMilliseconds(100),
            pendingFinishedPath: finishedPath);
        var standbyAssigned = new TaskCompletionSource<PlayerAssignment>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        client.PlayerAssignmentReceived += assignment =>
        {
            if (assignment.Role == "STANDBY") standbyAssigned.TrySetResult(assignment);
        };
        using var runCancellation = new CancellationTokenSource();
        Task? runTask = null;
        (WebSocket Socket, string RequestLine, TcpClient Client)? session = null;

        try
        {
            Assert.Equal(PendingPlaybackReportEnqueueResult.Added, await client.SendFinishedAsync(61));
            var accepted = AcceptWebSocketAsync(listener, timeout.Token);
            runTask = client.RunAsync(runCancellation.Token);
            session = await accepted.WaitAsync(timeout.Token);

            using (var inactiveHeartbeat = JsonDocument.Parse(await ReceiveTextAsync(session.Value.Socket, timeout.Token)))
            {
                Assert.Equal("ping", inactiveHeartbeat.RootElement.GetProperty("type").GetString());
                Assert.Equal(JsonValueKind.Null,
                    inactiveHeartbeat.RootElement.GetProperty("payload").GetProperty("generation").ValueKind);
            }

            await SendTextAsync(session.Value.Socket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":7,\"lease_ms\":45000}}",
                timeout.Token);
            using (var finished = JsonDocument.Parse(await ReceiveTextAsync(session.Value.Socket, timeout.Token)))
            {
                Assert.Equal("finished", finished.RootElement.GetProperty("type").GetString());
                Assert.Equal(61, finished.RootElement.GetProperty("payload").GetProperty("queue_id").GetInt64());
                Assert.Equal(7, finished.RootElement.GetProperty("payload").GetProperty("generation").GetInt64());
            }

            using (var heartbeatRetry = JsonDocument.Parse(await ReceiveTextAsync(session.Value.Socket, timeout.Token)))
            {
                Assert.Equal("finished", heartbeatRetry.RootElement.GetProperty("type").GetString());
                Assert.Equal(61, heartbeatRetry.RootElement.GetProperty("payload").GetProperty("queue_id").GetInt64());
                Assert.Equal(7, heartbeatRetry.RootElement.GetProperty("payload").GetProperty("generation").GetInt64());
            }
            using (var activeHeartbeat = JsonDocument.Parse(await ReceiveTextAsync(session.Value.Socket, timeout.Token)))
            {
                Assert.Equal("ping", activeHeartbeat.RootElement.GetProperty("type").GetString());
                Assert.Equal(7, activeHeartbeat.RootElement.GetProperty("payload").GetProperty("generation").GetInt64());
            }

            await SendTextAsync(session.Value.Socket,
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":1200}}",
                timeout.Token);

            await SendTextAsync(session.Value.Socket,
                "{\"type\":\"playback_report_ack\",\"payload\":{\"queue_id\":61,\"status\":\"APPLIED\",\"report_type\":\"finished\"}}",
                timeout.Token);
            await SendTextAsync(session.Value.Socket,
                "{\"type\":\"pong\",\"payload\":{\"role\":\"STANDBY\",\"generation\":8,\"lease_ms\":45000}}",
                timeout.Token);
            var standby = await standbyAssigned.Task.WaitAsync(timeout.Token);
            Assert.Equal(new PlayerAssignment("STANDBY", 8, 45_000), standby);
            Assert.Equal(PendingPlaybackReportEnqueueResult.Added, await client.SendFinishedAsync(61));

            using (var standbyHeartbeat = JsonDocument.Parse(await ReceiveTextAsync(session.Value.Socket, timeout.Token)))
            {
                Assert.Equal("ping", standbyHeartbeat.RootElement.GetProperty("type").GetString());
                Assert.Equal(JsonValueKind.Null,
                    standbyHeartbeat.RootElement.GetProperty("payload").GetProperty("generation").ValueKind);
            }
        }
        finally
        {
            if (session is { } connected)
            {
                try { await CloseServerSocketAsync(connected.Socket, timeout.Token); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException) { }
                connected.Socket.Dispose();
                connected.Client.Dispose();
            }

            runCancellation.Cancel();
            if (runTask is not null)
            {
                try { await runTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
            }
            listener.Stop();
            if (File.Exists(finishedPath)) File.Delete(finishedPath);
            if (File.Exists(finishedPath + ".tmp")) File.Delete(finishedPath + ".tmp");
        }
    }

    [Fact]
    public async Task Oversized_reliable_error_is_rejected_before_it_enters_the_outbox()
    {
        var endpoint = new ServerEndpoint(new Uri("http://127.0.0.1:8080/"));
        var outboxPath = Path.Combine(AppContext.BaseDirectory, $"ktv-ws-limit-{Guid.NewGuid():N}.json");
        await using var client = new KtvWebSocketClient(
            endpoint, "limit-client", reliableOutboxPath: outboxPath);
        (string Key, string Reason)? rejection = null;
        client.ReliableMessageRejected += (key, reason) => rejection = (key, reason);

        var result = await client.SendPlayErrorAsync(
            62, 9, new string('x', Utf8ByteBudget.MaxMessageBytes));

        Assert.Equal(ReliableSendResult.Rejected, result);
        Assert.Equal(("play_error:62", "message exceeds websocket byte budget"), rejection);
        Assert.False(File.Exists(outboxPath));

        await using var clientWithoutRejectionListener = new KtvWebSocketClient(endpoint, "silent-limit-client");
        Assert.Equal(ReliableSendResult.Rejected, await clientWithoutRejectionListener.SendPlayErrorAsync(
            63, 9, new string('x', Utf8ByteBudget.MaxMessageBytes)));
    }

    [Fact]
    public async Task Reliable_errors_return_already_queued_for_duplicate_offline_keys()
    {
        var endpoint = new ServerEndpoint(new Uri("http://127.0.0.1:8080/"));
        var outboxPath = Path.Combine(AppContext.BaseDirectory, $"ktv-ws-dedupe-{Guid.NewGuid():N}.json");

        try
        {
            await using var client = new KtvWebSocketClient(
                endpoint, "dedupe-client", reliableOutboxPath: outboxPath);
            Assert.Equal(ReliableSendResult.Queued,
                await client.SendPlayErrorAsync(1, null, "offline"));
            Assert.Equal(ReliableSendResult.AlreadyQueued,
                await client.SendPlayErrorAsync(1, null, "offline"));

            var persisted = JsonSerializer.Deserialize<List<ReliableMessage>>(
                await File.ReadAllTextAsync(outboxPath));
            Assert.NotNull(persisted);
            Assert.Equal("play_error:1", Assert.Single(persisted).Key);
        }
        finally
        {
            if (File.Exists(outboxPath)) File.Delete(outboxPath);
            if (File.Exists(outboxPath + ".tmp")) File.Delete(outboxPath + ".tmp");
        }
    }

    [Fact]
    public async Task Reliable_errors_report_rejection_when_outbox_persistence_fails()
    {
        var endpoint = new ServerEndpoint(new Uri("http://127.0.0.1:8080/"));
        var blockingFile = Path.Combine(AppContext.BaseDirectory, $"ktv-ws-outbox-blocker-{Guid.NewGuid():N}");
        var outboxPath = Path.Combine(blockingFile, "outbox.json");
        await File.WriteAllTextAsync(blockingFile, "synthetic file blocks the outbox directory");

        try
        {
            await using var client = new KtvWebSocketClient(
                endpoint, "persistence-failure-client", reliableOutboxPath: outboxPath);
            (string Key, string Reason)? rejection = null;
            client.ReliableMessageRejected += (key, reason) => rejection = (key, reason);

            Assert.Equal(ReliableSendResult.Rejected,
                await client.SendPlayErrorAsync(2, null, "offline"));
            Assert.Equal(
                ("play_error:2", "reliable outbox is full or persistence failed"),
                rejection);
            Assert.False(File.Exists(outboxPath));
        }
        finally
        {
            if (File.Exists(outboxPath)) File.Delete(outboxPath);
            if (File.Exists(outboxPath + ".tmp")) File.Delete(outboxPath + ".tmp");
            if (File.Exists(blockingFile)) File.Delete(blockingFile);
        }
    }

    [Fact]
    public async Task Reliable_send_failure_keeps_the_message_queued_for_the_next_lease_flush()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var endpoint = new ServerEndpoint(new Uri($"http://127.0.0.1:{port}/"));
        var outboxPath = Path.Combine(AppContext.BaseDirectory, $"ktv-ws-send-failure-{Guid.NewGuid():N}.json");
        var failNextReliableSend = 1;
        var failNextSendUnexpectedly = 0;
        var firstAssignment = new TaskCompletionSource<PlayerAssignment>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var acknowledgementProcessed = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously);

        async Task SendSocketBytesAsync(ClientWebSocket active, byte[] bytes, CancellationToken cancellationToken)
        {
            var text = Encoding.UTF8.GetString(bytes);
            if (text.Contains("\"type\":\"play_error\"", StringComparison.Ordinal)
                && Interlocked.Exchange(ref failNextReliableSend, 0) == 1)
            {
                throw new IOException("synthetic reliable send failure");
            }
            if (text.Contains("\"type\":\"play_error\"", StringComparison.Ordinal)
                && Interlocked.Exchange(ref failNextSendUnexpectedly, 0) == 1)
            {
                throw new InvalidOperationException("synthetic unexpected send failure");
            }

            await active.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken);
        }

        await using var client = new KtvWebSocketClient(
            endpoint,
            "send-failure-client",
            TimeSpan.FromHours(1),
            reliableOutboxPath: outboxPath,
            sendMessageAsync: SendSocketBytesAsync);
        client.PlayerAssignmentReceived += assignment => firstAssignment.TrySetResult(assignment);
        client.ProgressReceived += position =>
        {
            if (position == 999) acknowledgementProcessed.TrySetResult();
        };

        using var runCancellation = new CancellationTokenSource();
        TcpClient? serverTcpClient = null;
        WebSocket? serverSocket = null;
        Task? runTask = null;
        try
        {
            var accepted = AcceptWebSocketAsync(listener, timeout.Token);
            runTask = client.RunAsync(runCancellation.Token);
            var session = await accepted.WaitAsync(timeout.Token);
            serverTcpClient = session.Client;
            serverSocket = session.Socket;

            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":10,\"lease_ms\":45000}}",
                timeout.Token);
            Assert.Equal(new PlayerAssignment("ACTIVE", 10, 45_000),
                await firstAssignment.Task.WaitAsync(timeout.Token));

            Assert.Equal(ReliableSendResult.Queued,
                await client.SendPlayErrorAsync(84, 9, "synthetic failure", cancellationToken: timeout.Token));
            Assert.Equal(0, Volatile.Read(ref failNextReliableSend));

            Interlocked.Exchange(ref failNextReliableSend, 1);
            Assert.Equal(ReliableSendResult.AlreadyQueued,
                await client.SendPlayErrorAsync(84, 9, "synthetic failure", cancellationToken: timeout.Token));
            Assert.Equal(0, Volatile.Read(ref failNextReliableSend));

            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":11,\"lease_ms\":45000}}",
                timeout.Token);
            using var replay = JsonDocument.Parse(await ReceiveTextAsync(serverSocket, timeout.Token));
            Assert.Equal("play_error", replay.RootElement.GetProperty("type").GetString());
            Assert.Equal(84, replay.RootElement.GetProperty("payload").GetProperty("queue_id").GetInt64());
            Assert.Equal(11, replay.RootElement.GetProperty("payload").GetProperty("generation").GetInt64());

            await SendTextAsync(serverSocket,
                "{\"type\":\"playback_report_ack\",\"payload\":{\"queue_id\":84,\"status\":\"APPLIED\",\"report_type\":\"play_error\"}}",
                timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":999}}",
                timeout.Token);
            await acknowledgementProcessed.Task.WaitAsync(timeout.Token);
            var persisted = JsonSerializer.Deserialize<List<ReliableMessage>>(await File.ReadAllTextAsync(outboxPath));
            Assert.Empty(persisted!);

            Interlocked.Exchange(ref failNextSendUnexpectedly, 1);
            await Assert.ThrowsAsync<InvalidOperationException>(() =>
                client.SendPlayErrorAsync(85, 9, "unexpected failure", cancellationToken: timeout.Token));
            persisted = JsonSerializer.Deserialize<List<ReliableMessage>>(await File.ReadAllTextAsync(outboxPath));
            Assert.Equal("play_error:85", Assert.Single(persisted!).Key);
        }
        finally
        {
            if (serverSocket is not null)
            {
                try { await CloseServerSocketAsync(serverSocket, timeout.Token); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
                serverSocket.Dispose();
                serverTcpClient?.Dispose();
            }

            runCancellation.Cancel();
            if (runTask is not null)
            {
                try { await runTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
            }
            listener.Stop();
            if (File.Exists(outboxPath)) File.Delete(outboxPath);
            if (File.Exists(outboxPath + ".tmp")) File.Delete(outboxPath + ".tmp");
        }
    }

    [Fact]
    public async Task Reliable_send_waiting_for_the_send_lock_remains_queued_when_the_socket_closes()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var endpoint = new ServerEndpoint(new Uri($"http://127.0.0.1:{port}/"));
        var outboxPath = Path.Combine(AppContext.BaseDirectory, $"ktv-ws-close-during-send-{Guid.NewGuid():N}.json");
        var sendStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseSend = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var blockNextReliableSend = 1;
        var assigned = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var disconnected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        async Task SendSocketBytesAsync(ClientWebSocket active, byte[] bytes, CancellationToken cancellationToken)
        {
            var text = Encoding.UTF8.GetString(bytes);
            if (text.Contains("\"type\":\"play_error\"", StringComparison.Ordinal)
                && Interlocked.Exchange(ref blockNextReliableSend, 0) == 1)
            {
                sendStarted.TrySetResult();
                await releaseSend.Task.WaitAsync(cancellationToken);
                throw new IOException("synthetic send interrupted by socket close");
            }

            await active.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken);
        }

        await using var client = new KtvWebSocketClient(
            endpoint,
            "close-during-send-client",
            TimeSpan.FromHours(1),
            reliableOutboxPath: outboxPath,
            sendMessageAsync: SendSocketBytesAsync);
        client.PlayerAssignmentReceived += _ => assigned.TrySetResult();
        client.ConnectionChanged += connected =>
        {
            if (!connected) disconnected.TrySetResult();
        };

        using var runCancellation = new CancellationTokenSource();
        Task? runTask = null;
        (WebSocket Socket, string RequestLine, TcpClient Client)? session = null;
        try
        {
            runTask = client.RunAsync(runCancellation.Token);
            session = await AcceptWebSocketAsync(listener, timeout.Token).WaitAsync(timeout.Token);
            await SendTextAsync(session.Value.Socket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":12,\"lease_ms\":45000}}",
                timeout.Token);
            await assigned.Task.WaitAsync(timeout.Token);

            var firstSend = client.SendPlayErrorAsync(201, 31, "first", cancellationToken: timeout.Token);
            await sendStarted.Task.WaitAsync(timeout.Token);
            var secondSend = client.SendPlayErrorAsync(202, 32, "second", cancellationToken: timeout.Token);

            await session.Value.Socket.CloseOutputAsync(
                WebSocketCloseStatus.NormalClosure, "synthetic reconnect", timeout.Token);
            var closeBuffer = new byte[256];
            var closeResponse = await session.Value.Socket.ReceiveAsync(closeBuffer, timeout.Token);
            Assert.Equal(WebSocketMessageType.Close, closeResponse.MessageType);
            await disconnected.Task.WaitAsync(timeout.Token);

            releaseSend.TrySetResult();
            Assert.Equal(ReliableSendResult.Queued, await firstSend.WaitAsync(timeout.Token));
            Assert.Equal(ReliableSendResult.Queued, await secondSend.WaitAsync(timeout.Token));

            var queued = JsonSerializer.Deserialize<List<ReliableMessage>>(await File.ReadAllTextAsync(outboxPath));
            Assert.Equal(new[] { "play_error:201", "play_error:202" }, queued!.Select(message => message.Key));
        }
        finally
        {
            releaseSend.TrySetResult();
            if (session is { } accepted)
            {
                accepted.Socket.Dispose();
                accepted.Client.Dispose();
            }
            runCancellation.Cancel();
            if (runTask is not null)
            {
                try { await runTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (
                    exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
            }
            listener.Stop();
            if (File.Exists(outboxPath)) File.Delete(outboxPath);
            if (File.Exists(outboxPath + ".tmp")) File.Delete(outboxPath + ".tmp");
        }
    }

    [Fact]
    public async Task Queued_play_error_that_exceeds_the_budget_after_generation_is_removed_on_flush()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var endpoint = new ServerEndpoint(new Uri($"http://127.0.0.1:{port}/"));
        var outboxPath = Path.Combine(AppContext.BaseDirectory, $"ktv-ws-historical-size-{Guid.NewGuid():N}.json");
        var rejection = new TaskCompletionSource<(string Key, string Reason)>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        await using var client = new KtvWebSocketClient(
            endpoint, "historical-size-client", reliableOutboxPath: outboxPath);
        client.ReliableMessageRejected += (key, reason) => rejection.TrySetResult((key, reason));

        var minimumRejectedLength = 0;
        var maximumAcceptedLength = Utf8ByteBudget.MaxMessageBytes;
        while (minimumRejectedLength < maximumAcceptedLength)
        {
            var candidateLength = minimumRejectedLength + (maximumAcceptedLength - minimumRejectedLength) / 2;
            var candidate = ServerMessageFactory.PlayError(80, 9, new string('x', candidateLength));
            if (Utf8ByteBudget.GetByteCountAtMost(candidate, Utf8ByteBudget.MaxMessageBytes) is null)
                maximumAcceptedLength = candidateLength;
            else
                minimumRejectedLength = candidateLength + 1;
        }

        var message = new string('x', minimumRejectedLength - 1);
        Assert.NotNull(Utf8ByteBudget.GetByteCountAtMost(
            ServerMessageFactory.PlayError(80, 9, message), Utf8ByteBudget.MaxMessageBytes));
        const long oversizedGeneration = long.MaxValue;
        Assert.Null(Utf8ByteBudget.GetByteCountAtMost(
            ServerMessageFactory.PlayError(80, 9, message, generation: oversizedGeneration),
            Utf8ByteBudget.MaxMessageBytes));

        using var runCancellation = new CancellationTokenSource();
        Task? runTask = null;
        WebSocket? serverSocket = null;
        TcpClient? serverTcpClient = null;
        try
        {
            Assert.Equal(ReliableSendResult.Queued,
                await client.SendPlayErrorAsync(80, 9, message, cancellationToken: timeout.Token));
            var accepted = AcceptWebSocketAsync(listener, timeout.Token);
            runTask = client.RunAsync(runCancellation.Token);
            var session = await accepted.WaitAsync(timeout.Token);
            serverSocket = session.Socket;
            serverTcpClient = session.Client;

            await SendTextAsync(serverSocket,
                $"{{\"type\":\"player_role\",\"payload\":{{\"role\":\"ACTIVE\",\"generation\":{oversizedGeneration},\"lease_ms\":45000}}}}",
                timeout.Token);
            Assert.Equal(
                ("play_error:80", "historical message exceeds websocket byte budget"),
                await rejection.Task.WaitAsync(timeout.Token));
            var persisted = JsonSerializer.Deserialize<List<ReliableMessage>>(await File.ReadAllTextAsync(outboxPath));
            Assert.Empty(persisted!);
        }
        finally
        {
            if (serverSocket is not null)
            {
                try { await CloseServerSocketAsync(serverSocket, timeout.Token); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
                serverSocket.Dispose();
                serverTcpClient?.Dispose();
            }
            runCancellation.Cancel();
            if (runTask is not null)
            {
                try { await runTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
            }
            listener.Stop();
            if (File.Exists(outboxPath)) File.Delete(outboxPath);
            if (File.Exists(outboxPath + ".tmp")) File.Delete(outboxPath + ".tmp");
        }
    }

    [Fact]
    public async Task Disconnected_send_and_pre_cancelled_run_do_not_connect()
    {
        var storageRoot = Path.Combine(AppContext.BaseDirectory, $"ktv-ws-cancelled-{Guid.NewGuid():N}");
        var outboxPath = Path.Combine(storageRoot, "outbox.json");
        var finishedPath = Path.Combine(storageRoot, "finished.json");
        var endpoint = new ServerEndpoint(new Uri("http://127.0.0.1:1/"));
        await using var client = new KtvWebSocketClient(
            endpoint,
            "pre-cancelled-client",
            reliableOutboxPath: outboxPath,
            pendingFinishedPath: finishedPath);
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        var connectionChanges = 0;
        var connectionErrors = 0;
        client.ConnectionChanged += _ => connectionChanges++;
        client.ConnectionError += _ => connectionErrors++;

        await client.SendProgressAsync(1_234);
        await client.RunAsync(cancellation.Token).WaitAsync(TimeSpan.FromSeconds(2));

        Assert.False(client.IsConnected);
        Assert.Equal(0, connectionChanges);
        Assert.Equal(0, connectionErrors);
        Assert.False(Directory.Exists(storageRoot));
    }

    [Fact]
    public async Task Failed_loopback_connection_without_error_observer_can_be_cancelled()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        var storageRoot = Path.Combine(AppContext.BaseDirectory, $"ktv-ws-failed-connect-{Guid.NewGuid():N}");
        var endpoint = new ServerEndpoint(new Uri($"http://127.0.0.1:{port}/"));
        await using var client = new KtvWebSocketClient(
            endpoint,
            "failed-connect-client",
            reliableOutboxPath: Path.Combine(storageRoot, "outbox.json"),
            pendingFinishedPath: Path.Combine(storageRoot, "finished.json"));
        using var runCancellation = new CancellationTokenSource();
        var disconnected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        client.ConnectionChanged += connected =>
        {
            if (!connected)
            {
                disconnected.TrySetResult();
                runCancellation.Cancel();
            }
        };

        var runTask = client.RunAsync(runCancellation.Token);
        await disconnected.Task.WaitAsync(timeout.Token);
        await runTask.WaitAsync(timeout.Token);

        Assert.False(client.IsConnected);
        Assert.False(Directory.Exists(storageRoot));
    }

    [Fact]
    public async Task Secure_endpoint_accepts_a_synthetic_player_credential_before_connecting()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        var storageRoot = Path.Combine(AppContext.BaseDirectory, $"ktv-ws-secure-credential-{Guid.NewGuid():N}");
        var endpoint = new ServerEndpoint(new Uri($"https://127.0.0.1:{port}/"));
        await using var client = new KtvWebSocketClient(
            endpoint,
            "secure-credential-client",
            "synthetic-test-credential",
            Path.Combine(storageRoot, "outbox.json"),
            Path.Combine(storageRoot, "finished.json"));
        using var runCancellation = new CancellationTokenSource();
        var connectionError = new TaskCompletionSource<Exception>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        client.ConnectionError += exception =>
        {
            connectionError.TrySetResult(exception);
            runCancellation.Cancel();
        };

        var runTask = client.RunAsync(runCancellation.Token);
        var error = await connectionError.Task.WaitAsync(timeout.Token);
        await runTask.WaitAsync(timeout.Token);

        Assert.IsType<WebSocketException>(error);
        Assert.False(client.IsConnected);
        Assert.False(Directory.Exists(storageRoot));
    }

    [Fact]
    public async Task Finished_report_rejects_a_non_positive_queue_id_without_connecting()
    {
        await using var client = new KtvWebSocketClient(
            new ServerEndpoint(new Uri("http://127.0.0.1:8080/")), "invalid-finished-client");

        Assert.Equal(PendingPlaybackReportEnqueueResult.Invalid, await client.SendFinishedAsync(0));
        Assert.False(client.IsConnected);
    }

    [Fact]
    public async Task Receive_loop_rejects_message_above_the_wire_byte_limit()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var endpoint = new ServerEndpoint(new Uri(
            $"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/"));
        await using var client = new KtvWebSocketClient(endpoint, "oversized-client");
        var connectionError = new TaskCompletionSource<Exception>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        client.ConnectionError += exception => connectionError.TrySetResult(exception);
        using var runCancellation = new CancellationTokenSource();
        Task? runTask = null;
        (WebSocket Socket, string RequestLine, TcpClient Client)? session = null;

        try
        {
            var accepted = AcceptWebSocketAsync(listener, timeout.Token);
            runTask = client.RunAsync(runCancellation.Token);
            session = await accepted.WaitAsync(timeout.Token);
            await SendTextAsync(
                session.Value.Socket,
                new string('x', Utf8ByteBudget.MaxMessageBytes + 1),
                timeout.Token);

            await CloseServerSocketAsync(session.Value.Socket, timeout.Token);
            var error = Assert.IsType<WebSocketException>(await connectionError.Task.WaitAsync(timeout.Token));
            Assert.Contains("exceeded the size limit", error.Message, StringComparison.Ordinal);
        }
        finally
        {
            if (session is { } connected)
            {
                try { await CloseServerSocketAsync(connected.Socket, timeout.Token); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException) { }
                connected.Socket.Dispose();
                connected.Client.Dispose();
            }

            runCancellation.Cancel();
            if (runTask is not null)
            {
                try { await runTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
            }
            listener.Stop();
        }
    }

    [Fact]
    public async Task Disposing_a_connected_client_closes_the_local_socket_and_stops_its_run_loop()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var endpoint = new ServerEndpoint(new Uri(
            $"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/"));
        var client = new KtvWebSocketClient(endpoint, "dispose-client");
        var connected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        client.ConnectionChanged += isConnected =>
        {
            if (isConnected) connected.TrySetResult();
        };
        Task? runTask = null;
        Task? disposeTask = null;
        (WebSocket Socket, string RequestLine, TcpClient Client)? session = null;

        try
        {
            var accepted = AcceptWebSocketAsync(listener, timeout.Token);
            runTask = client.RunAsync();
            session = await accepted.WaitAsync(timeout.Token);
            await connected.Task.WaitAsync(timeout.Token);
            Assert.True(client.IsConnected);
            Assert.Same(runTask, client.RunAsync());

            disposeTask = client.DisposeAsync().AsTask();
            var buffer = new byte[256];
            var closeRequest = await session.Value.Socket.ReceiveAsync(buffer, timeout.Token);
            Assert.Equal(WebSocketMessageType.Close, closeRequest.MessageType);
            await session.Value.Socket.CloseOutputAsync(
                WebSocketCloseStatus.NormalClosure, "dispose acknowledged", timeout.Token);
            await disposeTask.WaitAsync(timeout.Token);
            await runTask.WaitAsync(timeout.Token);

            Assert.False(client.IsConnected);
            await client.DisposeAsync();
            await client.DisposeAsync();
            await Assert.ThrowsAsync<ObjectDisposedException>(() => client.RunAsync());
        }
        finally
        {
            if (session is { } connectedSession)
            {
                try { await CloseServerSocketAsync(connectedSession.Socket, timeout.Token); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException) { }
                connectedSession.Socket.Dispose();
                connectedSession.Client.Dispose();
            }

            if (disposeTask is not null)
            {
                try { await disposeTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
            }
            else
            {
                await client.DisposeAsync();
            }
            if (runTask is not null)
            {
                try { await runTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
            }
            listener.Stop();
        }
    }

    [Fact]
    public async Task Local_protocol_session_receives_lease_and_progress_and_replays_reliable_error()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var endpoint = new ServerEndpoint(new Uri($"http://127.0.0.1:{port}/"));
        var outboxPath = Path.Combine(AppContext.BaseDirectory, $"ktv-ws-{Guid.NewGuid():N}.json");
        var finishedPath = Path.Combine(AppContext.BaseDirectory, $"ktv-finished-{Guid.NewGuid():N}.json");
        await using var client = new KtvWebSocketClient(
            endpoint, "client token", reliableOutboxPath: outboxPath, pendingFinishedPath: finishedPath);

        var assignmentReceived = new TaskCompletionSource<PlayerAssignment>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var progressPositions = new ConcurrentQueue<long>();
        var progressReceived = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var legacyAckProcessed = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var snapshotReceived = new TaskCompletionSource<(string EventType, QueueSnapshot Snapshot)>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var chunkReceived = new TaskCompletionSource<QueueSnapshot>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var partialChunkProcessed = new TaskCompletionSource(
            TaskCreationOptions.RunContinuationsAsynchronously);
        using var runCancellation = new CancellationTokenSource();
        Action<PlayerAssignment> assignmentHandler = assignment => assignmentReceived.TrySetResult(assignment);
        client.PlayerAssignmentReceived += assignmentHandler;
        client.ProgressReceived += position =>
        {
            progressPositions.Enqueue(position);
            if (position == 42) progressReceived.TrySetResult();
            if (position == 43) partialChunkProcessed.TrySetResult();
            if (position == 44) legacyAckProcessed.TrySetResult();
        };
        client.SnapshotReceived += (eventType, snapshot, _) =>
        {
            if (eventType == "sync_full") snapshotReceived.TrySetResult((eventType, snapshot));
            if (eventType == "queue_updated") chunkReceived.TrySetResult(snapshot);
            return Task.CompletedTask;
        };

        TcpClient? serverTcpClient = null;
        WebSocket? serverSocket = null;
        Task? runTask = null;
        try
        {
            var queued = await client.SendPlayErrorAsync(42, null, "synthetic failure", cancellationToken: timeout.Token);
            Assert.Equal(ReliableSendResult.Queued, queued);

            var accepted = AcceptWebSocketAsync(listener, timeout.Token);
            runTask = client.RunAsync(runCancellation.Token);
            var session = await accepted.WaitAsync(timeout.Token);
            serverTcpClient = session.Client;
            serverSocket = session.Socket;

            Assert.StartsWith("GET /ws?client_type=tv&", session.RequestLine, StringComparison.Ordinal);
            Assert.Contains("client_token=client%20token", session.RequestLine, StringComparison.Ordinal);
            Assert.Contains("protocol_version=2", session.RequestLine, StringComparison.Ordinal);
            Assert.Contains("platform=WINDOWS", session.RequestLine, StringComparison.Ordinal);

            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{}}",
                timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\"}}",
                timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"pong\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":6}}",
                timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":7,\"lease_ms\":45000}}",
                timeout.Token);
            var assignment = await assignmentReceived.Task.WaitAsync(timeout.Token);
            Assert.Equal(new PlayerAssignment("ACTIVE", 7, 45_000), assignment);

            var replay = await ReceiveTextAsync(serverSocket, timeout.Token);
            using (var message = JsonDocument.Parse(replay))
            {
                Assert.Equal("play_error", message.RootElement.GetProperty("type").GetString());
                var payload = message.RootElement.GetProperty("payload");
                Assert.Equal(42, payload.GetProperty("queue_id").GetInt64());
                Assert.Equal(7, payload.GetProperty("generation").GetInt64());
                Assert.Equal("synthetic failure", payload.GetProperty("message").GetString());
            }

            await SendTextAsync(serverSocket,
                "{\"type\":\"playback_report_ack\",\"payload\":{\"queue_id\":42,\"status\":\"APPLIED\",\"report_type\":\"play_error\"}}",
                timeout.Token);

            Assert.Equal(PendingPlaybackReportEnqueueResult.Added, await client.SendFinishedAsync(43, cancellationToken: timeout.Token));
            using (var firstFinished = JsonDocument.Parse(await ReceiveTextAsync(serverSocket, timeout.Token)))
            {
                Assert.Equal("finished", firstFinished.RootElement.GetProperty("type").GetString());
                Assert.Equal(43, firstFinished.RootElement.GetProperty("payload").GetProperty("queue_id").GetInt64());
            }
            await SendTextAsync(serverSocket,
                "{\"type\":\"playback_report_ack\",\"payload\":{\"queue_id\":43,\"status\":\"APPLIED\"}}",
                timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":44}}",
                timeout.Token);
            await legacyAckProcessed.Task.WaitAsync(timeout.Token);
            Assert.Equal(PendingPlaybackReportEnqueueResult.Added, await client.SendFinishedAsync(43, cancellationToken: timeout.Token));
            using (var requeuedFinished = JsonDocument.Parse(await ReceiveTextAsync(serverSocket, timeout.Token)))
            {
                Assert.Equal("finished", requeuedFinished.RootElement.GetProperty("type").GetString());
                Assert.Equal(43, requeuedFinished.RootElement.GetProperty("payload").GetProperty("queue_id").GetInt64());
            }

            await SendTextAsync(serverSocket, "{malformed json", timeout.Token);
            await SendTextAsync(serverSocket, "null", timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"progress\",\"payload\":{}}",
                timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":-9}}",
                timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":42}}",
                timeout.Token);
            await progressReceived.Task.WaitAsync(timeout.Token);
            Assert.Equal(new long[] { 44, 0, 42 }, progressPositions.Take(3));

            Assert.Equal(ReliableSendResult.Sent,
                await client.SendPlayErrorAsync(42, null, "synthetic failure", cancellationToken: timeout.Token));
            var secondSend = await ReceiveTextAsync(serverSocket, timeout.Token);
            using (var message = JsonDocument.Parse(secondSend))
            {
                Assert.Equal("play_error", message.RootElement.GetProperty("type").GetString());
                Assert.Equal(7, message.RootElement.GetProperty("payload").GetProperty("generation").GetInt64());
            }

            client.PlayerAssignmentReceived -= assignmentHandler;
            await SendTextAsync(serverSocket,
                "{\"type\":\"pong\",\"payload\":{\"role\":null,\"generation\":8,\"lease_ms\":45000}}",
                timeout.Token);
            await SendTextAsync(serverSocket, "{\"type\":\"sync_full\",\"payload\":null}", timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"sync_full\",\"payload\":{\"playing\":null,\"list\":[],\"state\":\"idle\",\"volume\":60,\"muted\":false,\"vocalMode\":\"accompaniment\",\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"},\"tvOnline\":false,\"connectedPhones\":0}}",
                timeout.Token);
            var snapshot = await snapshotReceived.Task.WaitAsync(timeout.Token);
            Assert.Equal("sync_full", snapshot.EventType);
            Assert.Equal("idle", snapshot.Snapshot.State);
            Assert.Empty(snapshot.Snapshot.List);

            await SendTextAsync(serverSocket, "{\"type\":\"snapshot_chunk\",\"payload\":null}", timeout.Token);
            const string snapshotHeader = "\"header\":{\"playing\":null,\"state\":\"idle\",\"volume\":60,\"muted\":false,\"vocalMode\":\"accompaniment\",\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"},\"tvOnline\":false,\"connectedPhones\":0,\"positionMs\":0,\"seekSequence\":0}";
            await SendTextAsync(serverSocket,
                $$$"""{"type":"snapshot_chunk","payload":{"eventType":"queue_updated","syncId":"test-sync","index":0,"total":2,"last":false,{{{snapshotHeader}}},"entries":[]}}""",
                timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":43}}",
                timeout.Token);
            await partialChunkProcessed.Task.WaitAsync(timeout.Token);
            Assert.Equal(43, progressPositions.Last());
            Assert.False(chunkReceived.Task.IsCompleted);
            await SendTextAsync(serverSocket,
                $$$"""{"type":"snapshot_chunk","payload":{"eventType":"queue_updated","syncId":"test-sync","index":1,"total":2,"last":true,{{{snapshotHeader}}},"entries":[{"queueId":43,"song":null,"orderedBy":null,"orderedByNick":null,"status":"waiting"}]}}""",
                timeout.Token);
            var chunkedSnapshot = await chunkReceived.Task.WaitAsync(timeout.Token);
            Assert.Equal(43, chunkedSnapshot.List.Single().QueueId);
        }
        finally
        {
            if (serverSocket is not null)
            {
                try
                {
                    if (serverSocket.State == WebSocketState.Open)
                    {
                        await serverSocket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "test complete",
                            timeout.Token);
                    }
                    if (serverSocket.State is WebSocketState.CloseSent or WebSocketState.CloseReceived)
                    {
                        var buffer = new byte[256];
                        _ = await serverSocket.ReceiveAsync(buffer, timeout.Token);
                    }
                }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException)
                {
                    // Test cleanup must not hide the assertion that caused the session to end.
                }
                serverSocket.Dispose();
            }

            runCancellation.Cancel();
            if (runTask is not null)
            {
                try { await runTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException)
                {
                    // Cancellation or a closed loopback socket is expected during cleanup.
                }
            }

            serverTcpClient?.Dispose();
            listener.Stop();
            if (File.Exists(outboxPath)) File.Delete(outboxPath);
            if (File.Exists(outboxPath + ".tmp")) File.Delete(outboxPath + ".tmp");
            if (File.Exists(finishedPath)) File.Delete(finishedPath);
            if (File.Exists(finishedPath + ".tmp")) File.Delete(finishedPath + ".tmp");
        }
    }

    [Fact]
    public async Task Client_reconnects_after_server_closes_the_first_local_session()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(12));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var endpoint = new ServerEndpoint(new Uri($"http://127.0.0.1:{port}/"));
        await using var client = new KtvWebSocketClient(endpoint, "synthetic-client");

        var firstConnection = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var disconnected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var secondConnection = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var resumedProgress = new TaskCompletionSource<long>(TaskCreationOptions.RunContinuationsAsynchronously);
        var connectionCount = 0;
        client.ConnectionChanged += connected =>
        {
            if (!connected)
            {
                if (connectionCount > 0) disconnected.TrySetResult();
                return;
            }

            if (Interlocked.Increment(ref connectionCount) == 1) firstConnection.TrySetResult();
            else secondConnection.TrySetResult();
        };
        client.ProgressReceived += position => resumedProgress.TrySetResult(position);

        using var runCancellation = new CancellationTokenSource();
        Assert.False(client.IsConnected);
        Task? runTask = null;
        (WebSocket Socket, string RequestLine, TcpClient Client)? firstSession = null;
        (WebSocket Socket, string RequestLine, TcpClient Client)? secondSession = null;
        try
        {
            runTask = client.RunAsync(runCancellation.Token);
            firstSession = await AcceptWebSocketAsync(listener, timeout.Token).WaitAsync(timeout.Token);
            await firstConnection.Task.WaitAsync(timeout.Token);
            Assert.True(client.IsConnected);

            await firstSession.Value.Socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "restart",
                timeout.Token);
            var closeBuffer = new byte[256];
            var closeResponse = await firstSession.Value.Socket.ReceiveAsync(closeBuffer, timeout.Token);
            Assert.Equal(WebSocketMessageType.Close, closeResponse.MessageType);
            await disconnected.Task.WaitAsync(timeout.Token);
            Assert.False(client.IsConnected);

            secondSession = await AcceptWebSocketAsync(listener, timeout.Token).WaitAsync(timeout.Token);
            Assert.StartsWith("GET /ws?client_type=tv&", secondSession.Value.RequestLine, StringComparison.Ordinal);
            await secondConnection.Task.WaitAsync(timeout.Token);
            Assert.True(client.IsConnected);
            await SendTextAsync(secondSession.Value.Socket,
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":1234}}",
                timeout.Token);
            Assert.Equal(1_234, await resumedProgress.Task.WaitAsync(timeout.Token));
        }
        finally
        {
            if (secondSession is { } second)
            {
                try { await CloseServerSocketAsync(second.Socket, timeout.Token); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException) { }
                second.Socket.Dispose();
                second.Client.Dispose();
            }
            if (firstSession is { } first)
            {
                first.Socket.Dispose();
                first.Client.Dispose();
            }

            runCancellation.Cancel();
            if (runTask is not null)
            {
                try { await runTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
            }
            listener.Stop();
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Progress_observer_cancellation_or_disposal_stops_the_receive_loop(
        bool disposeClientFromObserver)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var endpoint = new ServerEndpoint(new Uri(
            $"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/"));
        await using var client = new KtvWebSocketClient(endpoint, "cancel-from-observer-client");
        using var runCancellation = new CancellationTokenSource();
        var disconnected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        Task? disposeTask = null;
        client.ProgressReceived += _ =>
        {
            if (disposeClientFromObserver)
            {
                disposeTask ??= client.DisposeAsync().AsTask();
            }
            else
            {
                runCancellation.Cancel();
            }
        };
        client.ConnectionChanged += connected =>
        {
            if (!connected) disconnected.TrySetResult();
        };

        Task? runTask = null;
        (WebSocket Socket, string RequestLine, TcpClient Client)? session = null;
        try
        {
            runTask = client.RunAsync(runCancellation.Token);
            session = await AcceptWebSocketAsync(listener, timeout.Token).WaitAsync(timeout.Token);
            await SendTextAsync(session.Value.Socket,
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":2345}}",
                timeout.Token);

            var serverCloseHandshake = Task.Run(async () =>
            {
                var buffer = new byte[256];
                var response = await session.Value.Socket.ReceiveAsync(buffer, timeout.Token);
                Assert.Equal(WebSocketMessageType.Close, response.MessageType);
                if (session.Value.Socket.State == WebSocketState.CloseReceived)
                {
                    await session.Value.Socket.CloseOutputAsync(
                        WebSocketCloseStatus.NormalClosure, "observer cancelled", timeout.Token);
                }
            }, timeout.Token);

            await runTask.WaitAsync(timeout.Token);
            await serverCloseHandshake.WaitAsync(timeout.Token);
            await disconnected.Task.WaitAsync(timeout.Token);
            if (disposeTask is not null) await disposeTask.WaitAsync(timeout.Token);

            Assert.False(client.IsConnected);
        }
        finally
        {
            runCancellation.Cancel();
            if (session is { } accepted)
            {
                accepted.Socket.Dispose();
                accepted.Client.Dispose();
            }
            if (runTask is not null)
            {
                try { await runTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (
                    exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
            }
            listener.Stop();
        }
    }

    private static async Task<(WebSocket Socket, string RequestLine, TcpClient Client)> AcceptWebSocketAsync(
        TcpListener listener,
        CancellationToken cancellationToken)
    {
        var tcpClient = await listener.AcceptTcpClientAsync(cancellationToken);
        var stream = tcpClient.GetStream();
        using var reader = new StreamReader(stream, Encoding.ASCII, detectEncodingFromByteOrderMarks: false,
            bufferSize: 1024, leaveOpen: true);

        var requestLine = await reader.ReadLineAsync(cancellationToken)
            ?? throw new IOException("WebSocket request line was missing.");
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        string? line;
        while (!string.IsNullOrEmpty(line = await reader.ReadLineAsync(cancellationToken)))
        {
            var separator = line.IndexOf(':');
            if (separator > 0) headers[line[..separator]] = line[(separator + 1)..].Trim();
        }

        var key = headers["Sec-WebSocket-Key"];
        var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(
            key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
        var response = Encoding.ASCII.GetBytes(
            "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            $"Sec-WebSocket-Accept: {accept}\r\n\r\n");
        await stream.WriteAsync(response, cancellationToken);

        var socket = WebSocket.CreateFromStream(stream, isServer: true, subProtocol: null,
            keepAliveInterval: Timeout.InfiniteTimeSpan);
        return (socket, requestLine, tcpClient);
    }

    private static async Task CloseServerSocketAsync(WebSocket socket, CancellationToken cancellationToken)
    {
        if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
        {
            await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "test complete", cancellationToken);
        }
        if (socket.State == WebSocketState.CloseSent)
        {
            var buffer = new byte[256];
            _ = await socket.ReceiveAsync(buffer, cancellationToken);
        }
    }

    private static async Task SendTextAsync(WebSocket socket, string text, CancellationToken cancellationToken)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        await socket.SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, cancellationToken);
    }

    private static async Task<string> ReceiveTextAsync(WebSocket socket, CancellationToken cancellationToken)
    {
        var buffer = new byte[4096];
        using var message = new MemoryStream();
        WebSocketReceiveResult result;
        do
        {
            result = await socket.ReceiveAsync(buffer, cancellationToken);
            if (result.MessageType == WebSocketMessageType.Close)
                throw new WebSocketException("Client closed before replaying the queued report.");
            message.Write(buffer, 0, result.Count);
        } while (!result.EndOfMessage);

        return Encoding.UTF8.GetString(message.ToArray());
    }
}
