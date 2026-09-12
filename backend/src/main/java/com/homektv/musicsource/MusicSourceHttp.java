package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class MusicSourceHttp {
    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final MusicProvider provider;
    private final Set<String> allowedHosts;

    MusicSourceHttp(ObjectMapper mapper, MusicProvider provider, Set<String> allowedHosts) {
        this(mapper, provider, allowedHosts,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    MusicSourceHttp(ObjectMapper mapper, MusicProvider provider, Set<String> allowedHosts, HttpClient client) {
        this.mapper = mapper;
        this.provider = provider;
        this.allowedHosts = allowedHosts;
        this.client = Objects.requireNonNull(client, "client");
    }

    JsonNode get(String url, Map<String, ?> headers, Duration timeout) {
        return send(HttpRequest.newBuilder(checked(url)).GET(), headers, timeout);
    }

    JsonNode form(String url, Map<String, String> form, Map<String, ?> headers, Duration timeout) {
        String body = query(form);
        return send(HttpRequest.newBuilder(checked(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)), headers, timeout);
    }

    private JsonNode send(HttpRequest.Builder builder, Map<String, ?> headers, Duration timeout) {
        headers.forEach((key, value) -> builder.header(key, String.valueOf(value)));
        builder.timeout(timeout).header("Accept", "application/json");
        if (headers.keySet().stream().noneMatch(key -> "user-agent".equalsIgnoreCase(key))) {
            builder.header("User-Agent", "HomeKTV/0.1 metadata-only");
        }
        HttpRequest request = builder.build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new MusicSourceException(provider, "上游返回 HTTP " + response.statusCode());
                long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declaredLength > MAX_BODY_BYTES) throw new ResponseTooLargeException();
                return mapper.readTree(readAtMost(body, MAX_BODY_BYTES));
            }
        } catch (MusicSourceException ex) {
            throw ex;
        } catch (ResponseTooLargeException ex) {
            throw new MusicSourceException(provider, "上游响应过大", ex);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new MusicSourceException(provider, "平台请求失败", ex);
        }
    }

    static byte[] readAtMost(InputStream input, int limit) throws IOException {
        if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[Math.min(Math.max(limit, 1), 8192)];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            if (count > limit - total) throw new ResponseTooLargeException();
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static final class ResponseTooLargeException extends IOException {
        private ResponseTooLargeException() {
            super("response body exceeds configured limit");
        }
    }

    private URI checked(String url) {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHosts.contains(uri.getHost()))
            throw new MusicSourceException(provider, "请求地址不在平台白名单内");
        return uri;
    }

    static String query(Map<String, ?> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((key, value) -> {
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    static Map<String, String> stringMap() { return new LinkedHashMap<>(); }
}
