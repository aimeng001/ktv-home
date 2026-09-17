package com.homektv.ai;

import com.homektv.web.ApiException;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Validates AI destinations before an API key is sent.
 *
 * Public HTTPS providers remain supported. Local HTTP providers require an
 * explicit opt-in because the AI client sends the configured bearer key.
 */
public final class AiBaseUrlPolicy {
    private AiBaseUrlPolicy() { }

    public static String normalize(String value) {
        if (value == null || value.trim().isBlank()) return "";
        String normalized = value.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(normalized);
            if (!(isHttp(uri) || isHttps(uri)) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return normalized;
        } catch (Exception e) {
            throw invalid("API Base URL 必须是完整且不带凭据、查询参数或片段的 HTTP(S) 地址");
        }
    }

    public static String normalizeAndValidate(String value, boolean allowPrivateNetwork) {
        String normalized = normalize(value);
        requireSafeNormalized(normalized, allowPrivateNetwork);
        return normalized;
    }

    public static void requireSafe(String value, boolean allowPrivateNetwork) {
        requireSafeNormalized(normalize(value), allowPrivateNetwork);
    }

    /** Returns true only when every resolved address is private/local. */
    public static boolean isPrivateNetwork(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) return false;
        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return false;
            host = host.replace("[", "").replace("]", "");
            InetAddress[] addresses = InetAddress.getAllByName(host);
            return addresses.length > 0 && Arrays.stream(addresses).allMatch(AiBaseUrlPolicy::isPrivate);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void requireSafeNormalized(String normalized, boolean allowPrivateNetwork) {
        if (normalized.isBlank()) return;
        URI uri = URI.create(normalized);
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw invalid("API Base URL 主机地址无效");
        host = host.replace("[", "").replace("]", "");

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw invalid("API Base URL 主机无法解析，已拒绝发送 API Key");
        }
        boolean privateAddress = false;
        boolean publicAddress = false;
        for (InetAddress address : addresses) {
            if (isPrivate(address)) privateAddress = true;
            else publicAddress = true;
        }

        if (privateAddress && !allowPrivateNetwork) {
            throw invalid("API Base URL 禁止访问本机或内网地址；本地 AI 需明确开启 KTV_AI_ALLOW_PRIVATE_NETWORK=true");
        }
        if (isHttp(uri) && (!allowPrivateNetwork || !privateAddress || publicAddress)) {
            throw invalid("公共 AI API 必须使用 HTTPS；仅允许在明确开启内网 AI 时使用纯内网 HTTP");
        }
    }

    private static boolean isHttp(URI uri) { return "http".equalsIgnoreCase(uri.getScheme()); }
    private static boolean isHttps(URI uri) { return "https".equalsIgnoreCase(uri.getScheme()); }

    private static boolean isPrivate(InetAddress address) {
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

    private static ApiException invalid(String message) {
        return new ApiException("INVALID_AI_CONFIG", message);
    }
}
