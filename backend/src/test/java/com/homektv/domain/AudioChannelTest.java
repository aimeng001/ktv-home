package com.homektv.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioChannelTest {

    @Test
    void resolvesOppositeChannels() {
        assertThat(AudioChannel.LEFT.opposite()).isEqualTo(AudioChannel.RIGHT);
        assertThat(AudioChannel.RIGHT.opposite()).isEqualTo(AudioChannel.LEFT);
    }

    @Test
    void parsesChannelValuesWithStableFallbacks() {
        assertThat(AudioChannel.from(" right ")).isEqualTo(AudioChannel.RIGHT);
        assertThat(AudioChannel.from(null)).isEqualTo(AudioChannel.LEFT);
        assertThat(AudioChannel.from("  ")).isEqualTo(AudioChannel.LEFT);
        assertThat(AudioChannel.from("unknown", AudioChannel.RIGHT)).isEqualTo(AudioChannel.RIGHT);
    }
}
