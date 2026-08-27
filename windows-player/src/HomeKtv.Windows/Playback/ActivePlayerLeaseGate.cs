namespace HomeKtv.Windows.Playback;

public sealed record PlayerAssignment(string Role, long Generation, long LeaseMs);

/** Fences local projection and upstream reports when this client is standby or its lease expired. */
public sealed class ActivePlayerLeaseGate
{
    private readonly Func<DateTimeOffset> now;
    private readonly object sync = new();
    private long generation;
    private DateTimeOffset expiresAt;
    private bool active;

    public ActivePlayerLeaseGate(Func<DateTimeOffset>? now = null)
    {
        this.now = now ?? (() => DateTimeOffset.UtcNow);
    }

    public void Apply(PlayerAssignment assignment)
    {
        lock (sync)
        {
            active = string.Equals(assignment.Role, "ACTIVE", StringComparison.OrdinalIgnoreCase)
                     && assignment.Generation > 0 && assignment.LeaseMs > 0;
            generation = active ? assignment.Generation : 0;
            expiresAt = active ? now().AddMilliseconds(assignment.LeaseMs) : DateTimeOffset.MinValue;
        }
    }

    public bool TryGetGeneration(out long value)
    {
        lock (sync)
        {
            if (active && now() < expiresAt)
            {
                value = generation;
                return true;
            }
            active = false;
            generation = 0;
            value = 0;
            return false;
        }
    }

    public void Disconnect()
    {
        lock (sync)
        {
            active = false;
            generation = 0;
            expiresAt = DateTimeOffset.MinValue;
        }
    }
}
