using System.Text.Json;
using System.IO;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class PendingPlaybackReportTests
{
    [Fact]
    public void Report_is_serialized_with_the_generation_at_flush_time()
    {
        var report = new PendingPlaybackReport(42);

        using var document = JsonDocument.Parse(report.Serialize(7));
        var payload = document.RootElement.GetProperty("payload");

        Assert.Equal(42, payload.GetProperty("queue_id").GetInt64());
        Assert.Equal(7, payload.GetProperty("generation").GetInt64());
    }

    [Fact]
    public void Report_stays_pending_until_a_terminal_acknowledgement()
    {
        var reports = new PendingPlaybackReportQueue();
        Assert.True(reports.Enqueue(42));

        reports.Acknowledge(42, "UNKNOWN");
        Assert.Equal(new[] { 42L }, reports.PendingQueueIds);

        reports.Acknowledge(42, "ALREADY_APPLIED");
        Assert.Empty(reports.PendingQueueIds);
    }

    [Fact]
    public void Store_round_trips_pending_queue_ids()
    {
        var path = Path.Combine(Path.GetTempPath(), $"home-ktv-pending-{Guid.NewGuid():N}.json");
        try
        {
            var store = new PendingPlaybackReportStore(path);
            store.Save(new[] { 42L, 43L });

            Assert.Equal(new[] { 42L, 43L }, store.Load());
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }
}
