using System.Collections.Concurrent;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using HomeKtv.Windows.Protocol;
using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.ServerConnection;

public sealed class KtvWebSocketClient : IAsyncDisposable
{
    private static readonly HashSet<string> SnapshotEvents = new(StringComparer.Ordinal)
    {
        "sync_full",
        "queue_updated",
        "now_playing",
        "player_state",
        "playback_restarted",
        "playback_seeked",
        "volume_changed",
        "vocal_changed",
    };

    private readonly ServerEndpoint endpoint;
    private readonly string clientToken;
    private readonly string? playerCredential;
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly SemaphoreSlim finishedFlushLock = new(1, 1);
    private readonly ReliablePlaybackOutbox reliableMessages;
    private readonly ReliablePlaybackOutboxStore reliableMessageStore;
    private readonly PendingPlaybackReportStore pendingFinishedStore;
    private readonly PendingPlaybackReportQueue pendingFinishedReports;
    private readonly SyncChunkAssembler syncChunkAssembler = new();
    private readonly CancellationTokenSource lifetime = new();
    private ClientWebSocket? socket;
    private long generation;

    public KtvWebSocketClient(
        ServerEndpoint endpoint,
        string clientToken,
        string? playerCredential = null,
        string? reliableOutboxPath = null)
    {
        this.endpoint = endpoint;
        this.clientToken = clientToken;
        this.playerCredential = playerCredential;
        reliableMessageStore = new(
            reliableOutboxPath,
            scope: $"{endpoint.BaseUri.AbsoluteUri}|{clientToken}");
        reliableMessages = new(reliableMessageStore.Load(), reliableMessageStore.Save);
        pendingFinishedStore = new(
            scope: $"{endpoint.BaseUri.AbsoluteUri}|{clientToken}");
        pendingFinishedReports = new(
            pendingFinishedStore.Load(),
            pendingFinishedStore.Save);
    }

    public event Action<bool>? ConnectionChanged;
    public event Func<string, QueueSnapshot, CancellationToken, Task>? SnapshotReceived;
    public event Action<long>? ProgressReceived;
    public event Action<Exception>? ConnectionError;
    public event Action<PlayerAssignment>? PlayerAssignmentReceived;
    public event Action<string, string>? ReliableMessageRejected;

    public bool IsConnected => socket?.State == WebSocketState.Open;

