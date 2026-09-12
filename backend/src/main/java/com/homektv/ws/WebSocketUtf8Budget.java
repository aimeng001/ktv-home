package com.homektv.ws;

/** Counts UTF-8 bytes without creating a second encoded copy of the message. */
final class WebSocketUtf8Budget {
    private WebSocketUtf8Budget() { }

    static Integer countAtMost(String value, int maxBytes) {
        long bytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2
                    : codePoint <= 0xffff ? 3 : 4;
            if (bytes > maxBytes) return null;
            index += Character.charCount(codePoint);
        }
        return (int) bytes;
    }

    static String truncateToByteLimit(String value, int maxBytes) {
        if (value == null || value.isEmpty() || maxBytes <= 0) return "";
        if (countAtMost(value, maxBytes) != null) return value;

        StringBuilder bounded = new StringBuilder(Math.min(value.length(), maxBytes));
        long bytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int codePointBytes = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2
                    : codePoint <= 0xffff ? 3 : 4;
            if (bytes + codePointBytes > maxBytes) break;
            bounded.appendCodePoint(codePoint);
            bytes += codePointBytes;
            index += Character.charCount(codePoint);
        }
        return bounded.toString();
    }
}
