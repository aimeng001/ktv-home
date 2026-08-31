package com.homektv.library;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Builds a stable server-local avatar URL from the first real artist credit. */
public final class ArtistAvatarUrl {
    private ArtistAvatarUrl() {}

    public static String forCredit(String credit) {
        return ArtistCreditParser.parse(credit).stream()
                .filter(name -> !ArtistKindClassifier.isPlaceholder(name))
                .map(ArtistCreditParser::key)
                .filter(key -> !key.isBlank())
                .findFirst()
                .map(key -> "/api/artists/avatar?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8))
                .orElse(null);
    }

    /** Returns an avatar endpoint only when the profile has a cached file. */
    public static String forCachedKey(String artistKey, String avatarPath) {
        if (artistKey == null || artistKey.isBlank() || avatarPath == null || avatarPath.isBlank()) {
            return null;
        }
        return "/api/artists/avatar?key=" + URLEncoder.encode(artistKey, StandardCharsets.UTF_8);
    }
}
