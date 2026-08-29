package com.homektv.web.dto;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSongDtoTest {

    @Test
    void externalReadonlyFileIsReportedAsExternalSourceEvenWithoutSourcePath() {
        Song song = new Song();
        song.setId(7L);
        song.setTitle("晴天");
        song.setArtist("周杰伦");
        SongFile file = new SongFile();
        file.setFileRole("EXTERNAL_READ_ONLY");
        file.setSourcePath(null);

        assertThat(AdminSongDto.from(song, file).importSource()).isEqualTo("EXTERNAL_READ_ONLY");
    }

    @Test
    void legacyFileWithoutSourcePathRemainsUnknown() {
        Song song = new Song();
        song.setId(8L);
        SongFile file = new SongFile();
        file.setFileRole("LIBRARY");
        file.setSourcePath(null);

        assertThat(AdminSongDto.from(song, file).importSource()).isEqualTo("UNKNOWN");
    }
}
