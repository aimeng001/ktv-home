package com.homektv.library;

import com.homektv.domain.Song;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SongMetadataWritePolicyTest {

    @Test
    void protectsManualLockedAndStructuredFilenameFieldsUniformly() {
        Song song = new Song();
        song.setTitle("晴天");
        song.setArtist("周杰伦");
        song.setLanguage("国语");
        song.setMetadataProvenance("""
                {"title":{"source":"filename"},"artist":{"source":"filename"}}
                """);
        song.lockMetadata("vocalForm");

        assertThat(SongMetadataWritePolicy.isProtectedField(song, "vocalForm", false)).isTrue();
        assertThat(SongMetadataWritePolicy.isProtectedField(song, "title", true)).isTrue();
        assertThat(SongMetadataWritePolicy.isProtectedField(song, "artist", true)).isTrue();
        assertThat(SongMetadataWritePolicy.isProtectedField(song, "language", true)).isTrue();
        assertThat(SongMetadataWritePolicy.isProtectedField(song, "title", false)).isFalse();
        assertThat(SongMetadataWritePolicy.isProtectedField(song, "language", false)).isFalse();
    }

    @Test
    void appliesPinyinAndPromotesValidUnrecognizedSongs() {
        Song song = new Song();
        song.setStatus("unrecognized");
        SongMetadataWritePolicy.applyTitleWithPinyin(song, " 稻香 ");
        SongMetadataWritePolicy.applyArtistWithPinyin(song, " 周杰伦 ");

        boolean promoted = SongMetadataWritePolicy.promoteUnrecognizedIfValid(song);

        assertThat(promoted).isTrue();
        assertThat(song.getStatus()).isEqualTo("ok");
        assertThat(song.getTitle()).isEqualTo("稻香");
        assertThat(song.getTitleInit()).isNotBlank();
        assertThat(song.getArtist()).isEqualTo("周杰伦");
        assertThat(song.getArtistInit()).isNotBlank();
    }
}
