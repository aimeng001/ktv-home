package com.homektv.web.dto;

import com.homektv.domain.Song;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void exposesOnlyValidEightToTenDigitCatalogNumbersAsStrings() {
        Song song = new Song();
        song.setCatalogNumber("00123456");
        assertThat(SongDto.from(song).catalogNumber()).isEqualTo("00123456");

        song.setCatalogNumber("123456789");
        assertThat(SongDto.from(song).catalogNumber()).isEqualTo("123456789");

        song.setCatalogNumber("0012345678");
        assertThat(SongDto.from(song).catalogNumber()).isEqualTo("0012345678");
        assertThat(SongDetailDto.from(song, List.of()).catalogNumber()).isEqualTo("0012345678");

        song.setCatalogNumber("123");
        assertThat(SongDto.from(song).catalogNumber()).isEmpty();

        song.setCatalogNumber("12345678901");
        assertThat(SongDto.from(song).catalogNumber()).isEmpty();
    }

    @Test
    void exposesAvailabilityWithoutChangingLegacyFields() {
        Song song = new Song();
        song.setId(9L);
        song.setTitle("准备中");
        song.setArtist("韩红");

        SongDto dto = SongDto.from(song, false, "SONG_NOT_READY");

        assertThat(dto.id()).isEqualTo(9L);
        assertThat(dto.title()).isEqualTo("准备中");
        assertThat(dto.playable()).isFalse();
        assertThat(dto.unavailableReason()).isEqualTo("SONG_NOT_READY");
    }

    @Test
    void exposesStoredPinyinFieldsForSearchResultHighlighting() {
        Song song = new Song();
        song.setTitlePy("haojiubujian");
        song.setTitleInit("HJBJ");
        song.setArtistPy("zhouxingchi");
        song.setArtistInit("ZXC");

        SongDto dto = SongDto.from(song);

        assertThat(dto.titlePinyin()).isEqualTo("haojiubujian");
        assertThat(dto.titleInitials()).isEqualTo("HJBJ");
        assertThat(dto.artistPinyin()).isEqualTo("zhouxingchi");
        assertThat(dto.artistInitials()).isEqualTo("ZXC");
    }

}
