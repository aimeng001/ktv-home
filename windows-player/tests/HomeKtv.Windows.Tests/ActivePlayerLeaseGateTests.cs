using HomeKtv.Windows.Playback;

namespace HomeKtv.Windows.Tests;

public sealed class ActivePlayerLeaseGateTests
{
    [Fact]
    public void Only_active_unexpired_assignment_can_project_and_report()
    {
        var now = DateTimeOffset.Parse("2026-08-27T00:00:00Z");
        var gate = new ActivePlayerLeaseGate(() => now);

        gate.Apply(new PlayerAssignment("STANDBY", 0, 45_000));
        Assert.False(gate.TryGetGeneration(out _));

        gate.Apply(new PlayerAssignment("ACTIVE", 7, 45_000));
        Assert.True(gate.TryGetGeneration(out var generation));
        Assert.Equal(7, generation);

        now = now.AddMilliseconds(45_000);
        Assert.False(gate.TryGetGeneration(out _));
    }

    [Fact]
    public void Disconnect_fences_the_previous_generation()
    {
        var gate = new ActivePlayerLeaseGate(() => DateTimeOffset.UtcNow);
        gate.Apply(new PlayerAssignment("ACTIVE", 3, 45_000));

        gate.Disconnect();

        Assert.False(gate.TryGetGeneration(out _));
    }
}
