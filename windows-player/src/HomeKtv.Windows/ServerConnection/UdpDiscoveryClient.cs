using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace HomeKtv.Windows.ServerConnection;

public sealed class UdpDiscoveryClient
{
    public async Task<IReadOnlyList<DiscoveredServer>> DiscoverAsync(
        TimeSpan timeout,
        CancellationToken cancellationToken = default)
    {
        using var client = new UdpClient(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true,
        };
        var request = Encoding.UTF8.GetBytes(DiscoveryProtocol.Request);
        foreach (var address in BroadcastAddresses())
        {
            await client.SendAsync(request, request.Length, new IPEndPoint(address, DiscoveryProtocol.UdpPort))
                .ConfigureAwait(false);
        }

        var results = new Dictionary<string, DiscoveredServer>(StringComparer.OrdinalIgnoreCase);
        var deadline = DateTime.UtcNow + timeout;
        while (!cancellationToken.IsCancellationRequested && DateTime.UtcNow < deadline)
        {
            var remaining = deadline - DateTime.UtcNow;
            if (remaining <= TimeSpan.Zero) break;
            using var receiveCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            receiveCancellation.CancelAfter(remaining);
            try
            {
                var response = await client.ReceiveAsync(receiveCancellation.Token).ConfigureAwait(false);
                var parsed = DiscoveryResponseParser.Parse(
                    Encoding.UTF8.GetString(response.Buffer), response.RemoteEndPoint.Address.ToString());
                if (parsed is not null) results[parsed.Endpoint.ApiBaseUri.ToString()] = parsed;
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (SocketException)
            {
                break;
            }
        }

        return results.Values.ToArray();
    }

    private static IEnumerable<IPAddress> BroadcastAddresses()
    {
        var result = new HashSet<IPAddress> { IPAddress.Broadcast };
        foreach (var network in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (network.OperationalStatus != OperationalStatus.Up
                || network.NetworkInterfaceType == NetworkInterfaceType.Loopback)
            {
                continue;
            }

            foreach (var address in network.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily != AddressFamily.InterNetwork
                    || address.IPv4Mask is null)
                {
                    continue;
                }

                var ip = ToUInt32(address.Address);
                var mask = ToUInt32(address.IPv4Mask);
                result.Add(new IPAddress(ip | ~mask));
            }
        }
        return result;
    }

    private static uint ToUInt32(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        if (BitConverter.IsLittleEndian) Array.Reverse(bytes);
        return BitConverter.ToUInt32(bytes, 0);
    }
}
