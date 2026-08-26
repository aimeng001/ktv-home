namespace HomeKtv.Windows.Display;

public sealed record DisplayInfo(int Index, string Name, int Width, int Height, bool IsPrimary)
{
    /** Stable Windows monitor identity; the index may change after a topology change. */
    public string Id { get; init; } = string.Empty;

    public int Left { get; init; }
    public int Top { get; init; }
    public int WorkAreaLeft { get; init; }
    public int WorkAreaTop { get; init; }
    public int WorkAreaWidth { get; init; }
    public int WorkAreaHeight { get; init; }

    public string StableId => string.IsNullOrWhiteSpace(Id) ? Name : Id;

    public int EffectiveWorkAreaWidth => WorkAreaWidth > 0 ? WorkAreaWidth : Width;
    public int EffectiveWorkAreaHeight => WorkAreaHeight > 0 ? WorkAreaHeight : Height;
    public int WorkAreaRight => WorkAreaLeft + EffectiveWorkAreaWidth;
    public int WorkAreaBottom => WorkAreaTop + EffectiveWorkAreaHeight;
    public string Label => $"{Name} ({Width}×{Height})";
}

public sealed class DisplayManager
{
    public IReadOnlyList<DisplayInfo> GetDisplays()
    {
        return System.Windows.Forms.Screen.AllScreens
            .Select((screen, index) => new DisplayInfo(
                index,
                screen.DeviceName,
                screen.Bounds.Width,
                screen.Bounds.Height,
                screen.Primary)
            {
                Id = screen.DeviceName,
                Left = screen.Bounds.Left,
                Top = screen.Bounds.Top,
                WorkAreaLeft = screen.WorkingArea.Left,
                WorkAreaTop = screen.WorkingArea.Top,
                WorkAreaWidth = screen.WorkingArea.Width,
                WorkAreaHeight = screen.WorkingArea.Height,
            })
            .ToArray();
    }

    public int SafeIndex(int requestedIndex)
    {
        var displays = GetDisplays();
        if (displays.Count == 0) return 0;
        return Math.Clamp(requestedIndex, 0, displays.Count - 1);
    }

    public DisplayInfo? ResolveTarget(string? savedDisplayId, int legacyIndex = 0) =>
        DisplaySelector.ResolveTarget(GetDisplays(), savedDisplayId, legacyIndex);
}
