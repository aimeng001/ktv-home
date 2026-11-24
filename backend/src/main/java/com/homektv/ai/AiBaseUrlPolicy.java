package com.homektv.ai;

import com.homektv.common.ApiException;
import com.homektv.common.OutboundUrlGuard;

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

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

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
        requireSafeNormalized(normalized, allowPrivateNetwork, InetAddress::getAllByName);
        return normalized;
    }

    public static void requireSafe(String value, boolean allowPrivateNetwork) {
        requireSafe(value, allowPrivateNetwork, InetAddress::getAllByName);
    }

    static void requireSafe(String value, boolean allowPrivateNetwork, AddressResolver addressResolver) {
        requireSafeNormalized(normalize(value), allowPrivateNetwork, addressResolver);
    }

    /** Returns true only when every resolved address is private/local. */
    public static boolean isPrivateNetwork(String value) {
        return isPrivateNetwork(value, InetAddress::getAllByName);
    }

    static boolean isPrivateNetwork(String value, AddressResolver addressResolver) {
        String normalized = normalize(value);
        if (normalized.isBlank()) return false;
        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return false;
            host = host.replace("[", "").replace("]", "");
            InetAddress[] addresses = addressResolver.resolve(host);
            return addresses.length > 0 && Arrays.stream(addresses).allMatch(AiBaseUrlPolicy::isPrivate);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void requireSafeNormalized(String normalized, boolean allowPrivateNetwork,
                                              AddressResolver addressResolver) {
        if (normalized.isBlank()) return;
        URI uri = URI.create(normalized);
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw invalid("API Base URL 主机地址无效");
        OutboundUrlGuard.AddressClassification resolved = OutboundUrlGuard.classifyHost(
                host,
                addressResolver::resolve,
                ignored -> invalid("API Base URL 主机无法解析，已拒绝发送 API Key"));
        boolean privateAddress = resolved.hasPrivate();
        boolean publicAddress = resolved.hasPublic();

        if (privateAddress && !allowPrivateNetwork) {
            throw invalid("API Base URL 禁止访问本机或内网地址；本地 AI 需明确开启 KTV_AI_ALLOW_PRIVATE_NETWORK=true");
        }
        if (isHttp(uri) && (!allowPrivateNetwork || !privateAddress || publicAddress)) {
            throw invalid("公共 AI API 必须使用 HTTPS；仅允许在明确开启内网 AI 时使用纯内网 HTTP");
        }
    }

    private static boolean isHttp(URI uri) { return "http".equalsIgnoreCase(uri.getScheme()); }
    private static boolean isHttps(URI uri) { return "https".equalsIgnoreCase(uri.getScheme()); }

    public static boolean isPrivate(InetAddress address) {
        return OutboundUrlGuard.isPrivateAddress(address);
    }

    private static ApiException invalid(String message) {
        return new ApiException("INVALID_AI_CONFIG", message);
    }
}
