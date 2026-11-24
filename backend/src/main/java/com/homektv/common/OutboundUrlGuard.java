package com.homektv.common;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Single outbound HTTP &amp; SSRF validation choke point shared by AI and music-source clients.
 * Always disables automatic HTTP redirects so per-hop destination checks cannot be bypassed.
 */
public final class OutboundUrlGuard {

    @FunctionalInterface
    public interface AddressResolver {
        InetAddress[] resolve(String host) throws Exception;
    }

    private OutboundUrlGuard() {
    }

    public static HttpClient newNoRedirectHttpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static URI requireHttpsAllowedHost(String url, Set<String> allowedHosts,
                                              Function<String, RuntimeException> errorFactory) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (RuntimeException ex) {
            throw errorFactory.apply("请求地址格式无效");
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !allowedHosts.contains(host)) {
            throw errorFactory.apply("请求地址不在平台白名单内");
        }
        return uri;
    }

    public static URI requirePublicDestination(String url, boolean httpsOnly, Set<String> allowedHosts,
                                               AddressResolver resolver,
                                               Function<String, RuntimeException> invalidFactory,
                                               Function<String, RuntimeException> dnsFailureFactory) {
        if (url == null || url.isBlank()) throw invalidFactory.apply("URL 不能为空");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException ex) {
            throw invalidFactory.apply("URL 格式无效");
        }
        String scheme = uri.getScheme();
        boolean validScheme = httpsOnly
                ? "https".equalsIgnoreCase(scheme)
                : ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        String host = uri.getHost();
        if (!validScheme || host == null || host.isBlank()) {
            throw invalidFactory.apply("URL 协议或主机无效");
        }
        if (allowedHosts != null && !allowedHosts.isEmpty() && !matchesAllowedHost(allowedHosts, host)) {
            throw invalidFactory.apply("主机不在允许域名白名单内");
        }
        requireResolvedAddresses(host, false, resolver, invalidFactory, dnsFailureFactory);
        return uri;
    }

    public static boolean matchesAllowedHost(Set<String> allowedHosts, String host) {
        if (host == null || allowedHosts == null) return false;
        String lowerHost = host.toLowerCase(java.util.Locale.ROOT);
        for (String allowed : allowedHosts) {
            if (host.equalsIgnoreCase(allowed) || lowerHost.endsWith("." + allowed.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static AddressClassification classifyHost(String host, AddressResolver resolver,
                                                     Function<String, RuntimeException> dnsFailureFactory) {
        String normalizedHost = host.replace("[", "").replace("]", "");
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(normalizedHost);
        } catch (Exception e) {
            throw dnsFailureFactory.apply("主机无法解析");
        }
        boolean hasPrivate = false;
        boolean hasPublic = false;
        for (InetAddress address : addresses) {
            if (isPrivateAddress(address)) hasPrivate = true;
            else hasPublic = true;
        }
        return new AddressClassification(hasPrivate, hasPublic, addresses);
    }

    public static void requireResolvedAddresses(String host, boolean allowPrivateNetwork,
                                                AddressResolver resolver,
                                                Function<String, RuntimeException> invalidFactory,
                                                Function<String, RuntimeException> dnsFailureFactory) {
        AddressClassification classification = classifyHost(host, resolver, dnsFailureFactory);
        if (classification.hasPrivate() && !allowPrivateNetwork) {
            throw invalidFactory.apply("禁止访问本机或内网地址");
        }
    }

    public static boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            return isPrivateIpv4(bytes);
        }
        if (bytes.length == 16) {
            if (isIpv4Mapped(bytes)) return isPrivateIpv4(Arrays.copyOfRange(bytes, 12, 16));
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80);
        }
        return false;
    }

    private static boolean isPrivateIpv4(byte[] bytes) {
        if (bytes.length != 4) return false;
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        return first == 0 || first == 10 || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 192 && second == 0 && third == 0)
                || (first == 198 && (second == 18 || second == 19));
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) if (bytes[index] != 0) return false;
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    public record AddressClassification(boolean hasPrivate, boolean hasPublic, InetAddress[] addresses) {
    }
}
