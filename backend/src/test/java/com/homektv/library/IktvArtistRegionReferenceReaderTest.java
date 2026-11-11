package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class IktvArtistRegionReferenceReaderTest {
    private final IktvArtistRegionReferenceReader reader = new IktvArtistRegionReferenceReader();

    @Test
    void readsOnlyExactUnambiguousArtistKeysFromThePackagedReferenceCsv() throws Exception {
        try (InputStream input = new ClassPathResource("iktv/iktv-singers.csv").getInputStream()) {
            var snapshot = reader.read(input,
                    "2ae936dd56f3fa31f41e357847dff55cbbdafba3ec5e6e10fa2f3bb5ee6f8ada", 11_969);

            assertThat(snapshot.sourceRowCount()).isEqualTo(11_969);
            assertThat(snapshot.entries()).hasSize(11_934);
            assertThat(snapshot.conflictingKeyCount()).isEqualTo(6);
            assertThat(snapshot.entries()).contains(new IktvArtistRegionReferenceReader.Entry(
                    "周杰伦", "周杰伦", "港台", "男歌手", "港台男"));
            assertThat(snapshot.entries()).noneMatch(entry -> entry.artistKey().isBlank());
        }
    }

    @Test
    void rejectsModifiedOrUnexpectedCsvInsteadOfPublishingPartialRegionData() {
        byte[] modified = "name,category\n歌手,内地男\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reader.read(
                new java.io.ByteArrayInputStream(modified),
                "2ae936dd56f3fa31f41e357847dff55cbbdafba3ec5e6e10fa2f3bb5ee6f8ada", 11_969))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ARTIST_REFERENCE_SHA256_MISMATCH");
    }
}
