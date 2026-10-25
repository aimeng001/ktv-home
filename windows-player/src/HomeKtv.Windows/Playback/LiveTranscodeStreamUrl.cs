using System.Globalization;

namespace HomeKtv.Windows.Playback;

/** URL operations for the server's pipe-backed live-transcode transport. */
internal static class LiveTranscodeStreamUrl
{
    public static bool IsLiveTranscode(string? streamUrl) =>
        FindQueryValue(streamUrl, "transcode") is { } value
        && string.Equals(value, "true", StringComparison.OrdinalIgnoreCase);

    public static long StartPositionMs(string? streamUrl)
    {
        if (!IsLiveTranscode(streamUrl)
            || FindQueryValue(streamUrl, "start") is not { } value
            || !decimal.TryParse(value, NumberStyles.AllowLeadingSign | NumberStyles.AllowDecimalPoint,
                CultureInfo.InvariantCulture, out var seconds)
            || seconds < 0)
        {
            return 0;
        }

        if (seconds >= long.MaxValue / 1000m) return long.MaxValue;
        var milliseconds = decimal.Round(seconds * 1000m, 0, MidpointRounding.AwayFromZero);
        return milliseconds >= long.MaxValue ? long.MaxValue : (long)milliseconds;
    }

    public static string WithStart(string streamUrl, long positionMs)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(streamUrl);
        var fragmentIndex = streamUrl.IndexOf('#');
        var fragment = fragmentIndex >= 0 ? streamUrl[fragmentIndex..] : string.Empty;
        var withoutFragment = fragmentIndex >= 0 ? streamUrl[..fragmentIndex] : streamUrl;
        var queryIndex = withoutFragment.IndexOf('?');
        var path = queryIndex >= 0 ? withoutFragment[..queryIndex] : withoutFragment;
        var query = queryIndex >= 0 ? withoutFragment[(queryIndex + 1)..] : string.Empty;
        var parameters = query.Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Where(parameter => !IsStartParameter(parameter))
            .ToList();
        var seconds = Math.Max(0, positionMs) / 1000m;
        parameters.Add($"start={seconds.ToString("0.000", CultureInfo.InvariantCulture)}");
        return $"{path}?{string.Join('&', parameters)}{fragment}";
    }

    private static string? FindQueryValue(string? streamUrl, string key)
    {
        if (string.IsNullOrWhiteSpace(streamUrl)) return null;
        var queryStart = streamUrl.IndexOf('?');
        if (queryStart < 0) return null;
        var fragmentStart = streamUrl.IndexOf('#', queryStart + 1);
        var query = fragmentStart >= 0
            ? streamUrl[(queryStart + 1)..fragmentStart]
            : streamUrl[(queryStart + 1)..];
        foreach (var parameter in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var separator = parameter.IndexOf('=');
            var rawKey = separator >= 0 ? parameter[..separator] : parameter;
            var decodedKey = Uri.UnescapeDataString(rawKey.Replace('+', ' '));
            if (!string.Equals(decodedKey, key, StringComparison.OrdinalIgnoreCase)) continue;
            var rawValue = separator >= 0 ? parameter[(separator + 1)..] : string.Empty;
            return Uri.UnescapeDataString(rawValue.Replace('+', ' '));
        }

        return null;
    }

    private static bool IsStartParameter(string parameter)
    {
        var separator = parameter.IndexOf('=');
        var rawKey = separator >= 0 ? parameter[..separator] : parameter;
        var decodedKey = Uri.UnescapeDataString(rawKey.Replace('+', ' '));
        return string.Equals(decodedKey, "start", StringComparison.OrdinalIgnoreCase);
    }
}
