using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Text;

namespace HomeKtv.Windows.Mpv;

public sealed class MpvJsonIpcSession : IMpvSession
{
    private readonly NamedPipeClientStream pipe;
    private readonly Process process;
    private readonly StreamReader reader;
    private readonly StreamWriter writer;
    private readonly SemaphoreSlim writeLock = new(1, 1);
    private readonly ConcurrentDictionary<long, TaskCompletionSource<System.Text.Json.JsonElement?>> pending = new();
    private readonly CancellationTokenSource lifetime = new();
    private readonly Task receiveLoop;
    private long requestId;
    private int disposed;

    public MpvJsonIpcSession(NamedPipeClientStream pipe, Process process)
    {
        this.pipe = pipe;
        this.process = process;
        reader = new StreamReader(pipe, Encoding.UTF8, detectEncodingFromByteOrderMarks: false,
            bufferSize: 16 * 1024, leaveOpen: true);
        writer = new StreamWriter(pipe, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false),
            bufferSize: 16 * 1024, leaveOpen: true)
        {
            AutoFlush = true,
        };
        receiveLoop = ReceiveLoopAsync();
    }

    public bool IsAlive => Volatile.Read(ref disposed) == 0
        && pipe.IsConnected
        && !process.HasExited;

    public event Action<MpvNotification>? NotificationReceived;
    public event Action<Exception>? Disconnected;

    public async Task<System.Text.Json.JsonElement?> ExecuteAsync(
        IReadOnlyList<object?> command,
        CancellationToken cancellationToken = default)
    {
        if (!IsAlive)
        {
            throw new MpvConnectionException("mpv IPC is not connected.");
        }

        var id = Interlocked.Increment(ref requestId);
        var completion = new TaskCompletionSource<System.Text.Json.JsonElement?>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        if (!pending.TryAdd(id, completion))
        {
            throw new MpvConnectionException($"Unable to register mpv IPC request {id}.");
        }

        try
        {
            await writeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                await writer.WriteLineAsync(MpvIpcProtocol.SerializeCommand(id, command))
                    .ConfigureAwait(false);
                await writer.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                writeLock.Release();
            }

            return await completion.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (MpvCommandException)
        {
            throw;
        }
        catch (Exception exception) when (exception is IOException or ObjectDisposedException)
        {
            throw new MpvConnectionException("mpv IPC command failed.", exception);
        }
        finally
        {
            pending.TryRemove(id, out _);
        }
    }

    private async Task ReceiveLoopAsync()
    {
        Exception? failure = null;
        try
        {
            while (!lifetime.IsCancellationRequested
                && await reader.ReadLineAsync(lifetime.Token).ConfigureAwait(false) is { } line)
            {
                if (!MpvIpcProtocol.TryParseMessage(line, out var message) || message is null)
                {
                    continue;
                }

                if (message.RequestId is { } id && pending.TryRemove(id, out var completion))
                {
                    if (!string.Equals(message.Error, "success", StringComparison.OrdinalIgnoreCase))
                    {
                        completion.TrySetException(new MpvCommandException(
                            $"mpv command failed: {message.Error ?? "unknown error"}"));
                    }
                    else
                    {
                        completion.TrySetResult(message.Data);
                    }

                    continue;
                }

                if (!string.IsNullOrWhiteSpace(message.Event))
                {
                    NotificationReceived?.Invoke(new MpvNotification(message.Event!, message.EventData));
                }
            }

            if (!lifetime.IsCancellationRequested)
            {
                failure = new MpvConnectionException("mpv IPC pipe closed.");
            }
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested)
        {
        }
        catch (Exception exception)
        {
            failure = exception is MpvConnectionException
                ? exception
                : new MpvConnectionException("mpv IPC receive loop failed.", exception);
        }

        if (failure is not null && !lifetime.IsCancellationRequested)
        {
            foreach (var item in pending.Values)
            {
                item.TrySetException(failure);
            }

            pending.Clear();
            Disconnected?.Invoke(failure);
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0)
        {
            return;
        }

        lifetime.Cancel();
        foreach (var item in pending.Values)
        {
            item.TrySetException(new MpvConnectionException("mpv IPC session was disposed."));
        }

        pending.Clear();
        try { pipe.Dispose(); } catch (IOException) { }
        try { writer.Dispose(); } catch (ObjectDisposedException) { }
        try { reader.Dispose(); } catch (ObjectDisposedException) { }
        try { await receiveLoop.ConfigureAwait(false); } catch (Exception) { }
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (InvalidOperationException) { }
        catch (System.ComponentModel.Win32Exception) { }
        process.Dispose();
        writeLock.Dispose();
        lifetime.Dispose();
    }
}
