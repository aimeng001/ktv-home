using System.Globalization;

namespace HomeKtv.Windows.ServerConnection;

public sealed record ServerEndpoint(Uri BaseUri)
{
    public Uri ApiBaseUri => new(BaseUri, "api/");

    public Uri WebSocketUri(string clientToken)
    {
        var scheme = BaseUri.Scheme.Equals("https", StringComparison.OrdinalIgnoreCase) ? "wss" : "ws";
        var query = $"client_type=tv&client_token={Uri.EscapeDataString(clientToken)}&protocol_version=2&platform=WINDOWS";
        var builder = new UriBuilder(scheme, BaseUri.Host, BaseUri.Port)
        {
            Path = "/ws",
            Query = query,
        };
        return builder.Uri;
    }

    public string? PlayerCredentialHeader(string? playerCredential)
    {
        if (string.IsNullOrWhiteSpace(playerCredential)) return null;
        if (!BaseUri.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("A player credential requires HTTPS/WSS transport.");
        }
        return playerCredential.Trim();
    }

    public static ServerEndpoint Parse(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            throw new FormatException("Server address is empty.");
        }

        var value = raw.Trim();
        if (!value.Contains("://", StringComparison.Ordinal))
        {
            value = "http://" + value;
        }

        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps)
            || string.IsNullOrWhiteSpace(uri.Host))
        {
            throw new FormatException("Server address must be an HTTP(S) host and port.");
        }

        var port = uri.IsDefaultPort ? 8080 : uri.Port;
        if (port is < 1 or > 65_535)
        {
            throw new FormatException("Server port is outside the valid range.");
        }

        var builder = new UriBuilder(uri.Scheme, uri.Host, port)
        {
            Path = "/",
            Query = string.Empty,
        };
        return new ServerEndpoint(builder.Uri);
    }
}
