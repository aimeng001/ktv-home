namespace HomeKtv.Windows.Display;

public sealed record DisplayInfo(int Index, string Name, int Width, int Height, bool IsPrimary);

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
                screen.Primary))
            .ToArray();
    }

    public int SafeIndex(int requestedIndex)
    {
        var displays = GetDisplays();
        if (displays.Count == 0) return 0;
        return Math.Clamp(requestedIndex, 0, displays.Count - 1);
    }
}
