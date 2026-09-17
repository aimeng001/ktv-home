namespace HomeKtv.Windows.ServerConnection;

public readonly record struct PlaybackReportAckTargets(bool Finished, bool PlayError)
{
    public static PlaybackReportAckTargets For(string? reportType) => reportType switch
    {
        "finished" => new(true, false),
        "play_error" => new(false, true),
        _ => new(true, true),
    };
}
