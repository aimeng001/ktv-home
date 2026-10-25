package com.homektv.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaylistSongIdTest {

    @Test
    void equalityAndHashCodeUseBothCompositeKeyFields() {
        PlaylistSongId key = new PlaylistSongId(4L, 9L);
        PlaylistSongId equalKey = new PlaylistSongId(4L, 9L);

        assertThat(key.equals(key)).isTrue();
        assertThat(key).isEqualTo(equalKey);
        assertThat(key.hashCode()).isEqualTo(equalKey.hashCode());
        assertThat(key.equals(null)).isFalse();
        assertThat(key.equals("not a key")).isFalse();
        assertThat(key.equals(new PlaylistSongId(5L, 9L))).isFalse();
        assertThat(key.equals(new PlaylistSongId(4L, 10L))).isFalse();
    }
}
