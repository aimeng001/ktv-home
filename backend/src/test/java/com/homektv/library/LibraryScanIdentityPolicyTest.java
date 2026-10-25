package com.homektv.library;

import com.homektv.domain.Song;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryScanIdentityPolicyTest {

    @Test
    void sameSongUsesIdentityAndRejectsMissingOrDifferentIds() {
        Song first = new Song();
        Song second = new Song();

        assertThat(LibraryScanService.sameSong(null, first)).isFalse();
        assertThat(LibraryScanService.sameSong(first, null)).isFalse();
        assertThat(LibraryScanService.sameSong(first, first)).isTrue();
        assertThat(LibraryScanService.sameSong(first, second)).isFalse();

        first.setId(7L);
        second.setId(7L);
        assertThat(LibraryScanService.sameSong(first, second)).isTrue();
        second.setId(8L);
        assertThat(LibraryScanService.sameSong(first, second)).isFalse();
    }

    @Test
    void identifiesPendingProbeAndFastIndexSongsAsProvisional() {
        assertThat(LibraryScanService.isProvisionalSong(null)).isFalse();

        Song pendingProbe = new Song();
        pendingProbe.setMediaType(MediaClassifier.PENDING_PROBE);
        assertThat(LibraryScanService.isProvisionalSong(pendingProbe)).isTrue();

        Song fastIndexed = new Song();
        fastIndexed.setFingerprint("fast-index-abc");
        assertThat(LibraryScanService.isProvisionalSong(fastIndexed)).isTrue();

        Song ordinary = new Song();
        ordinary.setMediaType("VIDEO");
        ordinary.setFingerprint("sha256:abc");
        assertThat(LibraryScanService.isProvisionalSong(ordinary)).isFalse();
    }

    @Test
    void detectsManualIdentityLocksOnlyForTitleOrArtist() {
        assertThat(LibraryScanService.hasManualIdentityOverride(null)).isFalse();

        Song unlocked = new Song();
        assertThat(LibraryScanService.hasManualIdentityOverride(unlocked)).isFalse();

        unlocked.lockMetadata("title");
        assertThat(LibraryScanService.hasManualIdentityOverride(unlocked)).isTrue();

        Song artistLocked = new Song();
        artistLocked.lockMetadata("artist");
        assertThat(LibraryScanService.hasManualIdentityOverride(artistLocked)).isTrue();

        Song unrelatedLock = new Song();
        unrelatedLock.lockMetadata("language");
        assertThat(LibraryScanService.hasManualIdentityOverride(unrelatedLock)).isFalse();
    }

    @Test
    void derivesSidecarLyricsFromDottedExtensionlessAndDotfileNames() {
        assertThat(LibraryScanService.sidecarLyricOf(Path.of("music", "song.live.mp4")))
                .isEqualTo(Path.of("music", "song.live.lrc"));
        assertThat(LibraryScanService.sidecarLyricOf(Path.of("music", "song")))
                .isEqualTo(Path.of("music", "song.lrc"));
        assertThat(LibraryScanService.sidecarLyricOf(Path.of("music", ".nomedia")))
                .isEqualTo(Path.of("music", ".nomedia.lrc"));
    }
}
