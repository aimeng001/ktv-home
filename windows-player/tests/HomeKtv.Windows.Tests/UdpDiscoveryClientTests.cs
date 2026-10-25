using System.Net;
using System.Net.Sockets;
using System.Text;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class UdpDiscoveryClientTests
{
    [Fact]
    public void BroadcastAddresses_includes_global_broadcast_and_only_ipv4_destinations()
    {
        var addresses = UdpDiscoveryClient.BroadcastAddresses().ToArray();

        Assert.Contains(IPAddress.Broadcast, addresses);
        Assert.All(addresses, address => Assert.Equal(AddressFamily.InterNetwork, address.AddressFamily));
        Assert.Equal(addresses.Length, addresses.Distinct().Count());
    }

    [Fact]
    public void ToUInt32_uses_network_byte_order_for_an_ipv4_address()
    {
        Assert.Equal(0x7F000001u, UdpDiscoveryClient.ToUInt32(IPAddress.Loopback));
    }

    [Fact]
    public async Task DiscoverAsync_returns_immediately_for_a_zero_timeout_without_broadcast_targets()
    {
        Assert.NotNull(new UdpDiscoveryClient());
        var client = new UdpDiscoveryClient(() => [], DiscoveryProtocol.UdpPort);

        Assert.Empty(await client.DiscoverAsync(TimeSpan.Zero));
    }

    [Fact]
    public async Task DiscoverAsync_uses_only_the_injected_loopback_target_and_deduplicates_servers()
    {
        using var responder = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        var target = (IPEndPoint)responder.Client.LocalEndPoint!;
        var client = new UdpDiscoveryClient(() => [IPAddress.Loopback], target.Port);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));

        var discoverTask = client.DiscoverAsync(TimeSpan.FromMilliseconds(350), timeout.Token);
        var request = await responder.ReceiveAsync(timeout.Token);

        Assert.Equal(DiscoveryProtocol.Request, Encoding.UTF8.GetString(request.Buffer));
        Assert.Equal(IPAddress.Loopback, request.RemoteEndPoint.Address);

        var malformed = Encoding.UTF8.GetBytes("{");
        var response = Encoding.UTF8.GetBytes(
            "{\"service\":\"home-ktv\",\"protocolVersion\":1,\"name\":\"Loopback KTV\",\"port\":8080}");
        await responder.SendAsync(malformed, malformed.Length, request.RemoteEndPoint);
        await responder.SendAsync(response, response.Length, request.RemoteEndPoint);
        await responder.SendAsync(response, response.Length, request.RemoteEndPoint);

        var servers = await discoverTask.WaitAsync(TimeSpan.FromSeconds(2));

        var server = Assert.Single(servers);
        Assert.Equal("Loopback KTV", server.Name);
        Assert.Equal("http://127.0.0.1:8080/api/", server.Endpoint.ApiBaseUri.ToString());
    }

    [Fact]
    public async Task DiscoverAsync_returns_no_server_when_a_loopback_probe_times_out()
    {
        using var responder = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        var target = (IPEndPoint)responder.Client.LocalEndPoint!;
        var client = new UdpDiscoveryClient(() => [IPAddress.Loopback], target.Port);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));

        var discoverTask = client.DiscoverAsync(TimeSpan.FromMilliseconds(100), timeout.Token);
        var request = await responder.ReceiveAsync(timeout.Token);
        Assert.Equal(DiscoveryProtocol.Request, Encoding.UTF8.GetString(request.Buffer));

        Assert.Empty(await discoverTask.WaitAsync(TimeSpan.FromSeconds(2)));
    }

    [Fact]
    public async Task DiscoverAsync_stops_receiving_when_the_caller_cancels()
    {
        using var responder = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        var target = (IPEndPoint)responder.Client.LocalEndPoint!;
        var client = new UdpDiscoveryClient(() => [IPAddress.Loopback], target.Port);
        using var cancellation = new CancellationTokenSource();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));

        var discoverTask = client.DiscoverAsync(TimeSpan.FromSeconds(2), cancellation.Token);
        var request = await responder.ReceiveAsync(timeout.Token);
        Assert.Equal(DiscoveryProtocol.Request, Encoding.UTF8.GetString(request.Buffer));

        cancellation.Cancel();

        Assert.Empty(await discoverTask.WaitAsync(TimeSpan.FromSeconds(2)));
    }
}
