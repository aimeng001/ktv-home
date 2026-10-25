using HomeKtv.Windows.Display;
using HomeKtv.Windows.Settings;

namespace HomeKtv.Windows.Tests;

public sealed class DisplayConfigurationTests
{
    [Fact]
    public void Selects_saved_display_by_stable_id_when_monitor_order_changes()
    {
        var primary = CreateDisplay(0, @"\\.\DISPLAY1", 1920, 1080, true, 0, 0);
        var television = CreateDisplay(1, @"\\.\DISPLAY2", 3840, 2160, false, 1920, 0);

        var selected = DisplaySelector.ResolveTarget(
            [television with { Index = 0 }, primary with { Index = 1 }],
            television.Id,
            legacyIndex: 1);

        Assert.NotNull(selected);
        Assert.Equal(television.Id, selected!.Id);
        Assert.Equal(0, selected.Index);
    }

    [Fact]
    public void Missing_saved_display_falls_back_to_primary_instead_of_stale_index()
    {
        var primary = CreateDisplay(0, @"\\.\DISPLAY1", 1920, 1080, true, 0, 0);
        var other = CreateDisplay(1, @"\\.\DISPLAY3", 2560, 1440, false, 1920, 0);

        var selected = DisplaySelector.ResolveTarget(
            [primary, other],
            @"\\.\DISPLAY2",
            legacyIndex: 1);

        Assert.NotNull(selected);
        Assert.Equal(primary.Id, selected!.Id);
    }

    [Fact]
    public void Legacy_index_is_used_when_no_stable_display_id_was_saved()
    {
        var primary = CreateDisplay(0, @"\\.\DISPLAY1", 1920, 1080, true, 0, 0);
        var television = CreateDisplay(1, @"\\.\DISPLAY2", 3840, 2160, false, 1920, 0);

        var selected = DisplaySelector.ResolveTarget([primary, television], null, legacyIndex: 1);

        Assert.NotNull(selected);
        Assert.Equal(television.Id, selected!.Id);
    }

    [Fact]
    public void Blank_saved_id_uses_a_valid_legacy_index_and_invalid_index_falls_back_safely()
    {
        var first = CreateDisplay(0, @"\\.\DISPLAY1", 1920, 1080, false, 0, 0);
        var second = CreateDisplay(1, @"\\.\DISPLAY2", 1280, 720, false, 1920, 0);

        Assert.Equal(second.Id, DisplaySelector.ResolveTarget([first, second], " ", legacyIndex: 1)!.Id);
        Assert.Equal(first.Id, DisplaySelector.ResolveTarget([first, second], null, legacyIndex: 9)!.Id);
        Assert.Null(DisplaySelector.ResolveTarget([], null, legacyIndex: 0));
    }

    [Fact]
    public void Restores_window_inside_selected_work_area_when_saved_position_is_off_screen()
    {
        var television = CreateDisplay(1, @"\\.\DISPLAY2", 3840, 2160, false, 1920, 0);
        var saved = new WindowPlacementSettings
        {
            DisplayId = television.Id,
            Left = -5000,
            Top = -5000,
            Width = 1200,
            Height = 800,
            IsMaximized = true,
        };

        var restored = WindowPlacementResolver.Restore(saved, [television]);

        Assert.Equal(television.Id, restored.DisplayId);
        Assert.InRange(restored.Left!.Value, television.WorkAreaLeft,
            television.WorkAreaRight - restored.Width);
        Assert.InRange(restored.Top!.Value, television.WorkAreaTop,
            television.WorkAreaBottom - restored.Height);
        Assert.True(restored.IsMaximized);
    }

    [Fact]
    public void Missing_saved_display_resets_window_to_primary_work_area()
    {
        var primary = CreateDisplay(0, @"\\.\DISPLAY1", 1920, 1080, true, 0, 0);
        var saved = new WindowPlacementSettings
        {
            DisplayId = @"\\.\DISPLAY2",
            Left = 3000,
            Top = 2000,
            Width = 820,
            Height = 520,
        };

        var restored = WindowPlacementResolver.Restore(saved, [primary]);

        Assert.Equal(primary.Id, restored.DisplayId);
        Assert.InRange(restored.Left!.Value, primary.WorkAreaLeft,
            primary.WorkAreaRight - restored.Width);
        Assert.InRange(restored.Top!.Value, primary.WorkAreaTop,
            primary.WorkAreaBottom - restored.Height);
    }

    [Fact]
    public void No_display_keeps_safe_dimensions_without_throwing()
    {
        var restored = WindowPlacementResolver.Restore(
            new WindowPlacementSettings { Width = -1, Height = double.NaN },
            []);

        Assert.Null(restored.DisplayId);
        Assert.Equal(820, restored.Width);
        Assert.Equal(520, restored.Height);
    }

    [Fact]
    public void Invalid_saved_geometry_uses_defaults_then_clamps_to_a_small_work_area()
    {
        var display = CreateDisplay(0, @"\\.\DISPLAY1", 1920, 1080, true, 100, 200) with
        {
            WorkAreaWidth = 300,
            WorkAreaHeight = 200,
        };
        var saved = new WindowPlacementSettings
        {
            DisplayId = display.Id,
            Left = double.NaN,
            Top = double.PositiveInfinity,
            Width = double.PositiveInfinity,
            Height = -1,
        };

        var restored = WindowPlacementResolver.Restore(saved, [display]);

        Assert.Equal(display.Id, restored.DisplayId);
        Assert.Equal(300, restored.Width);
        Assert.Equal(200, restored.Height);
        Assert.Equal(100, restored.Left);
        Assert.Equal(200, restored.Top);
    }

    private static DisplayInfo CreateDisplay(
        int index,
        string id,
        int width,
        int height,
        bool isPrimary,
        int left,
        int top) => new(index, id, width, height, isPrimary)
        {
            Id = id,
            Left = left,
            Top = top,
            WorkAreaLeft = left,
            WorkAreaTop = top,
            WorkAreaWidth = width,
            WorkAreaHeight = height,
        };
}
