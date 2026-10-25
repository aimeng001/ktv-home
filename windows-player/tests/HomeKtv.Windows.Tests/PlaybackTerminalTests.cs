using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Collections.Concurrent;
using HomeKtv.Windows.Mpv;
using HomeKtv.Windows.Playback;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class PlaybackTerminalTests
{
    [Fact]
    public async Task Commands_are_forwarded_to_server_and_display_selection_is_clamped_without_starting_mpv()
    {
        var requests = new List<(string Method, string Uri, string? Body)>();
        var handler = new RecordingHandler(request =>
        {
            var body = request.Content?.ReadAsStringAsync().GetAwaiter().GetResult();
            requests.Add((request.Method.Method, request.RequestUri!.ToString(), body));
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("null") };
        });
        var endpoint = new ServerEndpoint(new Uri("http://127.0.0.1:8080/"));
        var factory = new RecordingMpvSessionFactory();
        await using var terminal = CreateTerminal(endpoint, handler, factory);

        await terminal.PlayAsync();
        await terminal.PauseAsync();
        await terminal.StopAsync();
        await terminal.SeekAsync(-25);
        await terminal.ReplayAsync();
        await terminal.NextAsync();
        await terminal.SetVolumeAsync(73);
        await terminal.SetMutedAsync(true);
        await terminal.SetVocalModeAsync("original");
        await terminal.SwapVocalTracksAsync();
        await terminal.SetDisplayAsync(-4);

        var actions = requests.Select(request =>
        {
            using var body = JsonDocument.Parse(request.Body!);
            return body.RootElement.GetProperty("action").GetString();
        });
        Assert.Equal(
            new[] { "play", "pause", "stop", "seek", "restart", "next", "set_volume", "mute", "set_vocal", "swap_vocal_tracks" },
            actions);
        using var seek = JsonDocument.Parse(requests[3].Body!);
        Assert.Equal(0, seek.RootElement.GetProperty("params").GetProperty("position_ms").GetInt64());
        Assert.All(requests, request => Assert.Equal("POST", request.Method));
        Assert.All(requests, request => Assert.Equal("http://127.0.0.1:8080/api/control", request.Uri));
        Assert.Equal(0, factory.ScreenIndex);
        Assert.Equal(0, factory.StartCount);
    }

    [Fact]
    public async Task Connect_fails_closed_when_the_server_health_contract_is_unavailable()
    {
        HttpRequestMessage? observedRequest = null;
        var handler = new RecordingHandler(request =>
        {
            observedRequest = request;
            return new HttpResponseMessage(HttpStatusCode.ServiceUnavailable);
        });
        var endpoint = new ServerEndpoint(new Uri("http://127.0.0.1:8080/"));
        var factory = new RecordingMpvSessionFactory();
        await using var terminal = CreateTerminal(endpoint, handler, factory);

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() => terminal.ConnectAsync());

        Assert.Equal("Home KTV server health check failed.", error.Message);
        Assert.Equal("http://127.0.0.1:8080/api/health", observedRequest!.RequestUri!.ToString());
        Assert.False(terminal.IsConnected);
        Assert.False(terminal.IsOutputRunning);
        Assert.Null(terminal.CurrentSnapshot);
        Assert.Equal(0, factory.StartCount);
    }

    [Fact]
    public async Task Asset_request_is_delegated_to_the_configured_server()
    {
        HttpRequestMessage? observedRequest = null;
        var handler = new RecordingHandler(request =>
        {
            observedRequest = request;
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent([4, 8, 15, 16, 23, 42]),
            };
        });
        var endpoint = new ServerEndpoint(new Uri("http://127.0.0.1:8080/"));
        await using var terminal = CreateTerminal(endpoint, handler, new RecordingMpvSessionFactory());

        var bytes = await terminal.GetAssetBytesAsync("/api/artists/avatar?id=7");

        Assert.Equal(new byte[] { 4, 8, 15, 16, 23, 42 }, bytes);
        Assert.Equal("http://127.0.0.1:8080/api/artists/avatar?id=7", observedRequest!.RequestUri!.ToString());
    }

    [Fact]
    public async Task Dispose_is_idempotent_without_starting_external_services()
    {
        var endpoint = new ServerEndpoint(new Uri("http://127.0.0.1:8080/"));
        var factory = new RecordingMpvSessionFactory();
        var terminal = CreateTerminal(endpoint,
            new RecordingHandler(_ => throw new InvalidOperationException("HTTP must not be used")), factory);

        await terminal.DisposeAsync();
        await terminal.DisposeAsync();

        Assert.Equal(0, factory.StartCount);
    }

    [Fact]
    public async Task Reliable_report_rejection_is_reported_until_terminal_disposal()
    {
        var endpoint = new ServerEndpoint(new Uri("http://127.0.0.1:8080/"));
        var socket = new KtvWebSocketClient(endpoint, "rejected-report-client");
        var terminal = CreateTerminal(endpoint,
            new RecordingHandler(_ => throw new InvalidOperationException("HTTP must not be used")),
            new RecordingMpvSessionFactory(), socket: socket);
        var errors = new ConcurrentQueue<Exception>();
        terminal.Error += errors.Enqueue;
        var rejected = new string('x', Utf8ByteBudget.MaxMessageBytes);

        Assert.Equal(ReliableSendResult.Rejected,
            await socket.SendPlayErrorAsync(1, null, rejected));
        var error = Assert.IsType<InvalidOperationException>(Assert.Single(errors));
        Assert.Equal("Reliable playback report rejected: message exceeds websocket byte budget", error.Message);

        await terminal.DisposeAsync();
        Assert.Equal(ReliableSendResult.Rejected,
            await socket.SendPlayErrorAsync(2, null, rejected));
        Assert.Single(errors);
    }

    [Fact]
    public async Task Websocket_connection_errors_are_forwarded_to_the_terminal()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        using var reservedPort = new TcpListener(IPAddress.Loopback, 0);
        reservedPort.Start();
        var port = ((IPEndPoint)reservedPort.LocalEndpoint).Port;
        reservedPort.Stop();
        var endpoint = new ServerEndpoint(new Uri($"http://127.0.0.1:{port}/"));
        var socket = new KtvWebSocketClient(endpoint, "connection-error-client");
        var terminal = CreateTerminal(endpoint,
            new RecordingHandler(_ => throw new InvalidOperationException("HTTP must not be used")),
            new RecordingMpvSessionFactory(), socket: socket);
        var forwardedError = new TaskCompletionSource<Exception>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        terminal.Error += exception => forwardedError.TrySetResult(exception);
        Task? runTask = null;

        try
        {
            runTask = socket.RunAsync(timeout.Token);
            var error = await forwardedError.Task.WaitAsync(timeout.Token);
            Assert.NotNull(error);
            Assert.False(terminal.IsConnected);
        }
        finally
        {
            await terminal.DisposeAsync();
            if (runTask is not null)
            {
                try { await runTask.WaitAsync(TimeSpan.FromSeconds(2)); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException or TimeoutException) { }
            }
        }
    }

    [Fact]
    public async Task Active_lease_projects_server_snapshot_to_a_simulated_mpv_session()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var endpoint = new ServerEndpoint(new Uri($"http://127.0.0.1:{port}/"));
        var handler = new RecordingHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/health" => JsonResponse(HttpStatusCode.OK, "{\"status\":\"UP\",\"service\":\"home-ktv\"}"),
            "/api/queue" => JsonResponse(HttpStatusCode.OK, "null"),
            "/api/songs/2" => new HttpResponseMessage(HttpStatusCode.NotFound),
            "/api/songs/1" => JsonResponse(HttpStatusCode.OK,
                "{\"id\":1,\"title\":\"Synthetic\",\"artist\":\"Test\",\"mediaType\":\"VIDEO\",\"hasVocalTrack\":false,\"durationMs\":10000,\"lyricType\":\"none\",\"coverUrl\":null,\"lyricUrl\":null,\"files\":[{\"id\":7,\"format\":\"mp4\",\"audioTracks\":1,\"vocalTrackIndex\":0,\"resolution\":\"1920x1080\",\"priority\":1,\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"},\"ready\":true}]}"),
            "/api/playback/resolve/7" => JsonResponse(HttpStatusCode.OK,
                "{\"kind\":\"NATIVE\",\"status\":\"READY\",\"sourceFileId\":7,\"streamUrl\":\"/api/stream/7\",\"audioTracks\":1,\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"}}"),
            _ => new HttpResponseMessage(HttpStatusCode.NotFound),
        });
        var factory = new SimulatedMpvSessionFactory();
        var output = new MpvProcessController(factory);
        var playbackFinished = new TaskCompletionSource<long>(TaskCreationOptions.RunContinuationsAsynchronously);
        output.PlaybackFinished += fileId => playbackFinished.TrySetResult(fileId);
        var outboxPath = Path.Combine(AppContext.BaseDirectory, $"terminal-ws-{Guid.NewGuid():N}.json");
        var finishedPath = Path.Combine(AppContext.BaseDirectory, $"terminal-finished-{Guid.NewGuid():N}.json");
        var socket = new KtvWebSocketClient(endpoint, "terminal-test-client",
            reliableOutboxPath: outboxPath, pendingFinishedPath: finishedPath);
        var assignmentReceived = new TaskCompletionSource<PlayerAssignment>(TaskCreationOptions.RunContinuationsAsynchronously);
        socket.PlayerAssignmentReceived += assignment => assignmentReceived.TrySetResult(assignment);
        var terminal = CreateTerminal(endpoint, handler, factory, output, socket);
        var connected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var snapshotReceived = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var terminalError = new TaskCompletionSource<Exception>(TaskCreationOptions.RunContinuationsAsynchronously);
        var appliedSnapshotCount = 0;
        var observedTerminalErrors = new ConcurrentQueue<Exception>();
        terminal.ConnectionChanged += isConnected =>
        {
            if (isConnected) connected.TrySetResult();
        };
        terminal.SnapshotChanged += _ =>
        {
            Interlocked.Increment(ref appliedSnapshotCount);
            snapshotReceived.TrySetResult();
        };
        terminal.Error += exception =>
        {
            observedTerminalErrors.Enqueue(exception);
            if (exception is PlaybackAttemptException) terminalError.TrySetResult(exception);
        };

        WebSocket? serverSocket = null;
        TcpClient? serverTcpClient = null;
        try
        {
            await terminal.ConnectAsync(timeout.Token);
            var session = await AcceptWebSocketAsync(listener, timeout.Token).WaitAsync(timeout.Token);
            serverSocket = session.Socket;
            serverTcpClient = session.Client;
            await connected.Task.WaitAsync(timeout.Token);

            var progressMessage = ReceiveTextAsync(serverSocket, timeout.Token);
            await Task.Delay(TimeSpan.FromMilliseconds(1_200), timeout.Token);
            Assert.False(progressMessage.IsCompleted, "Progress must stay silent without an active lease.");

            await SendTextAsync(serverSocket, SnapshotMessage("sync_full", 99, null), timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":7,\"lease_ms\":45000}}",
                timeout.Token);
            Assert.Equal(new PlayerAssignment("ACTIVE", 7, 45_000),
                await assignmentReceived.Task.WaitAsync(timeout.Token));
            Assert.Null(terminal.CurrentSnapshot);
            await Task.Delay(TimeSpan.FromMilliseconds(1_200), timeout.Token);
            Assert.False(progressMessage.IsCompleted, "Progress must stay silent until a media output is active.");
            await SendTextAsync(serverSocket,
                "{\"type\":\"sync_full\",\"payload\":{\"playing\":{\"queueId\":5,\"song\":{\"id\":1,\"title\":\"Synthetic\",\"artist\":\"Test\",\"mediaType\":\"VIDEO\",\"hasVocalTrack\":false,\"durationMs\":10000,\"lyricType\":\"none\"},\"orderedByNick\":\"Test\"},\"list\":[],\"state\":\"playing\",\"volume\":50,\"muted\":false,\"vocalMode\":\"original\",\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"},\"tvOnline\":true,\"connectedPhones\":0,\"positionMs\":0,\"seekSequence\":0,\"stateRevision\":1}}",
                timeout.Token);

            await snapshotReceived.Task.WaitAsync(timeout.Token);
            await factory.Session.PlayCommand.Task.WaitAsync(timeout.Token);

            Assert.Equal(5, terminal.CurrentSnapshot!.Playing!.QueueId);
            Assert.Contains(factory.Session.Commands, command => command == "[\"loadfile\",\"http://127.0.0.1:" + port + "/api/stream/7\",\"replace\"]");
            Assert.Contains(factory.Session.Commands, command => command == "[\"set_property\",\"pause\",false]");
            Assert.Contains(factory.Session.Commands, command => command == "[\"set_property\",\"volume\",50]");

            using (var progress = JsonDocument.Parse(await progressMessage.WaitAsync(timeout.Token)))
            {
                Assert.Equal("progress", progress.RootElement.GetProperty("type").GetString());
                var progressPayload = progress.RootElement.GetProperty("payload");
                Assert.Equal(5_125, progressPayload.GetProperty("position_ms").GetInt64());
                Assert.Equal(5, progressPayload.GetProperty("queue_id").GetInt64());
                Assert.Equal(7, progressPayload.GetProperty("generation").GetInt64());
                Assert.Equal(5_125, terminal.CurrentPositionMs);
            }

            var beforeOutOfOrderSnapshots = Volatile.Read(ref appliedSnapshotCount);
            var nextQueueEntries = new object[]
            {
                new
                {
                    queueId = 6,
                    song = new
                    {
                        id = 2,
                        title = "Synthetic next",
                        artist = "Test",
                        mediaType = "VIDEO",
                        hasVocalTrack = false,
                        durationMs = 10_000,
                        lyricType = "none",
                    },
                    orderedBy = 1,
                    orderedByNick = "Test",
                    status = "waiting",
                },
            };
            var latestSnapshot = SnapshotMessage("queue_updated", 2, 1, 90, "playing", nextQueueEntries,
                playingQueueId: 5);
            var staleControlResponse = SnapshotMessage("queue_updated", 1, null, 10, "idle");
            await SendTextAsync(serverSocket, latestSnapshot, timeout.Token);
            await WaitForAsync(() => terminal.CurrentSnapshot?.StateRevision == 2, timeout.Token);
            await SendTextAsync(serverSocket, staleControlResponse, timeout.Token);
            // Equal-revision barrier proves the ordered stale frame was processed
            // before the assertion without changing the expected projection.
            await SendTextAsync(serverSocket, latestSnapshot, timeout.Token);
            await WaitForAsync(() => Volatile.Read(ref appliedSnapshotCount) == beforeOutOfOrderSnapshots + 2,
                timeout.Token);
            Assert.Equal(beforeOutOfOrderSnapshots + 2, Volatile.Read(ref appliedSnapshotCount));
            Assert.Equal(2, terminal.CurrentSnapshot!.StateRevision);
            Assert.Equal("playing", terminal.CurrentSnapshot.State);
            Assert.Equal(90, terminal.CurrentSnapshot.Volume);
            Assert.Equal(5, terminal.CurrentSnapshot.Playing!.QueueId);
            Assert.Equal(6, terminal.CurrentSnapshot.List.Single().QueueId);
            await WaitForAsync(
                () => factory.Session.Commands.Contains("[\"set_property\",\"volume\",90]"),
                timeout.Token);

            var receivedPositions = new ConcurrentQueue<long>();
            terminal.PositionChanged += receivedPositions.Enqueue;
            await SendTextAsync(serverSocket,
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":6250}}",
                timeout.Token);
            await WaitForAsync(() => receivedPositions.Contains(6_250), timeout.Token);
            Assert.Equal(6_250, terminal.CurrentPositionMs);

            await SendTextAsync(serverSocket,
                "{\"type\":\"progress\",\"payload\":{\"position_ms\":-25}}",
                timeout.Token);
            await WaitForAsync(() => receivedPositions.Contains(0), timeout.Token);
            Assert.Equal(0, terminal.CurrentPositionMs);

            factory.Session.ReportDisconnected(new IOException("synthetic IPC disconnect"));
            try
            {
                await factory.Session.RecoveredProjection.Task.WaitAsync(timeout.Token);
            }
            catch (OperationCanceledException) when (timeout.IsCancellationRequested)
            {
                throw new Xunit.Sdk.XunitException(
                    $"mpv recovery did not reload; loads={factory.Session.LoadCount}; "
                    + $"snapshot={terminal.CurrentSnapshot?.StateRevision}/{terminal.CurrentSnapshot?.State}/"
                    + $"{terminal.CurrentSnapshot?.Playing?.QueueId}; "
                    + $"commands={string.Join(" | ", factory.Session.Commands)}; "
                    + $"errors={string.Join(" | ", observedTerminalErrors.Select(error => error.ToString()))}");
            }
            Assert.Equal(2, factory.Session.LoadCount);

            factory.Session.Notify("start-file", "{\"playlist_entry_id\":9}");
            factory.Session.Notify("file-loaded", "{\"playlist_entry_id\":9}");
            factory.Session.Notify("end-file", "{\"playlist_entry_id\":9,\"reason\":\"eof\"}");
            Assert.Equal(7, await playbackFinished.Task.WaitAsync(timeout.Token));
            var receiveReport = ReceiveTextAsync(serverSocket, timeout.Token);
            var completed = await Task.WhenAny(receiveReport, terminalError.Task);
            if (completed == terminalError.Task)
            {
                throw new InvalidOperationException("Terminal rejected the simulated finished report.",
                    await terminalError.Task);
            }
            var finishedReport = await receiveReport;
            using var finished = JsonDocument.Parse(finishedReport);
            Assert.Equal("finished", finished.RootElement.GetProperty("type").GetString());
            Assert.Equal(5, finished.RootElement.GetProperty("payload").GetProperty("queue_id").GetInt64());
            Assert.Equal(7, finished.RootElement.GetProperty("payload").GetProperty("generation").GetInt64());

            factory.Session.Notify("start-file", "{\"playlist_entry_id\":10}");
            factory.Session.Notify("file-loaded", "{\"playlist_entry_id\":10}");
            factory.Session.Notify("end-file", "{\"playlist_entry_id\":10,\"reason\":\"error\"}");
            var playbackError = Assert.IsType<PlaybackAttemptException>(
                await terminalError.Task.WaitAsync(timeout.Token));
            Assert.Equal(PlaybackFailureKind.OutputFailure, playbackError.FailureKind);
            await factory.Session.StopCommand.Task.WaitAsync(timeout.Token);
            Assert.Null(output.CurrentFileId);

            await SendTextAsync(serverSocket,
                "{\"type\":\"now_playing\",\"payload\":{\"playing\":{\"queueId\":6,\"song\":{\"id\":2,\"title\":\"Missing\",\"artist\":\"Test\",\"mediaType\":\"VIDEO\",\"hasVocalTrack\":false,\"durationMs\":10000,\"lyricType\":\"none\"},\"orderedByNick\":\"Test\"},\"list\":[],\"state\":\"playing\",\"volume\":50,\"muted\":false,\"vocalMode\":\"original\",\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"},\"tvOnline\":true,\"connectedPhones\":0,\"positionMs\":0,\"seekSequence\":0,\"stateRevision\":2}}",
                timeout.Token);
            var playErrorReport = await ReceiveTextAsync(serverSocket, timeout.Token);
            using var playError = JsonDocument.Parse(playErrorReport);
            Assert.Equal("play_error", playError.RootElement.GetProperty("type").GetString());
            var errorPayload = playError.RootElement.GetProperty("payload");
            Assert.Equal(6, errorPayload.GetProperty("queue_id").GetInt64());
            Assert.Equal(JsonValueKind.Null, errorPayload.GetProperty("file_id").ValueKind);
            Assert.Equal(7, errorPayload.GetProperty("generation").GetInt64());
            Assert.Contains("Song 2 no longer exists", errorPayload.GetProperty("message").GetString());
        }
        finally
        {
            if (serverSocket is not null)
            {
                try { await CloseServerSocketAsync(serverSocket, timeout.Token); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException) { }
                serverSocket.Dispose();
            }
            await terminal.DisposeAsync();
            serverTcpClient?.Dispose();
            listener.Stop();
            if (File.Exists(finishedPath)) File.Delete(finishedPath);
            if (File.Exists(finishedPath + ".tmp")) File.Delete(finishedPath + ".tmp");
            if (File.Exists(outboxPath)) File.Delete(outboxPath);
            if (File.Exists(outboxPath + ".tmp")) File.Delete(outboxPath + ".tmp");
        }
    }

    [Fact]
    public async Task Live_pipe_progress_is_reported_to_the_server_as_absolute_position()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var endpoint = new ServerEndpoint(new Uri($"http://127.0.0.1:{port}/"));
        var handler = new RecordingHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/health" => JsonResponse(HttpStatusCode.OK, "{\"status\":\"UP\",\"service\":\"home-ktv\"}"),
            "/api/queue" => JsonResponse(HttpStatusCode.OK, "null"),
            "/api/songs/1" => JsonResponse(HttpStatusCode.OK,
                "{\"id\":1,\"title\":\"Synthetic\",\"artist\":\"Test\",\"mediaType\":\"VIDEO\",\"hasVocalTrack\":false,\"durationMs\":200000,\"lyricType\":\"none\",\"files\":[{\"id\":7,\"format\":\"rmvb\",\"audioTracks\":1,\"resolution\":\"720x480\",\"priority\":1,\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"},\"ready\":true}]}"),
            _ => new HttpResponseMessage(HttpStatusCode.NotFound),
        });
        var factory = new SimulatedMpvSessionFactory();
        factory.Session.TimePositionSeconds = 3.0d;
        var output = new MpvProcessController(factory);
        var socket = new KtvWebSocketClient(endpoint, "live-pipe-position-client",
            reliableOutboxPath: Path.Combine(AppContext.BaseDirectory, $"live-pipe-outbox-{Guid.NewGuid():N}.json"));
        var terminal = CreateTerminal(endpoint, handler, factory, output, socket);
        var connected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var assignmentReceived = new TaskCompletionSource<PlayerAssignment>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        terminal.ConnectionChanged += isConnected =>
        {
            if (isConnected) connected.TrySetResult();
        };
        socket.PlayerAssignmentReceived += assignment => assignmentReceived.TrySetResult(assignment);
        WebSocket? serverSocket = null;
        TcpClient? serverTcpClient = null;

        try
        {
            await terminal.ConnectAsync(timeout.Token);
            var accepted = await AcceptWebSocketAsync(listener, timeout.Token).WaitAsync(timeout.Token);
            serverSocket = accepted.Socket;
            serverTcpClient = accepted.Client;
            await connected.Task.WaitAsync(timeout.Token);
            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":11,\"lease_ms\":45000}}",
                timeout.Token);
            Assert.Equal(new PlayerAssignment("ACTIVE", 11, 45_000),
                await assignmentReceived.Task.WaitAsync(timeout.Token));

            var snapshot = JsonSerializer.Serialize(new
            {
                type = "sync_full",
                payload = new
                {
                    playing = new
                    {
                        queueId = 5,
                        song = new { id = 1, title = "Synthetic", artist = "Test", mediaType = "VIDEO", hasVocalTrack = false, durationMs = 200_000, lyricType = "none" },
                        orderedByNick = "Test",
                    },
                    list = Array.Empty<object>(),
                    state = "playing",
                    volume = 50,
                    muted = false,
                    vocalMode = "original",
                    audioLayout = new { layout = "NORMAL_STEREO" },
                    tvOnline = true,
                    connectedPhones = 0,
                    positionMs = 120_000,
                    seekSequence = 1,
                    stateRevision = 1,
                    playback = new
                    {
                        kind = "LIVE_TRANSCODE",
                        status = "READY",
                        sourceFileId = 7,
                        variantId = (long?)null,
                        streamUrl = $"http://127.0.0.1:{port}/api/stream/7?transcode=true",
                        audioTracks = 1,
                        audioLayout = new { layout = "NORMAL_STEREO" },
                    },
                },
            });
            await SendTextAsync(serverSocket, snapshot, timeout.Token);
            await WaitForAsync(() => LastLoadedUrl(factory.Session)?.EndsWith(
                "/api/stream/7?transcode=true&start=120.000", StringComparison.Ordinal) == true,
                timeout.Token);
            factory.Session.Notify("start-file", "{\"playlist_entry_id\":21}");
            factory.Session.Notify("file-loaded", "{\"playlist_entry_id\":21}");

            while (true)
            {
                using var message = JsonDocument.Parse(await ReceiveTextAsync(serverSocket, timeout.Token));
                if (message.RootElement.GetProperty("type").GetString() != "progress") continue;
                var payload = message.RootElement.GetProperty("payload");
                Assert.Equal(123_000, payload.GetProperty("position_ms").GetInt64());
                Assert.Equal(5, payload.GetProperty("queue_id").GetInt64());
                Assert.Equal(11, payload.GetProperty("generation").GetInt64());
                Assert.Equal(123_000, terminal.CurrentPositionMs);
                break;
            }
        }
        finally
        {
            if (serverSocket is not null)
            {
                try { await CloseServerSocketAsync(serverSocket, timeout.Token); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException) { }
                serverSocket.Dispose();
            }
            await terminal.DisposeAsync();
            serverTcpClient?.Dispose();
            listener.Stop();
        }
    }

    [Fact]
    public async Task Finished_report_persistence_failure_is_reported_and_fences_the_active_output()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var endpoint = new ServerEndpoint(new Uri(
            $"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/"));
        var blockingFile = Path.Combine(Path.GetTempPath(), $"terminal-finished-blocker-{Guid.NewGuid():N}");
        var finishedPath = Path.Combine(blockingFile, "finished.json");
        var outboxPath = Path.Combine(Path.GetTempPath(), $"terminal-report-{Guid.NewGuid():N}.json");
        await File.WriteAllTextAsync(blockingFile, "synthetic file blocks the pending report directory");

        var handler = new RecordingHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/health" => JsonResponse(HttpStatusCode.OK, "{\"status\":\"UP\",\"service\":\"home-ktv\"}"),
            "/api/queue" => JsonResponse(HttpStatusCode.OK, "null"),
            "/api/control" => JsonResponse(HttpStatusCode.OK, SnapshotPayload(2, 1, 61)),
            "/api/songs/1" => JsonResponse(HttpStatusCode.OK,
                "{\"id\":1,\"title\":\"Synthetic\",\"artist\":\"Test\",\"mediaType\":\"VIDEO\",\"hasVocalTrack\":false,\"durationMs\":10000,\"lyricType\":\"none\",\"files\":[{\"id\":7,\"format\":\"mp4\",\"audioTracks\":1,\"vocalTrackIndex\":0,\"resolution\":\"1920x1080\",\"priority\":1,\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"},\"ready\":true}]}"),
            "/api/playback/resolve/7" => JsonResponse(HttpStatusCode.OK,
                "{\"kind\":\"NATIVE\",\"status\":\"READY\",\"sourceFileId\":7,\"streamUrl\":\"/api/stream/7\",\"audioTracks\":1,\"audioLayout\":{\"layout\":\"NORMAL_STEREO\"}}"),
            _ => new HttpResponseMessage(HttpStatusCode.NotFound),
        });
        var factory = new SimulatedMpvSessionFactory();
        var output = new MpvProcessController(factory);
        var socket = new KtvWebSocketClient(
            endpoint, "finished-persistence-client", reliableOutboxPath: outboxPath,
            pendingFinishedPath: finishedPath);
        var initialAssignment = new TaskCompletionSource<PlayerAssignment>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var standbyAssignment = new TaskCompletionSource<PlayerAssignment>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var resumedAssignment = new TaskCompletionSource<PlayerAssignment>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        socket.PlayerAssignmentReceived += assignment =>
        {
            if (assignment.Role == "ACTIVE" && assignment.Generation == 7)
                initialAssignment.TrySetResult(assignment);
            else if (assignment.Role == "STANDBY")
                standbyAssignment.TrySetResult(assignment);
            else if (assignment.Role == "ACTIVE" && assignment.Generation == 9)
                resumedAssignment.TrySetResult(assignment);
        };
        var terminal = CreateTerminal(endpoint, handler, factory, output, socket);
        var connected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var disconnected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var reportRejected = new TaskCompletionSource<InvalidOperationException>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        terminal.ConnectionChanged += isConnected =>
        {
            if (isConnected) connected.TrySetResult();
            else disconnected.TrySetResult();
        };
        terminal.Error += exception =>
        {
            if (exception is InvalidOperationException invalidOperation
                && invalidOperation.Message.StartsWith("Finished playback report rejected:", StringComparison.Ordinal))
            {
                reportRejected.TrySetResult(invalidOperation);
            }
        };

        WebSocket? serverSocket = null;
        TcpClient? serverTcpClient = null;
        try
        {
            await terminal.ConnectAsync(timeout.Token);
            var session = await AcceptWebSocketAsync(listener, timeout.Token).WaitAsync(timeout.Token);
            serverSocket = session.Socket;
            serverTcpClient = session.Client;
            await connected.Task.WaitAsync(timeout.Token);

            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":7,\"lease_ms\":45000}}",
                timeout.Token);
            Assert.Equal(new PlayerAssignment("ACTIVE", 7, 45_000),
                await initialAssignment.Task.WaitAsync(timeout.Token));
            await SendTextAsync(serverSocket, SnapshotMessage("sync_full", 1, 1), timeout.Token);
            await factory.Session.PlayCommand.Task.WaitAsync(timeout.Token);
            await terminal.SetVolumeAsync(61, cancellationToken: timeout.Token);
            Assert.Equal(61, terminal.CurrentSnapshot!.Volume);
            Assert.Contains(factory.Session.Commands,
                command => command == "[\"set_property\",\"volume\",61]");

            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"STANDBY\",\"generation\":8,\"lease_ms\":45000}}",
                timeout.Token);
            Assert.Equal(new PlayerAssignment("STANDBY", 8, 45_000),
                await standbyAssignment.Task.WaitAsync(timeout.Token));
            await factory.Session.StopCommand.Task.WaitAsync(timeout.Token);
            await WaitForAsync(() => output.CurrentFileId is null, timeout.Token);

            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":9,\"lease_ms\":45000}}",
                timeout.Token);
            Assert.Equal(new PlayerAssignment("ACTIVE", 9, 45_000),
                await resumedAssignment.Task.WaitAsync(timeout.Token));
            await SendTextAsync(serverSocket, SnapshotMessage("sync_full", 3, 1), timeout.Token);
            await WaitForAsync(() => factory.Session.LoadCount == 2, timeout.Token);

            var stopCountBeforeReportFailure = factory.Session.Commands.Count(command => command == "[\"stop\"]");
            factory.Session.Notify("start-file", "{\"playlist_entry_id\":10}");
            factory.Session.Notify("file-loaded", "{\"playlist_entry_id\":10}");
            factory.Session.Notify("end-file", "{\"playlist_entry_id\":10,\"reason\":\"eof\"}");

            var rejection = await reportRejected.Task.WaitAsync(timeout.Token);
            Assert.Equal("Finished playback report rejected: PersistenceFailed.", rejection.Message);
            await WaitForAsync(() => factory.Session.Commands.Count(command => command == "[\"stop\"]")
                > stopCountBeforeReportFailure, timeout.Token);
            Assert.Null(output.CurrentFileId);

            await CloseServerSocketAsync(serverSocket, timeout.Token);
            await disconnected.Task.WaitAsync(timeout.Token);
        }
        finally
        {
            if (serverSocket is not null)
            {
                try { await CloseServerSocketAsync(serverSocket, timeout.Token); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException) { }
                serverSocket.Dispose();
            }
            await terminal.DisposeAsync();
            serverTcpClient?.Dispose();
            listener.Stop();
            foreach (var path in new[] { outboxPath, outboxPath + ".tmp", finishedPath, finishedPath + ".tmp" })
            {
                if (File.Exists(path)) File.Delete(path);
            }
            if (File.Exists(blockingFile)) File.Delete(blockingFile);
        }
    }

    [Fact]
    public async Task SimulatesOneHundredSongsAndOneHundredFastSwitchesWithoutStaleFinalProjection()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(90));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var endpoint = new ServerEndpoint(new Uri($"http://127.0.0.1:{port}/"));
        var handler = new RecordingHandler(request =>
        {
            var path = request.RequestUri!.AbsolutePath;
            if (path == "/api/health")
                return JsonResponse(HttpStatusCode.OK, "{\"status\":\"UP\",\"service\":\"home-ktv\"}");
            if (path == "/api/queue") return JsonResponse(HttpStatusCode.OK, "null");
            if (path.StartsWith("/api/songs/", StringComparison.Ordinal)
                && long.TryParse(path["/api/songs/".Length..], out var songId))
            {
                var fileId = songId + 7_000;
                return JsonResponse(HttpStatusCode.OK, JsonSerializer.Serialize(new
                {
                    id = songId,
                    title = $"Synthetic {songId}",
                    artist = "Test",
                    mediaType = "VIDEO",
                    hasVocalTrack = false,
                    durationMs = 10_000,
                    lyricType = "none",
                    files = new[]
                    {
                        new
                        {
                            id = fileId,
                            format = "mp4",
                            audioTracks = 1,
                            priority = 1,
                            ready = true,
                            audioLayout = new { layout = "NORMAL_STEREO" },
                        },
                    },
                }));
            }
            if (path.StartsWith("/api/playback/resolve/", StringComparison.Ordinal)
                && long.TryParse(path["/api/playback/resolve/".Length..], out var sourceFileId))
            {
                return JsonResponse(HttpStatusCode.OK, JsonSerializer.Serialize(new
                {
                    kind = "NATIVE",
                    status = "READY",
                    sourceFileId,
                    streamUrl = $"/api/stream/{sourceFileId}",
                    audioTracks = 1,
                    audioLayout = new { layout = "NORMAL_STEREO" },
                }));
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var factory = new SimulatedMpvSessionFactory();
        var output = new MpvProcessController(factory);
        var outboxPath = Path.Combine(AppContext.BaseDirectory, $"stress-ws-{Guid.NewGuid():N}.json");
        var finishedPath = Path.Combine(AppContext.BaseDirectory, $"stress-finished-{Guid.NewGuid():N}.json");
        var socket = new KtvWebSocketClient(endpoint, "stress-test-client",
            reliableOutboxPath: outboxPath, pendingFinishedPath: finishedPath);
        var terminal = CreateTerminal(endpoint, handler, factory, output, socket);
        var connected = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var assignmentReceived = new TaskCompletionSource<PlayerAssignment>(TaskCreationOptions.RunContinuationsAsynchronously);
        var errors = new ConcurrentQueue<Exception>();
        terminal.ConnectionChanged += isConnected =>
        {
            if (isConnected) connected.TrySetResult();
        };
        terminal.Error += errors.Enqueue;
        WebSocket? serverSocket = null;
        TcpClient? serverTcpClient = null;

        try
        {
            await terminal.ConnectAsync(timeout.Token);
            var accepted = await AcceptWebSocketAsync(listener, timeout.Token).WaitAsync(timeout.Token);
            serverSocket = accepted.Socket;
            serverTcpClient = accepted.Client;
            await connected.Task.WaitAsync(timeout.Token);
            socket.PlayerAssignmentReceived += assignment => assignmentReceived.TrySetResult(assignment);
            await SendTextAsync(serverSocket,
                "{\"type\":\"player_role\",\"payload\":{\"role\":\"ACTIVE\",\"generation\":19,\"lease_ms\":45000}}",
                timeout.Token);
            Assert.Equal(new PlayerAssignment("ACTIVE", 19, 45_000),
                await assignmentReceived.Task.WaitAsync(timeout.Token));
            await SendTextAsync(serverSocket, SnapshotMessage("sync_full", 1, null), timeout.Token);

            for (var index = 1; index <= 100; index++)
            {
                await SendTextAsync(serverSocket, SnapshotMessage("now_playing", index + 1L, index), timeout.Token);
                await WaitForAsync(() => factory.Session.LoadCount == index, timeout.Token);
                Assert.Equal((long)index, terminal.CurrentSnapshot!.Playing!.QueueId);
            }

            var loadsBeforeFastSwitches = factory.Session.LoadCount;
            for (var index = 101; index <= 200; index++)
            {
                await SendTextAsync(serverSocket,
                    SnapshotMessage("now_playing", index + 1L, index), timeout.Token);
            }

            await WaitForAsync(
                () => terminal.CurrentSnapshot?.Playing?.QueueId == 200
                    && LastLoadedUrl(factory.Session)?.EndsWith("/api/stream/7200", StringComparison.Ordinal) == true,
                timeout.Token);
            Assert.InRange(factory.Session.LoadCount - loadsBeforeFastSwitches, 1, 100);
            Assert.Equal(1, factory.StartCount);
            Assert.True(factory.Session.IsAlive);
            Assert.Empty(errors);

            factory.Session.Notify("start-file", "{\"playlist_entry_id\":200}");
            factory.Session.Notify("file-loaded", "{\"playlist_entry_id\":200}");
            factory.Session.Notify("end-file", "{\"playlist_entry_id\":200,\"reason\":\"eof\"}");
            Assert.Equal(200, await ReceiveFinishedQueueIdAsync(serverSocket, timeout.Token));
            factory.Session.Notify("end-file", "{\"playlist_entry_id\":200,\"reason\":\"eof\"}");
            Assert.False(await HasFinishedWithinAsync(serverSocket, TimeSpan.FromMilliseconds(300)));

            await terminal.DisposeAsync();
            Assert.False(factory.Session.IsAlive);
        }
        finally
        {
            if (serverSocket is not null)
            {
                try { await CloseServerSocketAsync(serverSocket, timeout.Token); }
                catch (Exception exception) when (exception is OperationCanceledException or WebSocketException or IOException) { }
                serverSocket.Dispose();
            }
            await terminal.DisposeAsync();
            serverTcpClient?.Dispose();
            listener.Stop();
            foreach (var path in new[] { outboxPath, outboxPath + ".tmp", finishedPath, finishedPath + ".tmp" })
            {
                if (File.Exists(path)) File.Delete(path);
            }
        }
    }

    private static PlaybackTerminal CreateTerminal(
        ServerEndpoint endpoint,
        HttpMessageHandler handler,
        IMpvSessionFactory factory,
        MpvProcessController? output = null,
        KtvWebSocketClient? socket = null)
    {
        var server = new HttpServerApi(endpoint, "terminal-test-token", handler);
        socket ??= new KtvWebSocketClient(endpoint, "terminal-test-client",
            reliableOutboxPath: Path.Combine(AppContext.BaseDirectory, $"terminal-outbox-{Guid.NewGuid():N}.json"));
        return new PlaybackTerminal(server, socket, output ?? new MpvProcessController(factory));
    }

    private sealed class RecordingHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) => Task.FromResult(responder(request));
    }

    private sealed class RecordingMpvSessionFactory : IMpvDisplaySessionFactory
    {
        public int ScreenIndex { get; private set; }
        public int StartCount { get; private set; }

        public void SetScreenIndex(int screenIndex) => ScreenIndex = screenIndex;

        public Task<IMpvSession> StartAsync(CancellationToken cancellationToken = default)
        {
            StartCount++;
            throw new InvalidOperationException("The terminal test must not start an mpv process.");
        }
    }

    private sealed class SimulatedMpvSessionFactory : IMpvSessionFactory
    {
        public SimulatedMpvSession Session { get; } = new();
        public int StartCount { get; private set; }

        public Task<IMpvSession> StartAsync(CancellationToken cancellationToken = default)
        {
            StartCount++;
            return Task.FromResult<IMpvSession>(Session);
        }
    }

    private sealed class SimulatedMpvSession : IMpvSession
    {
        private bool alive = true;
        private int loadCount;

        public bool IsAlive => alive;
        public int LoadCount => Volatile.Read(ref loadCount);
        public double TimePositionSeconds { get; set; } = 5.125d;
        public ConcurrentQueue<string> Commands { get; } = new();
        public TaskCompletionSource PlayCommand { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource StopCommand { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource RecoveredProjection { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public event Action<MpvNotification>? NotificationReceived;
        public event Action<Exception>? Disconnected;

        public void ReportDisconnected(Exception exception) => Disconnected?.Invoke(exception);

        public void Notify(string name, string json)
        {
            using var document = JsonDocument.Parse(json);
            NotificationReceived?.Invoke(new MpvNotification(name, document.RootElement.Clone()));
        }

        public Task<JsonElement?> ExecuteAsync(
            IReadOnlyList<object?> command,
            CancellationToken cancellationToken = default)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var commandJson = JsonSerializer.Serialize(command);
            Commands.Enqueue(commandJson);
            if (command.Count > 0 && Equals(command[0], "loadfile")
                && Interlocked.Increment(ref loadCount) > 1)
            {
                RecoveredProjection.TrySetResult();
            }
            if (command.Count >= 3 && Equals(command[0], "set_property")
                && Equals(command[1], "pause") && Equals(command[2], false))
            {
                PlayCommand.TrySetResult();
            }
            if (command.Count > 0 && Equals(command[0], "stop")) StopCommand.TrySetResult();

            if (command.Count >= 2 && Equals(command[0], "get_property") && Equals(command[1], "track-list"))
            {
                using var tracks = JsonDocument.Parse("[{\"type\":\"audio\",\"id\":11}]");
                return Task.FromResult<JsonElement?>(tracks.RootElement.Clone());
            }
            if (command.Count >= 2 && Equals(command[0], "get_property") && Equals(command[1], "time-pos"))
            {
                using var position = JsonDocument.Parse(JsonSerializer.Serialize(TimePositionSeconds));
                return Task.FromResult<JsonElement?>(position.RootElement.Clone());
            }

            return Task.FromResult<JsonElement?>(null);
        }

        public ValueTask DisposeAsync()
        {
            alive = false;
            return ValueTask.CompletedTask;
        }
    }

    private static string SnapshotMessage(string eventType, long revision, long? songId, int volume = 50,
        string? state = null, object[]? queue = null, long? playingQueueId = null)
    {
        var playing = songId is null ? null : new
        {
            queueId = playingQueueId ?? songId.Value,
            song = new
            {
                id = songId.Value,
                title = $"Synthetic {songId.Value}",
                artist = "Test",
                mediaType = "VIDEO",
                hasVocalTrack = false,
                durationMs = 10_000,
                lyricType = "none",
            },
            orderedByNick = "Stress test",
        };
        return JsonSerializer.Serialize(new
        {
            type = eventType,
            payload = new
            {
                playing,
                list = queue ?? Array.Empty<object>(),
                state = state ?? (songId is null ? "idle" : "playing"),
                volume,
                muted = false,
                vocalMode = "original",
                audioLayout = new { layout = "NORMAL_STEREO" },
                tvOnline = true,
                connectedPhones = 0,
                positionMs = 0,
                seekSequence = 0,
                stateRevision = revision,
            },
        });
    }

    private static string SnapshotPayload(long revision, long? songId, int volume)
    {
        using var document = JsonDocument.Parse(SnapshotMessage("sync_full", revision, songId, volume));
        return document.RootElement.GetProperty("payload").GetRawText();
    }

    private static async Task WaitForAsync(Func<bool> condition, CancellationToken cancellationToken)
    {
        while (!condition()) await Task.Delay(10, cancellationToken);
    }

    private static string? LastLoadedUrl(SimulatedMpvSession session)
    {
        foreach (var command in session.Commands.ToArray().Reverse())
        {
            using var document = JsonDocument.Parse(command);
            var values = document.RootElement;
            if (values.ValueKind == JsonValueKind.Array
                && values.GetArrayLength() > 1
                && values[0].GetString() == "loadfile") return values[1].GetString();
        }
        return null;
    }

    private static async Task<long> ReceiveFinishedQueueIdAsync(WebSocket socket, CancellationToken cancellationToken)
    {
        while (true)
        {
            using var message = JsonDocument.Parse(await ReceiveTextAsync(socket, cancellationToken));
            var root = message.RootElement;
            if (root.GetProperty("type").GetString() == "finished")
                return root.GetProperty("payload").GetProperty("queue_id").GetInt64();
        }
    }

    private static async Task<bool> HasFinishedWithinAsync(WebSocket socket, TimeSpan duration)
    {
        using var timeout = new CancellationTokenSource(duration);
        try
        {
            while (true)
            {
                using var message = JsonDocument.Parse(await ReceiveTextAsync(socket, timeout.Token));
                if (message.RootElement.GetProperty("type").GetString() == "finished") return true;
            }
        }
        catch (OperationCanceledException) when (timeout.IsCancellationRequested)
        {
            return false;
        }
    }

    private static HttpResponseMessage JsonResponse(HttpStatusCode status, string json) => new(status)
    {
        Content = new StringContent(json, Encoding.UTF8, "application/json"),
    };

    private static async Task<(WebSocket Socket, TcpClient Client)> AcceptWebSocketAsync(
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

        var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(
            headers["Sec-WebSocket-Key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
        var response = Encoding.ASCII.GetBytes(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            $"Sec-WebSocket-Accept: {accept}\r\n\r\n");
        await stream.WriteAsync(response, cancellationToken);
        var socket = WebSocket.CreateFromStream(stream, isServer: true, subProtocol: null,
            keepAliveInterval: Timeout.InfiniteTimeSpan);
        Assert.StartsWith("GET /ws?client_type=tv&", requestLine, StringComparison.Ordinal);
        return (socket, tcpClient);
    }

    private static Task SendTextAsync(WebSocket socket, string text, CancellationToken cancellationToken)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        return socket.SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, cancellationToken);
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
                throw new WebSocketException("Client closed before sending the simulated completion report.");
            message.Write(buffer, 0, result.Count);
        } while (!result.EndOfMessage);

        return Encoding.UTF8.GetString(message.ToArray());
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
}
