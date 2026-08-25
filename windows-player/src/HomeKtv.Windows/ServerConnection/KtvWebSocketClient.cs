using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using HomeKtv.Windows.Protocol;

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
    private readonly SemaphoreSlim sendLock = new(1, 1);
    private readonly CancellationTokenSource lifetime = new();
    private ClientWebSocket? socket;

    public KtvWebSocketClient(ServerEndpoint endpoint, string clientToken)
    {
        this.endpoint = endpoint;
        this.clientToken = clientToken;
    }

    public event Action<bool>? ConnectionChanged;
    public event Func<string, QueueSnapshot, CancellationToken, Task>? SnapshotReceived;
    public event Action<long>? ProgressReceived;
    public event Action<Exception>? ConnectionError;

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
                ConnectionChanged?.Invoke(false);
                socket = null;
            }

            if (linked.IsCancellationRequested) break;
            await Task.Delay(ReconnectPolicy.DelayMilliseconds(attempt), linked.Token).ConfigureAwait(false);
            attempt++;
        }
    }

    public Task SendProgressAsync(long positionMs, long? queueId = null, CancellationToken cancellationToken = default) =>
        SendTextAsync(ServerMessageFactory.Progress(positionMs, queueId), cancellationToken);

    public Task SendFinishedAsync(long queueId, CancellationToken cancellationToken = default) =>
        SendTextAsync(ServerMessageFactory.Finished(queueId), cancellationToken);

    public Task SendPlayErrorAsync(long queueId, long? fileId, string message,
        CancellationToken cancellationToken = default) =>
        SendTextAsync(ServerMessageFactory.PlayError(queueId, fileId, message), cancellationToken);

    private async Task ConnectAndReceiveAsync(CancellationToken cancellationToken)
    {
        using var connectedSocket = new ClientWebSocket
        {
            Options = { KeepAliveInterval = Timeout.InfiniteTimeSpan },
        };
        await connectedSocket.ConnectAsync(endpoint.WebSocketUri(clientToken), cancellationToken).ConfigureAwait(false);
        socket = connectedSocket;
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
            await SendTextAsync(ServerMessageFactory.Ping(), cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task ReceiveLoopAsync(ClientWebSocket connectedSocket, CancellationToken cancellationToken)
    {
        var buffer = new byte[16 * 1024];
        while (connectedSocket.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
        {
            using var message = new MemoryStream();
            WebSocketReceiveResult result;
            do
            {
                result = await connectedSocket.ReceiveAsync(buffer, cancellationToken).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close) return;
                message.Write(buffer, 0, result.Count);
            } while (!result.EndOfMessage);

            await DispatchAsync(Encoding.UTF8.GetString(message.ToArray()), cancellationToken)
                .ConfigureAwait(false);
        }
    }

    private async Task DispatchAsync(string json, CancellationToken cancellationToken)
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
        if (SnapshotEvents.Contains(message.Type))
        {
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
        var bytes = Encoding.UTF8.GetBytes(text);
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
    }
}
