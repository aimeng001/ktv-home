using System.Net;
using System.Net.Http;
using System.Text;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class BoundedResourceTests
{
    [Fact]
    public void Reliable_queue_rejects_new_items_at_the_hard_limit_and_keeps_order()
    {
        var queue = new BoundedReliableMessageQueue();
        for (var index = 0; index < BoundedReliableMessageQueue.MaxMessages; index++)
        {
            Assert.True(queue.TryEnqueue($"m-{index}"));
        }

        Assert.False(queue.TryEnqueue("overflow"));
        Assert.Equal("m-0", PeekAndDequeue(queue));
        Assert.Equal(BoundedReliableMessageQueue.MaxMessages - 1, queue.Count);
    }

    [Fact]
    public void Reliable_queue_reports_empty_reads_and_rejects_messages_over_the_utf8_limit()
    {
        var queue = new BoundedReliableMessageQueue();

        Assert.False(queue.TryPeek(out var peeked));
        Assert.Null(peeked);
        Assert.False(queue.TryDequeue(out var dequeued));
        Assert.Null(dequeued);
        Assert.False(queue.TryEnqueue(new string('x', Utf8ByteBudget.MaxMessageBytes + 1)));
        Assert.Equal(0, queue.Count);
    }

    [Fact]
    public async Task Chunked_http_content_is_rejected_after_crossing_the_limit()
    {
        using var content = new ChunkedContent(new byte[4]);

        var result = await BoundedHttpContentReader.ReadBytesAsync(content, 3);

        Assert.Null(result);
    }

    [Fact]
    public async Task Chunked_http_content_is_accepted_when_it_exactly_fills_the_limit()
    {
        byte[] expected = [1, 2, 3];
        using var content = new ChunkedContent(expected);

        var result = await BoundedHttpContentReader.ReadBytesAsync(content, expected.Length);

        Assert.Equal(expected, result);
    }

    [Fact]
    public async Task Declared_oversized_http_content_is_rejected_before_reading()
    {
        using var content = new ByteArrayContent(new byte[] { 1, 2, 3 });
        content.Headers.ContentLength = 4;

        var result = await BoundedHttpContentReader.ReadBytesAsync(content, 3);

        Assert.Null(result);
    }

    [Theory]
    [InlineData(0L)]
    [InlineData(-1L)]
    [InlineData((long)int.MaxValue + 1L)]
    public async Task Http_readers_reject_invalid_byte_budgets(long maxBytes)
    {
        using var content = new ByteArrayContent(new byte[] { 1 });

        var result = await BoundedHttpContentReader.ReadBytesAsync(content, maxBytes);

        Assert.Null(result);
    }

    [Fact]
    public async Task Text_reader_returns_utf8_text_within_the_byte_budget()
    {
        using var content = new StringContent("家庭歌单", Encoding.UTF8);

        var result = await BoundedHttpContentReader.ReadTextAsync(content, 32);

        Assert.Equal("家庭歌单", result);
    }

    [Fact]
    public async Task Text_reader_returns_null_when_the_body_exceeds_the_byte_budget()
    {
        using var content = new ChunkedContent(Encoding.UTF8.GetBytes("超过限制"));

        var result = await BoundedHttpContentReader.ReadTextAsync(content, 3);

        Assert.Null(result);
    }

    [Fact]
    public void Weighted_cache_enforces_size_budget_and_refreshes_recency_on_read()
    {
        var cache = new WeightedLruCache<string, string>(3, 5, value => value.Length);
        cache.Set("a", "1234");
        cache.Set("b", "12");

        Assert.False(cache.ContainsKey("a"));
        Assert.True(cache.TryGetValue("b", out var value));
        Assert.Equal("12", value);
        Assert.Equal(1, cache.Count);
    }

    [Fact]
    public void Weighted_cache_enforces_item_limit_when_weight_budget_is_not_exceeded()
    {
        var cache = new WeightedLruCache<string, string>(2, 100, value => value.Length);
        cache.Set("a", "1");
        cache.Set("b", "2");
        cache.Set("c", "3");

        Assert.False(cache.ContainsKey("a"));
        Assert.True(cache.TryGetValue("b", out var second));
        Assert.Equal("2", second);
        Assert.True(cache.TryGetValue("c", out var third));
        Assert.Equal("3", third);
        Assert.Equal(2, cache.Count);
    }

    [Fact]
    public void Weighted_cache_replacement_recalculates_weight_before_eviction()
    {
        var cache = new WeightedLruCache<string, string>(2, 5, value => value.Length);
        cache.Set("a", "1234");
        cache.Set("b", "1");

        cache.Set("a", "12");
        cache.Set("c", "456");

        Assert.False(cache.ContainsKey("b"));
        Assert.True(cache.TryGetValue("a", out var replacement));
        Assert.Equal("12", replacement);
        Assert.True(cache.TryGetValue("c", out var added));
        Assert.Equal("456", added);
        Assert.Equal(2, cache.Count);
    }

    [Fact]
    public void Weighted_cache_rejects_invalid_limits_and_weight_function()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new WeightedLruCache<string, string>(0, 1, value => value.Length));
        Assert.Throws<ArgumentOutOfRangeException>(() => new WeightedLruCache<string, string>(1, 0, value => value.Length));
        Assert.Throws<ArgumentNullException>(() => new WeightedLruCache<string, string>(1, 1, null!));
    }

    [Fact]
    public void Weighted_cache_miss_oversized_entry_and_clear_keep_the_cache_consistent()
    {
        var cache = new WeightedLruCache<string, string>(2, 3, value => value.StartsWith('-') ? -10 : value.Length);

        Assert.False(cache.TryGetValue("missing", out _));
        cache.Set("zero-weight", "-negative");
        Assert.True(cache.ContainsKey("zero-weight"));
        cache.Set("too-heavy", "1234");
        Assert.False(cache.ContainsKey("too-heavy"));
        Assert.False(cache.ContainsKey("zero-weight"));

        cache.Set("kept", "12");
        cache.Clear();

        Assert.Equal(0, cache.Count);
        Assert.False(cache.TryGetValue("kept", out _));
    }

    private static string PeekAndDequeue(BoundedReliableMessageQueue queue)
    {
        Assert.True(queue.TryPeek(out var peeked));
        Assert.True(queue.TryDequeue(out var dequeued));
        Assert.Equal(peeked, dequeued);
        return dequeued!;
    }

    private sealed class ChunkedContent(byte[] bytes) : HttpContent
    {
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) =>
            stream.WriteAsync(bytes).AsTask();

        protected override bool TryComputeLength(out long length)
        {
            length = -1;
            return false;
        }
    }
}
