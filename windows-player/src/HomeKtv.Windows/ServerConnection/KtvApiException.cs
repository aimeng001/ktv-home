namespace HomeKtv.Windows.ServerConnection;

/** Represents a structured non-success response from the Home KTV API. */
public sealed class KtvApiException : Exception
{
    public KtvApiException(int statusCode, string? code, string message)
        : base(message)
    {
        StatusCode = statusCode;
        Code = code;
    }

    public int StatusCode { get; }

    public string? Code { get; }
}
