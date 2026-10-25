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
    public void Report_rejects_non_positive_queue_and_generation_ids()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new PendingPlaybackReport(0).Serialize(1));
        Assert.Throws<ArgumentOutOfRangeException>(() => new PendingPlaybackReport(-1).Serialize(1));
        Assert.Throws<ArgumentOutOfRangeException>(() => new PendingPlaybackReport(1).Serialize(0));
        Assert.Throws<ArgumentOutOfRangeException>(() => new PendingPlaybackReport(1).Serialize(-1));
    }

    [Fact]
    public void Report_stays_pending_until_a_terminal_acknowledgement()
    {
        var reports = new PendingPlaybackReportQueue();
        Assert.Equal(PendingPlaybackReportEnqueueResult.Added, reports.Enqueue(42));

        reports.Acknowledge(42, "UNKNOWN");
        Assert.Equal(new[] { 42L }, reports.PendingQueueIds);

        reports.Acknowledge(42, "ALREADY_APPLIED");
        Assert.Empty(reports.PendingQueueIds);
    }

    [Fact]
    public void Every_documented_terminal_ack_status_removes_the_pending_report()
    {
        foreach (var status in new[] { "APPLIED", "ALREADY_APPLIED", "STALE" })
        {
            var reports = new PendingPlaybackReportQueue();
            Assert.Equal(PendingPlaybackReportEnqueueResult.Added, reports.Enqueue(42));

            reports.Acknowledge(42, status);

            Assert.Empty(reports.PendingQueueIds);
        }
    }

    [Fact]
    public void Queue_rejects_invalid_and_duplicate_ids_and_ignores_non_terminal_or_unknown_acks()
    {
        var reports = new PendingPlaybackReportQueue();

        Assert.Equal(PendingPlaybackReportEnqueueResult.Invalid, reports.Enqueue(0));
        Assert.Equal(PendingPlaybackReportEnqueueResult.Invalid, reports.Enqueue(-1));
        Assert.Equal(PendingPlaybackReportEnqueueResult.Added, reports.Enqueue(42));
        Assert.Equal(PendingPlaybackReportEnqueueResult.Duplicate, reports.Enqueue(42));

        reports.Acknowledge(0, "APPLIED");
        reports.Acknowledge(42, null);
        reports.Acknowledge(99, "APPLIED");
        reports.Acknowledge(42, "UNKNOWN");

        Assert.Equal(new[] { 42L }, reports.PendingQueueIds);
        Assert.Equal(new[] { new PendingPlaybackReport(42) }, reports.Snapshot());
    }

    [Fact]
    public void Initial_queue_filters_non_positive_and_duplicate_ids()
    {
        var reports = new PendingPlaybackReportQueue(new long[] { 0, -1, 42, 42, 43 });

        Assert.Equal(new[] { 42L, 43L }, reports.PendingQueueIds);
        Assert.Equal(new[] { new PendingPlaybackReport(42), new PendingPlaybackReport(43) }, reports.Snapshot());
    }

    [Fact]
    public void Full_queue_returns_explicit_failure_without_discarding_existing_reports()
    {
        var reports = new PendingPlaybackReportQueue(Enumerable.Range(1, PendingPlaybackReportQueue.MaxPending)
            .Select(value => (long)value));

        var result = reports.Enqueue(PendingPlaybackReportQueue.MaxPending + 1L);

        Assert.Equal(PendingPlaybackReportEnqueueResult.Full, result);
        Assert.Equal(PendingPlaybackReportQueue.MaxPending, reports.PendingQueueIds.Count);
        Assert.Equal(1L, reports.PendingQueueIds[0]);
        Assert.Equal(PendingPlaybackReportQueue.MaxPending,
            reports.PendingQueueIds[^1]);
    }

    [Fact]
    public void Persistence_failure_is_explicit_and_does_not_leave_a_memory_only_report()
    {
        var reports = new PendingPlaybackReportQueue(
            persist: _ => throw new IOException("disk full"));

        var result = reports.Enqueue(42);

        Assert.Equal(PendingPlaybackReportEnqueueResult.PersistenceFailed, result);
        Assert.Empty(reports.PendingQueueIds);
    }

    [Fact]
    public void Unauthorized_persistence_failure_is_reported_without_adding_an_item()
    {
        var reports = new PendingPlaybackReportQueue(
            persist: _ => throw new UnauthorizedAccessException("access denied"));

        var result = reports.Enqueue(42);

        Assert.Equal(PendingPlaybackReportEnqueueResult.PersistenceFailed, result);
        Assert.Empty(reports.PendingQueueIds);
    }

    [Fact]
    public void Acknowledgement_is_rolled_back_when_persistence_fails()
    {
        var persistCalls = 0;
        var reports = new PendingPlaybackReportQueue(persist: _ =>
        {
            if (++persistCalls > 1) throw new IOException("disk full");
        });
        Assert.Equal(PendingPlaybackReportEnqueueResult.Added, reports.Enqueue(42));

        reports.Acknowledge(42, "APPLIED");

        Assert.Equal(new[] { 42L }, reports.PendingQueueIds);
        Assert.Equal(2, persistCalls);
    }

    [Fact]
    public void Store_round_trips_pending_queue_ids()
    {
        var path = Path.Combine(AppContext.BaseDirectory, $"home-ktv-pending-{Guid.NewGuid():N}.json");
        try
        {
            var store = new PendingPlaybackReportStore(path);
            store.Save(new[] { 42L, 43L });
            store.Save(new[] { 43L, 44L });

            Assert.Equal(new[] { 43L, 44L }, store.Load());
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }

    [Fact]
    public void Store_load_returns_empty_for_missing_malformed_and_null_data()
    {
        var missingPath = Path.Combine(AppContext.BaseDirectory, $"home-ktv-missing-{Guid.NewGuid():N}.json");
        var malformedPath = Path.Combine(AppContext.BaseDirectory, $"home-ktv-malformed-{Guid.NewGuid():N}.json");
        var nullPath = Path.Combine(AppContext.BaseDirectory, $"home-ktv-null-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(malformedPath, "not-json");
            File.WriteAllText(nullPath, "null");

            Assert.Empty(new PendingPlaybackReportStore(missingPath).Load());
            Assert.Empty(new PendingPlaybackReportStore(malformedPath).Load());
            Assert.Empty(new PendingPlaybackReportStore(nullPath).Load());
        }
        finally
        {
            foreach (var path in new[] { malformedPath, nullPath })
            {
                if (File.Exists(path)) File.Delete(path);
            }
        }
    }

    [Fact]
    public void Store_uses_an_isolated_generated_path_when_a_scope_is_supplied()
    {
        var store = new PendingPlaybackReportStore(scope: $"test-only-{Guid.NewGuid():N}");

        Assert.Empty(store.Load());
    }

    [Fact]
    public void Store_can_be_constructed_with_the_default_user_scope_without_file_io()
    {
        var store = new PendingPlaybackReportStore();

        Assert.NotNull(store);
    }

    [Fact]
    public void Store_load_filters_invalid_and_duplicate_ids_and_caps_the_queue()
    {
        var path = Path.Combine(AppContext.BaseDirectory, $"home-ktv-pending-cap-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(path, JsonSerializer.Serialize(new long[] { 42, -1, 42 }
                .Concat(Enumerable.Range(1, PendingPlaybackReportQueue.MaxPending + 5).Select(value => (long)value + 100))));

            var loaded = new PendingPlaybackReportStore(path).Load();

            Assert.Equal(PendingPlaybackReportQueue.MaxPending, loaded.Count);
            Assert.Equal(new[] { 42L, 101L, 102L }, loaded.Take(3));
            Assert.DoesNotContain(loaded, queueId => queueId <= 0);
            Assert.Equal(loaded.Count, loaded.Distinct().Count());
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }

    [Fact]
    public void Store_removes_the_temporary_file_when_atomic_move_fails()
    {
        var directoryPath = Path.Combine(AppContext.BaseDirectory, $"home-ktv-pending-dir-{Guid.NewGuid():N}");
        var temporaryPath = directoryPath + ".tmp";
        Directory.CreateDirectory(directoryPath);
        try
        {
            var store = new PendingPlaybackReportStore(directoryPath);

            Assert.Throws<IOException>(() => store.Save(new[] { 42L }));
            Assert.False(File.Exists(temporaryPath));
            Assert.True(Directory.Exists(directoryPath));
        }
        finally
        {
            if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
            if (Directory.Exists(directoryPath)) Directory.Delete(directoryPath);
        }
    }
}
