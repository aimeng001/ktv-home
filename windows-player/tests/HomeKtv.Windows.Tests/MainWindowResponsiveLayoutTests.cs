using System.Globalization;
using System.Xml.Linq;

namespace HomeKtv.Windows.Tests;

public sealed class MainWindowResponsiveLayoutTests
{
    private static readonly XNamespace Presentation = "http://schemas.microsoft.com/winfx/2006/xaml/presentation";
    private static readonly XNamespace Xaml = "http://schemas.microsoft.com/winfx/2006/xaml";

    [Fact]
    public void Minimum_width_connection_row_uses_flexible_field_columns_without_changing_actions()
    {
        var path = LocateMainWindow();
        var window = XDocument.Load(path).Root!;
        var minimumWidth = double.Parse(window.Attribute("MinWidth")!.Value, CultureInfo.InvariantCulture);
        var rootGrid = window.Element(Presentation + "Grid")!;
        var connectionRow = rootGrid.Descendants()
            .Single(element => element.Attribute("Grid.Row")?.Value == "0");

        Assert.Equal(680, minimumWidth);
        Assert.Equal("Grid", connectionRow.Name.LocalName);

        var columnDefinitions = connectionRow.Elements()
            .Single(element => element.Name.LocalName == "Grid.ColumnDefinitions")
            .Elements(Presentation + "ColumnDefinition")
            .Select(element => element.Attribute("Width")!.Value)
            .ToArray();
        Assert.Equal(new[] { "Auto", "2*", "Auto", "*", "72", "72" }, columnDefinitions);

        var address = connectionRow.Descendants().Single(element => element.Attribute(Xaml + "Name")?.Value == "ServerAddressText");
        var credential = connectionRow.Descendants().Single(element => element.Attribute(Xaml + "Name")?.Value == "PlayerCredentialText");
        Assert.Null(address.Attribute("Width"));
        Assert.Null(credential.Attribute("Width"));
        Assert.Equal("1", address.Attribute("Grid.Column")?.Value);
        Assert.Equal("3", credential.Attribute("Grid.Column")?.Value);

        var actions = connectionRow.Elements(Presentation + "Button").ToList();
        Assert.Equal(new[] { "发现", "连接" }, actions.Select(action => action.Attribute("Content")?.Value));
        Assert.All(actions, action => Assert.NotNull(action.Attribute("Click")));
    }

    private static string LocateMainWindow()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null)
        {
            var candidate = Path.Combine(directory.FullName, "src", "HomeKtv.Windows", "MainWindow.xaml");
            if (File.Exists(candidate)) return candidate;
            directory = directory.Parent;
        }

        throw new FileNotFoundException("Cannot locate src/HomeKtv.Windows/MainWindow.xaml from test output.");
    }
}
