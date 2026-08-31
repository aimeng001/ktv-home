package com.homektv.library;

import com.homektv.musicsource.ExternalArtist;

import java.util.Collection;
import java.util.Optional;

/** Matches an external singer result to a local identity before downloading an avatar. */
public final class ArtistAvatarMatcher {
    private static final double ALIAS_CONFIDENCE = 0.95d;

    private ArtistAvatarMatcher() {}

    public static Optional<Match> bestMatch(String expectedName, Collection<ExternalArtist> candidates) {
        String expectedKey = ArtistCreditParser.key(expectedName);
        if (expectedKey.isBlank() || candidates == null) return Optional.empty();

        Match best = null;
        for (ExternalArtist candidate : candidates) {
            if (candidate == null || candidate.displayName() == null) continue;
            boolean exact = expectedKey.equals(ArtistCreditParser.key(candidate.displayName()));
            boolean alias = candidate.aliases() != null && candidate.aliases().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .anyMatch(value -> expectedKey.equals(ArtistCreditParser.key(value)));
            if (!exact && !alias) continue;
            Match current = new Match(candidate, exact ? 1.0d : ALIAS_CONFIDENCE);
            if (best == null || current.confidence() > best.confidence()) best = current;
        }
        return Optional.ofNullable(best).filter(match -> match.confidence() >= 0.90d);
    }

    public record Match(ExternalArtist artist, double confidence) {}
}
