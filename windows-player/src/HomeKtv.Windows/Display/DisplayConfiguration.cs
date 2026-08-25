using HomeKtv.Windows.Settings;

namespace HomeKtv.Windows.Display;

public static class DisplaySelector
{
    public static DisplayInfo? ResolveTarget(
        IReadOnlyList<DisplayInfo> displays,
        string? savedDisplayId,
        int legacyIndex = 0)
    {
        if (displays.Count == 0) return null;

        if (!string.IsNullOrWhiteSpace(savedDisplayId))
        {
            var saved = displays.FirstOrDefault(display =>
                string.Equals(display.StableId, savedDisplayId, StringComparison.OrdinalIgnoreCase));
            if (saved is not null) return saved;

            // A remembered monitor disappeared. Do not use its old index for a
            // different physical monitor; prefer the primary display instead.
            return displays.FirstOrDefault(display => display.IsPrimary) ?? displays[0];
        }

        // DisplayIndex is retained for settings written by the first Windows
        // player version, which did not persist a stable monitor identity.
        if (legacyIndex >= 0 && legacyIndex < displays.Count)
        {
            return displays[legacyIndex];
        }

        return displays.FirstOrDefault(display => display.IsPrimary) ?? displays[0];
    }
}

public static class WindowPlacementResolver
{
    private const double DefaultWidth = 820;
    private const double DefaultHeight = 520;
    private const double MinimumWidth = 680;
    private const double MinimumHeight = 430;

    public static WindowPlacementSettings Restore(
        WindowPlacementSettings saved,
        IReadOnlyList<DisplayInfo> displays)
    {
        var width = PositiveOrDefault(saved.Width, DefaultWidth);
        var height = PositiveOrDefault(saved.Height, DefaultHeight);
        var target = DisplaySelector.ResolveTarget(displays, saved.DisplayId);
        if (target is null)
        {
            return new WindowPlacementSettings
            {
                DisplayId = null,
                Width = width,
                Height = height,
                IsMaximized = saved.IsMaximized,
            };
        }

        var workAreaWidth = Math.Max(1, target.EffectiveWorkAreaWidth);
        var workAreaHeight = Math.Max(1, target.EffectiveWorkAreaHeight);
        width = Math.Clamp(width, Math.Min(MinimumWidth, workAreaWidth), workAreaWidth);
        height = Math.Clamp(height, Math.Min(MinimumHeight, workAreaHeight), workAreaHeight);

        var savedForTarget = string.Equals(
            saved.DisplayId,
            target.StableId,
            StringComparison.OrdinalIgnoreCase);
        var left = savedForTarget && IsFinite(saved.Left)
            ? saved.Left!.Value
            : target.WorkAreaLeft + (workAreaWidth - width) / 2d;
        var top = savedForTarget && IsFinite(saved.Top)
            ? saved.Top!.Value
            : target.WorkAreaTop + (workAreaHeight - height) / 2d;

        var rightLimit = target.WorkAreaRight - width;
        var bottomLimit = target.WorkAreaBottom - height;
        left = Math.Clamp(left, target.WorkAreaLeft, Math.Max(target.WorkAreaLeft, rightLimit));
        top = Math.Clamp(top, target.WorkAreaTop, Math.Max(target.WorkAreaTop, bottomLimit));

        return new WindowPlacementSettings
        {
            DisplayId = target.StableId,
            Left = left,
            Top = top,
            Width = width,
            Height = height,
            IsMaximized = saved.IsMaximized,
        };
    }

    private static bool IsFinite(double? value) => value is { } number && double.IsFinite(number);

    private static double PositiveOrDefault(double value, double fallback) =>
        double.IsFinite(value) && value > 0 ? value : fallback;
}