    public async Task RunAsync(CancellationToken cancellationToken = default)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, lifetime.Token);
        var attempt = 0;
        while (!linked.IsCancellationRequested)
        {
            try
            {
                await ConnectAndReceiveAsync(linked.Token).ConfigureAwait(false);
                attempt = 0;
            }
            catch (OperationCanceledException) when (linked.IsCancellationRequested)
            {
                break;
            }
            catch (Exception exception)
            {
                ConnectionError?.Invoke(exception);
            }
            finally
            {
                pendingFinishedReports.OnDisconnected();
                syncChunkAssembler.Reset();
                Volatile.Write(ref generation, 0);
                ConnectionChanged?.Invoke(false);
                socket = null;
            }

            if (linked.IsCancellationRequested) break;
            await Task.Delay(ReconnectPolicy.DelayMilliseconds(attempt), linked.Token).ConfigureAwait(false);
            attempt++;
        }
    }

    public Task SendProgressAsync(long positionMs, long? queueId = null, long? activeGeneration = null,
        CancellationToken cancellationToken = default) =>
        SendTextAsync(ServerMessageFactory.Progress(positionMs, queueId, activeGeneration), cancellationToken);

    public async Task<PendingPlaybackReportEnqueueResult> SendFinishedAsync(long queueId, long? activeGeneration = null,
        CancellationToken cancellationToken = default)
    {
        _ = activeGeneration; // The queue is serialized with the current lease generation at flush time.
        var result = pendingFinishedReports.Enqueue(queueId);
        if (result is PendingPlaybackReportEnqueueResult.Invalid
            or PendingPlaybackReportEnqueueResult.Full
            or PendingPlaybackReportEnqueueResult.PersistenceFailed)
        {
            return result;
        }

        await FlushFinishedReportsAsync(cancellationToken).ConfigureAwait(false);
        return result;
    }

    public Task<ReliableSendResult> SendPlayErrorAsync(
        long queueId,
        long? fileId,
        string message,
        long? activeGeneration = null,
        CancellationToken cancellationToken = default)
    {
        var text = ServerMessageFactory.PlayError(queueId, fileId, message, activeGeneration);
        return SendReliableAsync(
            new ReliableMessage($"play_error:{queueId}", text, 0,
                new ReliablePlayError(queueId, fileId, message)), cancellationToken);
    }

    private async Task ConnectAndReceiveAsync(CancellationToken cancellationToken)
    {
        syncChunkAssembler.Reset();
        using var connectedSocket = new ClientWebSocket
        {
            Options = { KeepAliveInterval = Timeout.InfiniteTimeSpan },
        };
        await connectedSocket.ConnectAsync(endpoint.WebSocketUri(clientToken, playerCredential), cancellationToken).ConfigureAwait(false);
        socket = connectedSocket;
        await FlushReliableMessagesAsync(connectedSocket, cancellationToken).ConfigureAwait(false);
        ConnectionChanged?.Invoke(true);

        using var heartbeatCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var heartbeat = SendHeartbeatsAsync(heartbeatCancellation.Token);
        try
        {
            await ReceiveLoopAsync(connectedSocket, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            heartbeatCancellation.Cancel();
            try { await heartbeat.ConfigureAwait(false); } catch (OperationCanceledException) { }
            if (connectedSocket.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                await connectedSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "reconnect",
                    CancellationToken.None).ConfigureAwait(false);
            }
        }
    }

    private async Task SendHeartbeatsAsync(CancellationToken cancellationToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(15));
        while (await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false))
        {
            var currentGeneration = Volatile.Read(ref generation);
            if (currentGeneration > 0)
            {
                await FlushFinishedReportsAsync(cancellationToken).ConfigureAwait(false);
            }
            await SendTextAsync(ServerMessageFactory.Ping(currentGeneration > 0 ? currentGeneration : null),
                cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task ReceiveLoopAsync(ClientWebSocket connectedSocket, CancellationToken cancellationToken)
    {
        var buffer = new byte[16 * 1024];
        while (connectedSocket.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
        {
            using var message = new MemoryStream();
            WebSocketReceiveResult result;
            var messageBytes = 0;
            do
            {
                result = await connectedSocket.ReceiveAsync(buffer, cancellationToken).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close) return;
                if (result.Count > MaxWebSocketMessageBytes - messageBytes)
                {
                    throw new WebSocketException("WebSocket message exceeded the size limit.");
                }
                message.Write(buffer, 0, result.Count);
                messageBytes += result.Count;
            } while (!result.EndOfMessage);

            await DispatchAsync(connectedSocket, Encoding.UTF8.GetString(message.ToArray()), messageBytes, cancellationToken)
                .ConfigureAwait(false);
        }
    }

    private async Task DispatchAsync(ClientWebSocket connectedSocket, string json, int wireBytes,
        CancellationToken cancellationToken)
    {
        WsMessage? message;
        try
        {
            message = ProtocolJson.Deserialize<WsMessage>(json);
        }
        catch (JsonException)
        {
            return;
        }

        if (message is null) return;
        if (message.Type == "playback_report_ack"
            && message.Payload is { } acknowledgement
            && acknowledgement.TryGetProperty("queue_id", out var acknowledgedQueueId)
            && acknowledgedQueueId.TryGetInt64(out var queueId)
            && queueId > 0
            && acknowledgement.TryGetProperty("status", out var acknowledgementStatus)
            && acknowledgementStatus.ValueKind == JsonValueKind.String)
        {
            var status = acknowledgementStatus.GetString();
            var reportType = acknowledgement.TryGetProperty("report_type", out var reportTypeValue)
                && reportTypeValue.ValueKind == JsonValueKind.String
                ? reportTypeValue.GetString()
                : null;
            var targets = PlaybackReportAckTargets.For(reportType);
            if (targets.Finished) pendingFinishedReports.Acknowledge(queueId, status);
            if (targets.PlayError) reliableMessages.Acknowledge($"play_error:{queueId}", status);
            return;
        }
        if ((message.Type == "player_role" || message.Type == "pong")
            && message.Payload is { } rolePayload
            && rolePayload.TryGetProperty("role", out var role)
            && rolePayload.TryGetProperty("generation", out var assignedGeneration)
            && rolePayload.TryGetProperty("lease_ms", out var leaseMs))
        {
            var assignment = new PlayerAssignment(
                role.GetString() ?? "STANDBY", assignedGeneration.GetInt64(), leaseMs.GetInt64());
            Volatile.Write(ref generation,
                string.Equals(assignment.Role, "ACTIVE", StringComparison.OrdinalIgnoreCase)
                    ? assignment.Generation : 0);
            PlayerAssignmentReceived?.Invoke(assignment);
            await FlushFinishedReportsAsync(cancellationToken).ConfigureAwait(false);
            await FlushReliableMessagesAsync(connectedSocket, cancellationToken).ConfigureAwait(false);
            return;
        }
        if (message.Type == "snapshot_chunk")
        {
            var chunk = message.Payload is { } chunkPayload
                ? chunkPayload.Deserialize<QueueSnapshotChunk>(ProtocolJson.Options)
                : null;
            var assembled = chunk is null ? null : syncChunkAssembler.Accept(chunk, wireBytes);
            if (assembled is not null && SnapshotReceived is { } chunkHandler)
            {
                await chunkHandler(assembled.EventType, assembled.Snapshot, cancellationToken)
                    .ConfigureAwait(false);
            }
            return;
        }
        if (SnapshotEvents.Contains(message.Type))
        {
            syncChunkAssembler.Reset();
            var snapshot = message.Payload is { } payload
                ? payload.Deserialize<QueueSnapshot>(ProtocolJson.Options)
                : null;
            if (snapshot is not null && SnapshotReceived is { } handler)
            {
                await handler(message.Type, snapshot, cancellationToken).ConfigureAwait(false);
            }
            return;
        }

        if (message.Type == "progress" && message.Payload is { } progress
            && progress.TryGetProperty("position_ms", out var position))
        {
            ProgressReceived?.Invoke(Math.Max(0, position.GetInt64()));
        }
    }

    private async Task SendTextAsync(string text, CancellationToken cancellationToken)
    {
        var active = socket;
        if (active?.State != WebSocketState.Open) return;
        var bytes = EncodeBounded(text);
        if (bytes is null) return;
        await sendLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (active.State == WebSocketState.Open)
            {
                await active.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken)
                    .ConfigureAwait(false);
            }
        }
        finally
        {
            sendLock.Release();
        }
    }

    private async Task<ReliableSendResult> SendReliableAsync(
        ReliableMessage message,
        CancellationToken cancellationToken)
    {
        var currentGeneration = Volatile.Read(ref generation);
        var text = currentGeneration > 0 ? message.Serialize(currentGeneration) : message.Text;
        var byteCount = Utf8ByteBudget.GetByteCountAtMost(text, MaxWebSocketMessageBytes);
        if (byteCount is null)
        {
            NotifyReliableRejected(message.Key, "message exceeds websocket byte budget");
            return ReliableSendResult.Rejected;
        }

        message = message with { Text = text, Utf8Bytes = byteCount.Value };
        var queued = reliableMessages.Enqueue(message);
        if (queued == ReliableEnqueueResult.Rejected)
        {
            NotifyReliableRejected(message.Key, "reliable outbox is full or persistence failed");
            return ReliableSendResult.Rejected;
        }

        var active = socket;
        if (active?.State != WebSocketState.Open || currentGeneration <= 0)
        {
            return queued == ReliableEnqueueResult.AlreadyQueued
                ? ReliableSendResult.AlreadyQueued
                : ReliableSendResult.Queued;
        }

        try
        {
            var bytes = Utf8ByteBudget.EncodeBounded(message.Text, MaxWebSocketMessageBytes)
                ?? throw new WebSocketException("WebSocket message exceeded the size limit.");
            await SendOnSocketAsync(active, bytes, cancellationToken).ConfigureAwait(false);
            return ReliableSendResult.Sent;
        }
        catch (Exception exception) when (exception is WebSocketException or IOException
            or ObjectDisposedException)
        {
            return queued == ReliableEnqueueResult.AlreadyQueued
                ? ReliableSendResult.AlreadyQueued
                : ReliableSendResult.Queued;
        }
    }

    private async Task<bool> FlushFinishedReportsAsync(CancellationToken cancellationToken)
    {
        var active = socket;
        var currentGeneration = Volatile.Read(ref generation);
        if (active?.State != WebSocketState.Open || currentGeneration <= 0) return false;

        await finishedFlushLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var sent = false;
            foreach (var report in pendingFinishedReports.Snapshot())
            {
                try
                {
                    await SendOnSocketAsync(active, report.Serialize(currentGeneration), cancellationToken)
                        .ConfigureAwait(false);
                    sent = true;
                }
                catch (Exception exception) when (exception is WebSocketException or IOException
                    or ObjectDisposedException)
                {
                    return sent;
                }
            }

            return sent;
        }
        finally
        {
            finishedFlushLock.Release();
        }
    }

    private async Task FlushReliableMessagesAsync(
        ClientWebSocket active,
        CancellationToken cancellationToken)
    {
        var currentGeneration = Volatile.Read(ref generation);
        if (currentGeneration <= 0) return;

        foreach (var message in reliableMessages.Snapshot())
        {
            var text = message.Serialize(currentGeneration);
            if (Utf8ByteBudget.GetByteCountAtMost(text, MaxWebSocketMessageBytes) is null)
            {
                if (reliableMessages.Remove(message.Key))
                {
                    NotifyReliableRejected(message.Key, "historical message exceeds websocket byte budget");
                }
                continue;
            }

            var bytes = Utf8ByteBudget.EncodeBounded(text, MaxWebSocketMessageBytes)
                ?? throw new WebSocketException("WebSocket message exceeded the size limit.");
            await SendOnSocketAsync(active, bytes, cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task SendOnSocketAsync(
        ClientWebSocket active,
        string text,
        CancellationToken cancellationToken)
    {
        var bytes = EncodeBounded(text)
            ?? throw new WebSocketException("WebSocket message exceeded the size limit.");
        await SendOnSocketAsync(active, bytes, cancellationToken).ConfigureAwait(false);
    }

    private async Task SendOnSocketAsync(
        ClientWebSocket active,
        byte[] bytes,
        CancellationToken cancellationToken)
    {
        await sendLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (active.State != WebSocketState.Open)
            {
                throw new WebSocketException("WebSocket is no longer open.");
            }

            await active.SendAsync(bytes, WebSocketMessageType.Text, true, cancellationToken)
                .ConfigureAwait(false);
        }
        finally
        {
            sendLock.Release();
        }
    }

    private static byte[]? EncodeBounded(string text)
    {
        return Utf8ByteBudget.EncodeBounded(text, MaxWebSocketMessageBytes);
    }

    private void NotifyReliableRejected(string key, string reason)
    {
        ReliableMessageRejected?.Invoke(key, reason);
    }

    private const int MaxWebSocketMessageBytes = Utf8ByteBudget.MaxMessageBytes;

    public async ValueTask DisposeAsync()
    {
        lifetime.Cancel();
        var active = socket;
        if (active?.State is WebSocketState.Open or WebSocketState.CloseReceived)
        {
            try
            {
                await active.CloseAsync(WebSocketCloseStatus.NormalClosure, "client closing",
                    CancellationToken.None).ConfigureAwait(false);
            }
            catch (WebSocketException) { }
        }
        lifetime.Dispose();
        sendLock.Dispose();
        finishedFlushLock.Dispose();
    }
}
