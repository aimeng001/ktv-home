namespace HomeKtv.Windows.ServerConnection;

/** Access-ordered cache with a hard item and weighted-size budget. */
public sealed class WeightedLruCache<TKey, TValue>
    where TKey : notnull
{
    private readonly int capacity;
    private readonly long maxWeight;
    private readonly Func<TValue, long> weight;
    private readonly Dictionary<TKey, TValue> values = new();
    private readonly LinkedList<TKey> order = new();
    private readonly Dictionary<TKey, LinkedListNode<TKey>> nodes = new();
    private long currentWeight;

    public WeightedLruCache(int capacity, long maxWeight, Func<TValue, long> weight)
    {
        if (capacity <= 0) throw new ArgumentOutOfRangeException(nameof(capacity));
        if (maxWeight <= 0) throw new ArgumentOutOfRangeException(nameof(maxWeight));
        this.capacity = capacity;
        this.maxWeight = maxWeight;
        this.weight = weight ?? throw new ArgumentNullException(nameof(weight));
    }

    public int Count => values.Count;

    public bool ContainsKey(TKey key) => values.ContainsKey(key);

    public bool TryGetValue(TKey key, out TValue value)
    {
        if (!values.TryGetValue(key, out value!)) return false;
        Touch(key);
        return true;
    }

    public void Set(TKey key, TValue value)
    {
        if (values.Remove(key, out var old))
        {
            currentWeight -= WeightOf(old);
            RemoveNode(key);
        }

        values[key] = value;
        nodes[key] = order.AddLast(key);
        currentWeight += WeightOf(value);
        Trim();
    }

    public void Clear()
    {
        values.Clear();
        order.Clear();
        nodes.Clear();
        currentWeight = 0;
    }

    private void Touch(TKey key)
    {
        var node = nodes[key];
        order.Remove(node);
        nodes[key] = order.AddLast(key);
    }

    private void Trim()
    {
        while (values.Count > capacity || currentWeight > maxWeight)
        {
            var node = order.First;
            if (node is null) return;
            var key = node.Value;
            order.RemoveFirst();
            nodes.Remove(key);
            if (values.Remove(key, out var value)) currentWeight -= WeightOf(value);
        }
    }

    private void RemoveNode(TKey key)
    {
        if (!nodes.Remove(key, out var node)) return;
        order.Remove(node);
    }

    private long WeightOf(TValue value) => Math.Max(0, weight(value));
}
