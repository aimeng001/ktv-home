using HomeKtv.Windows.Display;

namespace HomeKtv.Windows.Tests;

public sealed class DisplayManagerTests
{
    [Fact]
    public void Rejects_a_null_display_provider()
    {
        Assert.Throws<ArgumentNullException>(() => new DisplayManager(null!));
    }

    [Fact]
    public void Uses_the_injected_display_snapshot_and_clamps_requested_indices()
    {
        var displays = new[]
        {
            CreateDisplay(0, "DISPLAY-A", true),
            CreateDisplay(1, "DISPLAY-B", false),
        };
        var manager = new DisplayManager(() => displays);

        Assert.Same(displays, manager.GetDisplays());
        Assert.Equal(0, manager.SafeIndex(-10));
        Assert.Equal(0, manager.SafeIndex(0));
        Assert.Equal(1, manager.SafeIndex(1));
        Assert.Equal(1, manager.SafeIndex(10));
    }

    [Fact]
    public void Uses_primary_display_when_saved_monitor_is_missing_and_returns_zero_for_empty_topology()
    {
        var primary = CreateDisplay(0, "DISPLAY-A", true);
        var manager = new DisplayManager(() => [primary]);

        Assert.Same(primary, manager.ResolveTarget("DISPLAY-MISSING", legacyIndex: 9));
        Assert.Equal(0, new DisplayManager(() => Array.Empty<DisplayInfo>()).SafeIndex(9));
        Assert.Null(new DisplayManager(() => Array.Empty<DisplayInfo>()).ResolveTarget(null));
    }

    [Fact]
    public void Uses_display_name_when_stable_id_is_blank_and_uses_work_area_dimensions_when_available()
    {
        var display = new DisplayInfo(0, "DISPLAY-NAME", 1920, 1080, true)
        {
            WorkAreaWidth = 1600,
            WorkAreaHeight = 900,
        };

        Assert.Equal("DISPLAY-NAME", display.StableId);
        Assert.Equal(1600, display.EffectiveWorkAreaWidth);
        Assert.Equal(900, display.EffectiveWorkAreaHeight);
        Assert.Equal(1600, display.WorkAreaRight);
        Assert.Equal(900, display.WorkAreaBottom);
        Assert.Equal("DISPLAY-NAME (1920×1080)", display.Label);
    }

    private static DisplayInfo CreateDisplay(int index, string id, bool primary) =>
        new(index, id, 1920, 1080, primary) { Id = id };
}
