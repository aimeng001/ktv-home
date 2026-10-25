package com.homektv.library;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagReaderTest {

    @Test
    void readsAndNormalizesTextTagsLyricsAndPngCover() throws Exception {
        Tag tag = mock(Tag.class);
        when(tag.getFirst(FieldKey.TITLE)).thenReturn("  歌名  ");
        when(tag.getFirst(FieldKey.ARTIST)).thenReturn("  歌手  ");
        when(tag.getFirst(FieldKey.LANGUAGE)).thenReturn("  粤语  ");
        when(tag.getFirst(FieldKey.LYRICS)).thenReturn("  第一行\n第二行  ");
        Artwork artwork = artwork(new byte[]{1, 2, 3}, "image/png");
        when(tag.getFirstArtwork()).thenReturn(artwork);

        TagInfo info = reader(tag).read(new File("synthetic.mp3"));

        assertThat(info.getTitle()).isEqualTo("歌名");
        assertThat(info.getArtist()).isEqualTo("歌手");
        assertThat(info.getLanguage()).isEqualTo("粤语");
        assertThat(info.getEmbeddedLyric()).isEqualTo("第一行\n第二行");
        assertThat(info.getCoverImage()).containsExactly(1, 2, 3);
        assertThat(info.getCoverExt()).isEqualTo("png");
    }

    @Test
    void skipsParserWhenBudgetIsUnsafeAndReturnsEmptyInfoForMissingTag() throws Exception {
        AtomicInteger parserCalls = new AtomicInteger();
        TagReader unsafeReader = new TagReader(file -> {
            parserCalls.incrementAndGet();
            return mock(AudioFile.class);
        }, ignored -> TagBudget.UNSAFE);

        TagInfo unsafe = unsafeReader.read(new File("unsafe.mp3"));

        assertEmpty(unsafe);
        assertThat(parserCalls).hasValue(0);

        AudioFile noTagAudio = mock(AudioFile.class);
        when(noTagAudio.getTag()).thenReturn(null);
        TagInfo noTag = new TagReader(ignored -> noTagAudio, ignored -> TagBudget.SAFE)
                .read(new File("untagged.mp3"));

        assertEmpty(noTag);
    }

    @Test
    void catchesProbeAndParserFailuresAndKeepsAnyMetadataAlreadyRead() throws Exception {
        TagReader probeFailure = new TagReader(ignored -> mock(AudioFile.class), ignored -> {
            throw new IOException("synthetic probe failure");
        });
        assertEmpty(probeFailure.read(new File("probe-error.mp3")));

        TagReader parserFailure = new TagReader(ignored -> {
            throw new IOException("synthetic parser failure");
        }, ignored -> TagBudget.SAFE);
        assertEmpty(parserFailure.read(new File("parser-error.mp3")));

        Tag partialTag = mock(Tag.class);
        when(partialTag.getFirst(FieldKey.TITLE)).thenReturn("  Partial title ");
        when(partialTag.getFirst(FieldKey.ARTIST)).thenThrow(new IllegalStateException("bad artist frame"));
        when(partialTag.getFirst(FieldKey.LANGUAGE)).thenReturn(" \t ");
        when(partialTag.getFirst(FieldKey.LYRICS)).thenReturn(null);
        when(partialTag.getFirstArtwork()).thenReturn(null);

        TagInfo partial = reader(partialTag).read(new File("partial.mp3"));

        assertThat(partial.getTitle()).isEqualTo("Partial title");
        assertThat(partial.getArtist()).isNull();
        assertThat(partial.getLanguage()).isNull();
        assertThat(partial.getEmbeddedLyric()).isNull();
    }

    @Test
    void rejectsOversizedLyricsAndCoversWithoutDroppingOtherTags() {
        Tag tag = mock(Tag.class);
        when(tag.getFirst(FieldKey.TITLE)).thenReturn("Kept title");
        when(tag.getFirst(FieldKey.ARTIST)).thenReturn(null);
        when(tag.getFirst(FieldKey.LANGUAGE)).thenReturn(null);
        when(tag.getFirst(FieldKey.LYRICS)).thenReturn("x".repeat(TagReader.MAX_EMBEDDED_LYRIC_BYTES + 1));
        Artwork oversizedArtwork = artwork(
                new byte[TagReader.MAX_EMBEDDED_IMAGE_BYTES + 1], "image/jpeg");
        when(tag.getFirstArtwork()).thenReturn(oversizedArtwork);

        TagInfo info = reader(tag).read(new File("oversized-assets.mp3"));

        assertThat(info.getTitle()).isEqualTo("Kept title");
        assertThat(info.getEmbeddedLyric()).isNull();
        assertThat(info.getCoverImage()).isNull();
        assertThat(info.getCoverExt()).isNull();
    }

    @Test
    void ignoresNullArtworkAndNullArtworkBytes() {
        Tag withoutArtwork = mock(Tag.class);
        when(withoutArtwork.getFirstArtwork()).thenReturn(null);
        assertThat(reader(withoutArtwork).read(new File("no-art.mp3")).getCoverImage()).isNull();

        Tag withEmptyArtwork = mock(Tag.class);
        Artwork artwork = artwork(null, "image/webp");
        when(withEmptyArtwork.getFirstArtwork()).thenReturn(artwork);
        TagInfo info = reader(withEmptyArtwork).read(new File("empty-art.mp3"));

        assertThat(info.getCoverImage()).isNull();
        assertThat(info.getCoverExt()).isNull();
        verify(artwork).getBinaryData();
    }

    @Test
    void mapsNullWebpAndUnknownMimeTypesToSupportedExtensions() {
        assertCoverExtension(null, "jpg");
        assertCoverExtension("image/WEBP", "webp");
        assertCoverExtension("image/jpeg", "jpg");
    }

    @Test
    void utf8BudgetAcceptsNullAndRejectsNegativeLimit() {
        assertThat(TagReader.utf8AtMost(null, -1)).isTrue();
        assertThat(TagReader.utf8AtMost("", -1)).isFalse();
        assertThat(TagReader.utf8AtMost("\u07ff", 2)).isTrue();
    }

    private static void assertCoverExtension(String mime, String expected) {
        Tag tag = mock(Tag.class);
        Artwork artwork = artwork(new byte[]{4}, mime);
        when(tag.getFirstArtwork()).thenReturn(artwork);
        TagInfo info = reader(tag).read(new File("cover.mp3"));
        assertThat(info.getCoverExt()).isEqualTo(expected);
    }

    private static TagReader reader(Tag tag) {
        AudioFile audioFile = mock(AudioFile.class);
        when(audioFile.getTag()).thenReturn(tag);
        return new TagReader(ignored -> audioFile, ignored -> TagBudget.SAFE);
    }

    private static Artwork artwork(byte[] bytes, String mime) {
        Artwork artwork = mock(Artwork.class);
        when(artwork.getBinaryData()).thenReturn(bytes);
        when(artwork.getMimeType()).thenReturn(mime);
        return artwork;
    }

    private static void assertEmpty(TagInfo info) {
        assertThat(info.getTitle()).isNull();
        assertThat(info.getArtist()).isNull();
        assertThat(info.getLanguage()).isNull();
        assertThat(info.getEmbeddedLyric()).isNull();
        assertThat(info.getCoverImage()).isNull();
        assertThat(info.getCoverExt()).isNull();
    }
}
