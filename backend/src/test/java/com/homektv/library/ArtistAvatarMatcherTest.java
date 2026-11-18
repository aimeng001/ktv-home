package com.homektv.library;

import com.homektv.musicsource.ExternalArtist;
import com.homektv.musicsource.MusicProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtistAvatarMatcherTest {

    @Test
    void prefersExactArtistIdentityAndRejectsUnrelatedSearchResults() {
        ExternalArtist unrelated = new ExternalArtist(
                MusicProvider.QQ, "wrong", "周边歌手", List.of("别的名字"), "https://y.gtimg.cn/wrong.jpg");
        ExternalArtist exact = new ExternalArtist(
                MusicProvider.QQ, "zhou", "周杰伦", List.of(), "https://y.gtimg.cn/zhou.jpg");

        ArtistAvatarMatcher.Match match = ArtistAvatarMatcher.bestMatch("周杰伦", List.of(unrelated, exact)).orElseThrow();

        assertThat(match.artist()).isSameAs(exact);
        assertThat(match.confidence()).isEqualTo(1.0d);
    }

    @Test
    void acceptsOnlyAnExplicitAliasAtTheHighConfidenceThreshold() {
        ExternalArtist alias = new ExternalArtist(
                MusicProvider.NETEASE, "1", "Jay Chou", List.of("周杰伦"), "https://music.126.net/avatar.jpg");

        ArtistAvatarMatcher.Match match = ArtistAvatarMatcher.bestMatch("周杰伦", List.of(alias)).orElseThrow();

        assertThat(match.confidence()).isGreaterThanOrEqualTo(0.90d);
    }

    @Test
    void matchesTraditionalSimplifiedAndBracketedStageNames() {
        ExternalArtist simplified = new ExternalArtist(
                MusicProvider.QQ, "gem", "G.E.M.邓紫棋", List.of(), "https://y.gtimg.cn/gem.jpg");
        ExternalArtist bracketed = new ExternalArtist(
                MusicProvider.NETEASE, "jay", "周杰伦", List.of(), "https://music.126.net/jay.jpg");

        assertThat(ArtistAvatarMatcher.bestMatch("G.E.M. 鄧紫棋", List.of(simplified)))
                .isPresent()
                .get()
                .satisfies(match -> assertThat(match.artist().externalId()).isEqualTo("gem"));

        assertThat(ArtistAvatarMatcher.bestMatch("周杰倫 (Jay Chou)", List.of(bracketed)))
                .isPresent()
                .get()
                .satisfies(match -> assertThat(match.artist().externalId()).isEqualTo("jay"));

        assertThat(ArtistAvatarMatcher.cleanLookupName("周杰伦 (Jay Chou)")).isEqualTo("周杰伦");
    }
}
