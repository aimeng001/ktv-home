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
    public async Task Chunked_http_content_is_rejected_after_crossing_the_limit()
    {
        using var content = new ChunkedContent(new byte[4]);

        var result = await BoundedHttpContentReader.ReadBytesAsync(content, 3);

        Assert.Null(result);
    }

    [Fact]
    public async Task Declared_oversized_http_content_is_rejected_before_reading()
    {
        using var content = new ByteArrayContent(new byte[] { 1, 2, 3 });
        content.Headers.ContentLength = 4;

        var result = await BoundedHttpContentReader.ReadBytesAsync(content, 3);

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
