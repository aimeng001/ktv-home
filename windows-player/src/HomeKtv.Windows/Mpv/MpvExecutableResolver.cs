using System.IO;

namespace HomeKtv.Windows.Mpv;

public static class MpvExecutableResolver
{
    public static string Resolve(
        string? configuredPath,
        string? applicationDirectory = null,
        Func<string, bool>? fileExists = null)
    {
        var requestedPath = string.IsNullOrWhiteSpace(configuredPath)
            ? "mpv.exe"
            : configuredPath.Trim();

        if (Path.IsPathRooted(requestedPath))
        {
            return requestedPath;
        }

        var baseDirectory = string.IsNullOrWhiteSpace(applicationDirectory)
            ? AppContext.BaseDirectory
            : applicationDirectory;
        var adjacentPath = Path.GetFullPath(Path.Combine(baseDirectory, requestedPath));
        var exists = fileExists ?? File.Exists;

        // A release package may carry mpv.exe beside the player. If it does not,
        // return the configured name so normal Windows PATH lookup still works.
        return exists(adjacentPath) ? adjacentPath : requestedPath;
    }
}
