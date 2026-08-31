package com.homektv.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArtistKindClassifierTest {

    @Test
    void classifiesPlaceholderNamesAsNonAttributableArtists() {
        assertThat(ArtistKindClassifier.classify("佚名")).isEqualTo(ArtistKind.UNATTRIBUTED);
        assertThat(ArtistKindClassifier.classify("未知歌手")).isEqualTo(ArtistKind.UNATTRIBUTED);
        assertThat(ArtistKindClassifier.classify("Unknown")).isEqualTo(ArtistKind.UNATTRIBUTED);
    }

    @Test
    void classifiesVariousArtistsSeparatelyFromUnattributed() {
        assertThat(ArtistKindClassifier.classify("群星")).isEqualTo(ArtistKind.VARIOUS);
        assertThat(ArtistKindClassifier.classify("Various Artists")).isEqualTo(ArtistKind.VARIOUS);
    }

    @Test
    void keepsRegularNamesEligibleForArtistMetadata() {
        assertThat(ArtistKindClassifier.classify("周杰伦")).isEqualTo(ArtistKind.PERSON);
    }
}
