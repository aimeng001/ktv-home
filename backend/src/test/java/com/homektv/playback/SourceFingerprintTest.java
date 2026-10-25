package com.homektv.playback;

import com.homektv.domain.SongFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SourceFingerprintTest {

    @Test
    void normalizesPathAndIncludesAvailableFileIdentityAndMetadata() {
        SongFile unidentified = new SongFile();
        String withoutIdentity = SourceFingerprint.from(unidentified, Path.of("library", "..", "song.mkv"), 100, 200);
        String normalizedPath = SourceFingerprint.from(unidentified, Path.of("song.mkv"), 100, 200);

        SongFile identified = new SongFile();
        identified.setFileIdentity("device:inode:42");
        String withIdentity = SourceFingerprint.from(identified, Path.of("song.mkv"), 100, 200);

        assertThat(withoutIdentity).matches("[0-9a-f]{64}").isEqualTo(normalizedPath).isNotEqualTo(withIdentity);
        assertThat(SourceFingerprint.from(identified, Path.of("song.mkv"), 101, 200)).isNotEqualTo(withIdentity);
        assertThat(SourceFingerprint.from(identified, Path.of("song.mkv"), 100, 201)).isNotEqualTo(withIdentity);
        assertThat(SourceFingerprint.from(identified, Path.of("other.mkv"), 100, 200)).isNotEqualTo(withIdentity);
    }
}
