package com.homektv.musicsource;

import com.homektv.domain.Song;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalTrackMatcherTest {
    private final ExternalTrackMatcher matcher = new ExternalTrackMatcher();

    @Test
    void scoreNormalizesDisplayMarkersCatalogSuffixesUnicodeAndArtistSeparators() {
        Song song = song("晴天 (MTV) -国语", "周杰伦·Jay Chou", 180_000);
        ExternalTrack track = track("晴天（KTV）－国语", List.of("周杰伦", "Jay Chou"), 180_000);

        assertThat(matcher.score(song, track)).isEqualTo(1.0);
    }

    @Test
    void scoreUsesContainmentSimilarityAndNeutralDurationWhenMissing() {
        Song song = song("abc song", "alice", 180_000);
        ExternalTrack track = track("song", List.of("alice"), null);

        assertThat(matcher.score(song, track)).isEqualTo(0.714);
    }

    @Test
    void scoreUsesEditDistanceAndClampsLargeDurationDifferences() {
        Song song = song("sky", "alice", 180_000);
        ExternalTrack track = track("skz", List.of("alicia"), 210_000);

        assertThat(matcher.score(song, track)).isEqualTo(0.6);
    }

    @Test
    void emptyValuesDoNotMatchEachOtherAndMissingDurationUsesNeutralScore() {
        Song song = song(null, null, 0);
        ExternalTrack track = track(null, null, 0);

        assertThat(matcher.score(song, track)).isEqualTo(0.05);
    }

    @Test
    void groupKeyNormalizesTextAndUsesRoundedThirtySecondDurationBuckets() {
        ExternalTrack beforeBoundary = track("晴天（MV）-国语", List.of("周杰伦·Jay"), 14_999);
        ExternalTrack atBoundary = track("晴天 (KTV) -国语", List.of("周杰伦 Jay"), 15_000);
        ExternalTrack unknownDuration = track("晴天", List.of("周杰伦 Jay"), null);

        assertThat(matcher.groupKey(beforeBoundary)).isEqualTo("晴天|周杰伦jay|0");
        assertThat(matcher.groupKey(atBoundary)).isEqualTo("晴天|周杰伦jay|1");
        assertThat(matcher.groupKey(unknownDuration)).isEqualTo("晴天|周杰伦jay|-1");
    }

    private static Song song(String title, String artist, int durationMs) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setDurationMs(durationMs);
        return song;
    }

    private static ExternalTrack track(String title, List<String> artists, Integer durationMs) {
        return new ExternalTrack(MusicProvider.QQ, "external-id", title, artists, null,
                durationMs, null, null, null, null, null);
    }
}
