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
    public void Plain_reliable_message_serializes_to_its_original_text()
    {
        var message = new ReliableMessage("finished:42", "{\"type\":\"finished\"}", 0);

        Assert.Equal("{\"type\":\"finished\"}", message.Serialize(9));
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
        var path = Path.Combine(AppContext.BaseDirectory, $"home-ktv-reliable-{Guid.NewGuid():N}.json");
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

    [Fact]
    public void Constructor_rejects_nonpositive_limits()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new ReliablePlaybackOutbox(maxMessages: 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => new ReliablePlaybackOutbox(maxBytes: 0));
    }

    [Fact]
    public void Constructor_normalizes_initial_messages_and_enforces_deduplication_and_budgets()
    {
        var outbox = new ReliablePlaybackOutbox(
            new ReliableMessage[]
            {
                null!,
                new(" ", "invalid", 0),
                new("large", "中", 0),
                new("large", "x", 0),
                new("over-budget", "x", 0),
                new("empty", string.Empty, 0),
            },
            maxMessages: 2,
            maxBytes: 3);

        var message = Assert.Single(outbox.Snapshot());
        Assert.Equal("large", message.Key);
        Assert.Equal(3, message.Utf8Bytes);
        Assert.Equal(3, outbox.BufferedBytes);
    }

    [Fact]
    public void Enqueue_uses_actual_utf8_size_and_rejects_message_count_and_byte_overflow()
    {
        var outbox = new ReliablePlaybackOutbox(maxMessages: 1, maxBytes: 3);

        Assert.Equal(ReliableEnqueueResult.Enqueued, outbox.Enqueue(new("one", "中", 999)));
        Assert.Equal(3, outbox.BufferedBytes);
        Assert.Equal(ReliableEnqueueResult.Rejected, outbox.Enqueue(new("two", "x", 1)));
        Assert.Equal(ReliableEnqueueResult.AlreadyQueued, outbox.Enqueue(new("one", "x", 1)));
        Assert.Equal(1, outbox.Count);
        Assert.Equal(3, outbox.BufferedBytes);
        Assert.Equal(ReliableEnqueueResult.Rejected, outbox.Enqueue(new("empty", "", 0)));
        Assert.Equal(ReliableEnqueueResult.Rejected, outbox.Enqueue(new("null-text", null!, 0)));
    }

    [Fact]
    public void Peek_and_dequeue_preserve_fifo_order_and_adjust_buffered_bytes()
    {
        var outbox = new ReliablePlaybackOutbox(maxMessages: 3, maxBytes: 20);
        Assert.False(outbox.TryPeekMessage(out var emptyPeek));
        Assert.Null(emptyPeek);
        Assert.False(outbox.TryDequeueMessage(out var emptyDequeue));
        Assert.Null(emptyDequeue);

        outbox.Enqueue(new("first", "中", 0));
        outbox.Enqueue(new("second", "x", 0));
        Assert.True(outbox.TryPeekMessage(out var peeked));
        Assert.Equal("first", peeked!.Key);
        Assert.Equal(4, outbox.BufferedBytes);

        Assert.True(outbox.TryDequeueMessage(out var dequeued));
        Assert.Equal("first", dequeued!.Key);
        Assert.Equal(1, outbox.Count);
        Assert.Equal(1, outbox.BufferedBytes);
        Assert.True(outbox.TryPeekMessage(out peeked));
        Assert.Equal("second", peeked!.Key);
    }

    [Fact]
    public void Acknowledge_removes_only_known_keys_with_terminal_statuses()
    {
        var outbox = new ReliablePlaybackOutbox(maxMessages: 4, maxBytes: 40);
        foreach (var status in new[] { "APPLIED", "ALREADY_APPLIED", "STALE" })
        {
            Assert.Equal(ReliableEnqueueResult.Enqueued,
                outbox.Enqueue(new(status, status, 0)));
        }

        Assert.False(outbox.Acknowledge("", "APPLIED"));
        Assert.False(outbox.Acknowledge("APPLIED", null));
        Assert.False(outbox.Acknowledge("APPLIED", "applied"));
        Assert.False(outbox.Acknowledge("missing", "APPLIED"));
        Assert.True(outbox.Acknowledge("ALREADY_APPLIED", "ALREADY_APPLIED"));
        Assert.Equal(2, outbox.Count);
        Assert.Equal(12, outbox.BufferedBytes);
    }

    [Fact]
    public void Remove_updates_the_buffer_and_rejects_blank_or_unknown_keys()
    {
        var outbox = new ReliablePlaybackOutbox(maxMessages: 3, maxBytes: 20);
        outbox.Enqueue(new("first", "abc", 0));
        outbox.Enqueue(new("second", "中", 0));

        Assert.False(outbox.Remove(" "));
        Assert.False(outbox.Remove("missing"));
        Assert.True(outbox.Remove("first"));
        Assert.Equal(new[] { "second" }, outbox.Snapshot().Select(item => item.Key));
        Assert.Equal(3, outbox.BufferedBytes);
    }

    [Fact]
    public void Failed_persistence_rolls_back_enqueue_dequeue_acknowledgement_and_remove()
    {
        var outbox = new ReliablePlaybackOutbox(
            persist: _ => throw new IOException("synthetic persistence failure"),
            maxMessages: 3,
            maxBytes: 20);

        Assert.Equal(ReliableEnqueueResult.Rejected, outbox.Enqueue(new("cannot-save", "x", 0)));
        Assert.Equal(0, outbox.Count);
        Assert.Equal(0, outbox.BufferedBytes);

        var persistenceCalls = 0;
        var seeded = new ReliablePlaybackOutbox(persist: _ =>
        {
            if (++persistenceCalls > 1) throw new IOException("synthetic persistence failure");
        }, maxMessages: 3, maxBytes: 20);
        Assert.Equal(ReliableEnqueueResult.Enqueued, seeded.Enqueue(new("kept", "中", 0)));

        Assert.False(seeded.TryDequeueMessage(out var dequeued));
        Assert.Null(dequeued);
        Assert.False(seeded.Acknowledge("kept", "APPLIED"));
        Assert.False(seeded.Remove("kept"));
        Assert.Equal(1, seeded.Count);
        Assert.Equal(3, seeded.BufferedBytes);
        Assert.Equal("kept", Assert.Single(seeded.Snapshot()).Key);
    }

    [Fact]
    public void Store_load_returns_empty_for_missing_null_or_malformed_files()
    {
        var directory = Path.Combine(AppContext.BaseDirectory, $"home-ktv-outbox-{Guid.NewGuid():N}");
        var path = Path.Combine(directory, "state.json");
        var store = new ReliablePlaybackOutboxStore(path);
        try
        {
            Assert.Empty(store.Load());
            Directory.CreateDirectory(directory);
            File.WriteAllText(path, "null");
            Assert.Empty(store.Load());
            File.WriteAllText(path, "{");
            Assert.Empty(store.Load());
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
            if (Directory.Exists(directory)) Directory.Delete(directory, recursive: false);
        }
    }

    [Fact]
    public void Store_can_be_constructed_with_the_default_user_scope_without_file_io()
    {
        var store = new ReliablePlaybackOutboxStore();

        Assert.NotNull(store);
    }

    [Fact]
    public void Store_load_returns_empty_when_the_file_is_locked_by_another_writer()
    {
        var path = Path.Combine(AppContext.BaseDirectory, $"home-ktv-outbox-locked-{Guid.NewGuid():N}.json");
        try
        {
            File.WriteAllText(path, "[]");
            using var locked = new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None);

            Assert.Empty(new ReliablePlaybackOutboxStore(path).Load());
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }

    [Fact]
    public void Store_load_rejects_a_file_larger_than_its_read_budget_without_deserializing_it()
    {
        var path = Path.Combine(AppContext.BaseDirectory, $"home-ktv-outbox-oversized-{Guid.NewGuid():N}.json");
        try
        {
            using (var stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.SetLength(ReliablePlaybackOutbox.MaxBytes * 2L + 1);
            }

            Assert.Empty(new ReliablePlaybackOutboxStore(path).Load());
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
        }
    }

    [Fact]
    public void Store_replaces_existing_state_and_removes_its_temporary_file()
    {
        var path = Path.Combine(AppContext.BaseDirectory, $"home-ktv-reliable-{Guid.NewGuid():N}.json");
        var store = new ReliablePlaybackOutboxStore(path);
        try
        {
            store.Save(new[] { new ReliableMessage("old", "old", 3) });
            store.Save(new[] { new ReliableMessage("new", "new", 3) });

            Assert.Equal("new", Assert.Single(store.Load()).Key);
            Assert.False(File.Exists(path + ".tmp"));
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
            if (File.Exists(path + ".tmp")) File.Delete(path + ".tmp");
        }
    }

    [Fact]
    public void Store_removes_the_temporary_file_when_committing_to_a_directory_fails()
    {
        var path = Path.Combine(AppContext.BaseDirectory, $"home-ktv-outbox-target-{Guid.NewGuid():N}");
        Directory.CreateDirectory(path);
        try
        {
            var store = new ReliablePlaybackOutboxStore(path);

            Assert.ThrowsAny<IOException>(() =>
                store.Save(new[] { new ReliableMessage("message", "payload", 7) }));
            Assert.False(File.Exists(path + ".tmp"));
            Assert.True(Directory.Exists(path));
        }
        finally
        {
            if (File.Exists(path + ".tmp")) File.Delete(path + ".tmp");
            if (Directory.Exists(path)) Directory.Delete(path, recursive: false);
        }
    }

    [Fact]
    public void Store_save_supports_a_relative_filename_without_a_parent_directory()
    {
        var path = $"home-ktv-reliable-{Guid.NewGuid():N}.json";
        try
        {
            new ReliablePlaybackOutboxStore(path).Save(new[] { new ReliableMessage("relative", "x", 1) });

            Assert.Equal("relative", Assert.Single(new ReliablePlaybackOutboxStore(path).Load()).Key);
            Assert.False(File.Exists(path + ".tmp"));
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
            if (File.Exists(path + ".tmp")) File.Delete(path + ".tmp");
        }
    }

    [Fact]
    public void Store_rejects_message_count_or_byte_budget_overflow_before_writing()
    {
        var path = Path.Combine(AppContext.BaseDirectory, $"home-ktv-reliable-{Guid.NewGuid():N}.json");
        var store = new ReliablePlaybackOutboxStore(path);
        try
        {
            var tooMany = Enumerable.Range(0, ReliablePlaybackOutbox.MaxMessages + 1)
                .Select(index => new ReliableMessage($"key-{index}", "x", 1)).ToArray();
            Assert.Throws<InvalidOperationException>(() => store.Save(tooMany));
            Assert.Throws<InvalidOperationException>(() => store.Save(
                new[] { new ReliableMessage("too-large", "x", ReliablePlaybackOutbox.MaxBytes + 1) }));
            Assert.False(File.Exists(path));
        }
        finally
        {
            if (File.Exists(path)) File.Delete(path);
            if (File.Exists(path + ".tmp")) File.Delete(path + ".tmp");
        }
    }
}
