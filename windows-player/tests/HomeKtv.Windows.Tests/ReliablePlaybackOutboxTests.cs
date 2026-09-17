using System.Text.Json;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class ReliablePlaybackOutboxTests
{
    [Fact]
    public void Structured_play_error_is_reencoded_with_the_current_generation()
    {
        var message = new ReliableMessage(
            "play_error:42",
            "legacy",
            0,
            new ReliablePlayError(42, 7, "bad media"));

        using var document = JsonDocument.Parse(message.Serialize(9));

        Assert.Equal(9, document.RootElement.GetProperty("payload").GetProperty("generation").GetInt64());
        Assert.Equal(7, document.RootElement.GetProperty("payload").GetProperty("file_id").GetInt64());
    }

    [Fact]
    public void Same_key_is_deduplicated_and_item_survives_until_terminal_ack()
    {
        var outbox = new ReliablePlaybackOutbox(maxMessages: 2, maxBytes: 100);
        var message = new ReliableMessage("play_error:42:7", "error", 5);

        Assert.Equal(ReliableEnqueueResult.Enqueued, outbox.Enqueue(message));
        Assert.Equal(ReliableEnqueueResult.AlreadyQueued, outbox.Enqueue(message));
        Assert.Equal(1, outbox.Count);

        Assert.False(outbox.Acknowledge("play_error:42:7", "UNKNOWN"));
        Assert.Equal(1, outbox.Count);
        Assert.True(outbox.Acknowledge("play_error:42:7", "APPLIED"));
        Assert.Empty(outbox.Snapshot());
    }

    [Fact]
    public void Queue_rejects_over_budget_message_and_does_not_poison_the_head()
    {
        var legal = new ReliableMessage("legal", "ok", 2);
        var poison = new ReliableMessage(
            "poison", new string('x', Utf8ByteBudget.MaxMessageBytes + 1), 0);

        var outbox = new ReliablePlaybackOutbox(
            new[] { poison, legal }, maxMessages: 4, maxBytes: 100);

        Assert.Equal(1, outbox.Count);
        Assert.Equal("legal", outbox.Snapshot().Single().Key);
        Assert.Equal(ReliableEnqueueResult.Rejected, outbox.Enqueue(poison));
    }

    [Fact]
    public void Store_round_trips_pending_messages_for_process_restart_recovery()
    {
        var path = Path.Combine(Path.GetTempPath(), $"home-ktv-reliable-{Guid.NewGuid():N}.json");
        try
        {
            var store = new ReliablePlaybackOutboxStore(path);
            store.Save(new[] { new ReliableMessage("play_error:42:7", "bad media", 9) });

            var restored = new ReliablePlaybackOutbox(store.Load());

            Assert.Equal("play_error:42:7", restored.Snapshot().Single().Key);
            Assert.Equal("bad media", restored.Snapshot().Single().Text);
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }
}
