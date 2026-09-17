package com.homektv.web.dto;

import com.homektv.domain.Song;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SongDtoArtistAvatarTest {

    @Test
    void exposesAnArtistAvatarUrlForARealArtist() {
        Song song = new Song();
        song.setArtist("周杰伦");

        assertThat(SongDto.from(song).artistAvatarUrl())
                .isEqualTo("/api/artists/avatar?key=%E5%91%A8%E6%9D%B0%E4%BC%A6");
    }

    @Test
    void doesNotExposeAnAvatarUrlForPlaceholderCredits() {
        Song song = new Song();
        song.setArtist("佚名");

        assertThat(SongDto.from(song).artistAvatarUrl()).isNull();
    }

    @Test
    void exposesOnlyAValidEightDigitCatalogNumber() {
        Song song = new Song();
        song.setCatalogNumber("00123456");
        assertThat(SongDto.from(song).catalogNumber()).isEqualTo("00123456");

        song.setCatalogNumber("123");
        assertThat(SongDto.from(song).catalogNumber()).isEmpty();
    }
}
