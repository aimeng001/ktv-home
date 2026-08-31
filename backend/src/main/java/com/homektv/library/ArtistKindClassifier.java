package com.homektv.library;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies labels that are not real artist identities before metadata work is
 * scheduled. The classifier is deliberately conservative: unknown regular
 * names remain eligible as people until an administrator changes the kind.
 */
public final class ArtistKindClassifier {
    private static final Set<String> UNATTRIBUTED = Set.of(
            "", "佚名", "未知", "未知歌手", "unknown", "unknow", "anonymous",
            "anon", "纯音乐", "instrumental"
    );
    private static final Set<String> VARIOUS = Set.of(
            "群星", "多人", "various artists", "various", "variousartist"
    );

    private ArtistKindClassifier() {}

    public static ArtistKind classify(String value) {
        String normalized = normalize(value);
        if (UNATTRIBUTED.contains(normalized)) return ArtistKind.UNATTRIBUTED;
        if (VARIOUS.contains(normalized)) return ArtistKind.VARIOUS;
        return ArtistKind.PERSON;
    }

    public static boolean isPlaceholder(String value) {
        ArtistKind kind = classify(value);
        return kind == ArtistKind.UNATTRIBUTED || kind == ArtistKind.VARIOUS;
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
