package com.homektv.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryMetadataResolverTest {
    @Test
    void structuredFilenameWinsOverContainerTag() {
        var resolved = LibraryMetadataResolver.resolve(
                new LibraryMetadataResolver.Candidates(
                        false, null, true,
                        new LibraryMetadataResolver.Value("望", "filename"),
                        new LibraryMetadataResolver.Value("测试文件", "container_tag")));

        assertThat(resolved.text()).isEqualTo("望");
        assertThat(resolved.source()).isEqualTo("filename");
    }

    @Test
    void manualValueWinsOverStructuredFilename() {
        var manual = new LibraryMetadataResolver.Value("人工歌名", "manual");
        var resolved = LibraryMetadataResolver.resolve(
                new LibraryMetadataResolver.Candidates(
                        true, manual, true,
                        new LibraryMetadataResolver.Value("望", "filename"),
                        new LibraryMetadataResolver.Value("测试文件", "container_tag")));

        assertThat(resolved).isEqualTo(manual);
    }

    @Test
    void structuredFilenameRequiresAllFourFields() {
        assertThat(LibraryMetadataResolver.isStructured(
                ParsedMeta.of("望", "韩红", "国语", "流行"))).isTrue();
        assertThat(LibraryMetadataResolver.isStructured(
                ParsedMeta.of("望", "韩红"))).isFalse();
    }
}
