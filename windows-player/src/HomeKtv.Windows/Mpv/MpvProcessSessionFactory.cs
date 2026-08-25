using System.Diagnostics;
using System.IO;
using System.IO.Pipes;

namespace HomeKtv.Windows.Mpv;

public sealed record MpvLaunchOptions(
    string ExecutablePath = "mpv.exe",
    int ScreenIndex = 0,
    bool Fullscreen = true);

public sealed class MpvProcessSessionFactory : IMpvSessionFactory
{
    private readonly MpvLaunchOptions options;

    public MpvProcessSessionFactory(MpvLaunchOptions? options = null)
    {
        this.options = options ?? new MpvLaunchOptions();
    }

    public async Task<IMpvSession> StartAsync(CancellationToken cancellationToken = default)
    {
        var pipeName = $"home-ktv-{Guid.NewGuid():N}";
        var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        var process = new Process
        {
            StartInfo = CreateStartInfo(pipeName),
            EnableRaisingEvents = true,
        };

        try
        {
            if (!process.Start())
            {
                throw new MpvConnectionException("Unable to start mpv.exe.");
            }

            await pipe.ConnectAsync(5_000, cancellationToken).ConfigureAwait(false);
            return new MpvJsonIpcSession(pipe, process);
        }
        catch (OperationCanceledException)
        {
            pipe.Dispose();
            DisposeProcess(process);
            throw;
        }
        catch (Exception exception) when (exception is MpvConnectionException or IOException or TimeoutException
            or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            pipe.Dispose();
            DisposeProcess(process);
            throw new MpvConnectionException("Unable to connect to mpv IPC.", exception);
        }
    }

    private ProcessStartInfo CreateStartInfo(string pipeName)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = options.ExecutablePath,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        startInfo.ArgumentList.Add("--no-config");
        startInfo.ArgumentList.Add("--no-terminal");
        startInfo.ArgumentList.Add("--really-quiet");
        startInfo.ArgumentList.Add("--idle=yes");
        startInfo.ArgumentList.Add("--force-window=immediate");
        startInfo.ArgumentList.Add("--input-ipc-server=\\\\.\\pipe\\" + pipeName);
        startInfo.ArgumentList.Add($"--fs-screen={Math.Max(0, options.ScreenIndex)}");
        startInfo.ArgumentList.Add($"--fullscreen={(options.Fullscreen ? "yes" : "no")}");
        startInfo.ArgumentList.Add("--osd-level=0");
        return startInfo;
    }

    private static void DisposeProcess(Process process)
    {
        try
        {
            if (!process.HasExited) process.Kill(entireProcessTree: true);
        }
        catch (InvalidOperationException) { }
        catch (System.ComponentModel.Win32Exception) { }
        process.Dispose();
    }
}
