package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TagReaderResourceBudgetTest {

    @TempDir
    Path temp;

    @Test
    void oversizedMp3TagIsRejectedBeforeThirdPartyParserRuns() throws Exception {
        Path file = writeId3v2Header(DefaultTagBudgetProbe.MAX_TAG_PARSE_BYTES + 1L);
        AtomicInteger parserCalls = new AtomicInteger();
        TagReader reader = new TagReader(
                ignored -> {
                    parserCalls.incrementAndGet();
                    throw new AssertionError("jaudiotagger must not run for an oversized tag");
                },
                new DefaultTagBudgetProbe());

        TagInfo result = reader.read(file.toFile());

        assertThat(result.getCoverImage()).isNull();
        assertThat(result.getEmbeddedLyric()).isNull();
        assertThat(parserCalls).hasValue(0);
    }

    @Test
    void oversizedId3LyricsFrameIsRejectedBeforeThirdPartyParserRuns() throws Exception {
        Path file = writeId3v24Frame("USLT", TagReader.MAX_EMBEDDED_LYRIC_BYTES + 1, 0);
        AtomicInteger parserCalls = new AtomicInteger();
        TagReader reader = new TagReader(
                ignored -> {
                    parserCalls.incrementAndGet();
                    throw new AssertionError("jaudiotagger must not run for an oversized lyric frame");
                },
                new DefaultTagBudgetProbe());

        TagInfo result = reader.read(file.toFile());

        assertThat(result.getEmbeddedLyric()).isNull();
        assertThat(parserCalls).hasValue(0);
    }

    @Test
    void compressedId3FrameIsRejectedBeforeThirdPartyParserRuns() throws Exception {
        Path file = writeId3v24Frame("TIT2", 32, 0x08);
        AtomicInteger parserCalls = new AtomicInteger();
        TagReader reader = new TagReader(
                ignored -> {
                    parserCalls.incrementAndGet();
                    throw new AssertionError("jaudiotagger must not run for compressed metadata");
                },
                new DefaultTagBudgetProbe());

        reader.read(file.toFile());

        assertThat(parserCalls).hasValue(0);
    }

    @Test
    void utf8BudgetCountsChineseAndEmojiAsBytesWithoutChangingTheAcceptedBoundary() {
        assertThat(TagReader.utf8AtMost("a".repeat(16), 16)).isTrue();
        assertThat(TagReader.utf8AtMost("中".repeat(6), 17)).isFalse();
        assertThat(TagReader.utf8AtMost("😀".repeat(4), 16)).isTrue();
        assertThat(TagReader.utf8AtMost("😀".repeat(5), 16)).isFalse();
    }

    private Path writeId3v2Header(long tagBytes) throws IOException {
        if (tagBytes < 0 || tagBytes > 0x0fffffffL) {
            throw new IllegalArgumentException("test tag size must fit ID3 synchsafe encoding");
        }
        byte[] header = {
                'I', 'D', '3', 4, 0, 0,
                (byte) ((tagBytes >>> 21) & 0x7f),
                (byte) ((tagBytes >>> 14) & 0x7f),
                (byte) ((tagBytes >>> 7) & 0x7f),
                (byte) (tagBytes & 0x7f)
        };
        Path file = temp.resolve("oversized.mp3");
        Files.write(file, header);
        return file;
    }

    private Path writeId3v24Frame(String frameId, int frameBytes) throws IOException {
        return writeId3v24Frame(frameId, frameBytes, 0);
    }

    private Path writeId3v24Frame(String frameId, int frameBytes, int formatFlags) throws IOException {
        int tagPayloadBytes = 10 + frameBytes;
        byte[] file = new byte[10 + tagPayloadBytes];
        file[0] = 'I';
        file[1] = 'D';
        file[2] = '3';
        file[3] = 4;
        file[6] = (byte) ((tagPayloadBytes >>> 21) & 0x7f);
        file[7] = (byte) ((tagPayloadBytes >>> 14) & 0x7f);
        file[8] = (byte) ((tagPayloadBytes >>> 7) & 0x7f);
        file[9] = (byte) (tagPayloadBytes & 0x7f);
        byte[] id = frameId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, file, 10, id.length);
        file[14] = (byte) ((frameBytes >>> 21) & 0x7f);
        file[15] = (byte) ((frameBytes >>> 14) & 0x7f);
        file[16] = (byte) ((frameBytes >>> 7) & 0x7f);
        file[17] = (byte) (frameBytes & 0x7f);
        file[19] = (byte) formatFlags;
        Path path = temp.resolve("oversized-lyrics.mp3");
        Files.write(path, file);
        return path;
    }
}
