using System.Net.Http;
using System.IO;

namespace HomeKtv.Windows.ServerConnection;

/** Reads HTTP bodies without trusting Content-Length or buffering the peer's whole response. */
public static class BoundedHttpContentReader
{
    public const long MaxAssetBytes = 10L * 1024L * 1024L;
    public const long MaxJsonBytes = 4L * 1024L * 1024L;
    public const long MaxErrorBytes = 64L * 1024L;

    public static async Task<byte[]?> ReadBytesAsync(
        HttpContent content,
        long maxBytes,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(content);
        var declaredLength = content.Headers.ContentLength;
        if (declaredLength is not null && declaredLength.Value > maxBytes) return null;
        if (maxBytes <= 0 || maxBytes > int.MaxValue) return null;

        await using var input = await content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var output = new MemoryStream(capacity: (int)Math.Min(maxBytes, 64 * 1024));
        var buffer = new byte[64 * 1024];
        long total = 0;
        while (true)
        {
            var count = await input.ReadAsync(buffer.AsMemory(), cancellationToken).ConfigureAwait(false);
            if (count == 0) break;
            total += count;
            if (total > maxBytes) return null;
            await output.WriteAsync(buffer.AsMemory(0, count), cancellationToken).ConfigureAwait(false);
        }

        return output.ToArray();
    }

    public static async Task<string?> ReadTextAsync(
        HttpContent content,
        long maxBytes,
        CancellationToken cancellationToken = default)
    {
        var bytes = await ReadBytesAsync(content, maxBytes, cancellationToken).ConfigureAwait(false);
        return bytes is null ? null : System.Text.Encoding.UTF8.GetString(bytes);
    }
}
