using System.Text;

namespace HomeKtv.Windows.ServerConnection;

/** Performs the cheap byte-count check before allocating a UTF-8 payload buffer. */
public static class Utf8ByteBudget
{
    public const int MaxMessageBytes = 1 * 1024 * 1024;
    private static readonly UTF8Encoding Utf8 = new(encoderShouldEmitUTF8Identifier: false);

    public static int? GetByteCountAtMost(string? text, int maxBytes)
    {
        if (text is null || maxBytes < 0) return null;
        var byteCount = Utf8.GetByteCount(text);
        return byteCount <= maxBytes ? byteCount : null;
    }

    public static byte[]? EncodeBounded(string? text, int maxBytes)
    {
        var byteCount = GetByteCountAtMost(text, maxBytes);
        if (byteCount is null || text is null) return null;

        var bytes = new byte[byteCount.Value];
        Utf8.GetBytes(text, bytes);
        return bytes;
    }

    public static string TruncateToByteLimit(string? text, int maxBytes)
    {
        if (string.IsNullOrEmpty(text) || maxBytes <= 0) return string.Empty;
        if (GetByteCountAtMost(text, maxBytes) is not null) return text;

        var builder = new StringBuilder(Math.Min(text.Length, maxBytes));
        var bytes = 0;
        for (var index = 0; index < text.Length;)
        {
            var charCount = 1;
            var codePoint = (int)text[index];
            if (char.IsHighSurrogate(text[index])
                && index + 1 < text.Length
                && char.IsLowSurrogate(text[index + 1]))
            {
                codePoint = char.ConvertToUtf32(text[index], text[index + 1]);
                charCount = 2;
            }

            var codePointBytes = codePoint switch
            {
                <= 0x7f => 1,
                <= 0x7ff => 2,
                <= 0xffff => 3,
                _ => 4,
            };
            if (bytes + codePointBytes > maxBytes) break;
            builder.Append(text, index, charCount);
            bytes += codePointBytes;
            index += charCount;
        }
        return builder.ToString();
    }
}
