package com.homektv.diagnostics;

import java.util.regex.Pattern;

/** Defense-in-depth text redaction for diagnostic output. */
public final class SecretRedactor {
    private static final String KEY = "(?:api[_-]?key|apikey|password|passwd|secret|access[_-]?token|refresh[_-]?token|client[_-]?token|token)";
    private static final Pattern USER_INFO = Pattern.compile("(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@");
    private static final Pattern AUTH_HEADER = Pattern.compile("(?im)^(\\s*(?:authorization|proxy-authorization)\\s*[:=]\\s*).+$");
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?im)^(\\s*(?:cookie|set-cookie)\\s*[:=]\\s*).+$");
    private static final Pattern QUOTED_PAIR = Pattern.compile(
            "(?i)([\\\"']?" + KEY + "[\\\"']?\\s*[:=]\\s*)([\\\"'])[^\\\"']*\\2");
    private static final Pattern RAW_PAIR = Pattern.compile(
            "(?i)(" + KEY + "\\s*[:=]\\s*)(?!\\[REDACTED])[^&\\s,;}\\\"]+");
    private static final Pattern KNOWN_TOKEN = Pattern.compile(
            "(?i)(?:sk-[a-z0-9_-]{16,}|github_pat_[a-z0-9_]{20,}|gh[opusr]_[a-z0-9]{20,})");

    private SecretRedactor() {}

    public static String redact(String input) {
        if (input == null || input.isEmpty()) return input;
        String value = USER_INFO.matcher(input).replaceAll("$1[REDACTED]@");
        value = AUTH_HEADER.matcher(value).replaceAll("$1[REDACTED]");
        value = COOKIE_HEADER.matcher(value).replaceAll("$1[REDACTED]");
        value = QUOTED_PAIR.matcher(value).replaceAll("$1$2[REDACTED]$2");
        value = RAW_PAIR.matcher(value).replaceAll("$1[REDACTED]");
        return KNOWN_TOKEN.matcher(value).replaceAll("[REDACTED]");
    }
}
