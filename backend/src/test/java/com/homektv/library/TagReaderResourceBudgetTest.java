package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    void rejectsMissingShortUnknownAndUnsupportedTagFiles() throws Exception {
        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();

        assertThat(probe.inspect(null).parserSafe()).isFalse();
        assertThat(probe.inspect(temp.resolve("missing.mp3").toFile()).parserSafe()).isFalse();
        Path directory = Files.createDirectory(temp.resolve("directory"));
        assertThat(probe.inspect(directory.toFile()).parserSafe()).isFalse();
        assertThat(probe.inspect(writeFile("short.bin", new byte[]{1, 2, 3}).toFile()).parserSafe()).isFalse();
        assertUnsafe(probe, writeFile("short-id3.mp3", new byte[]{'I', 'D', '3', 4}));
        assertThat(probe.inspect(writeFile("unknown.bin", "WAVE".getBytes(StandardCharsets.US_ASCII))
                .toFile()).parserSafe()).isFalse();
    }

    @Test
    void rejectsMalformedId3HeadersAndFramesButAllowsLargeNonLyricFrames() throws Exception {
        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();

        assertUnsafe(probe, writeId3("old-version.mp3", 2, 0, new byte[0]));
        assertUnsafe(probe, writeId3("new-version.mp3", 5, 0, new byte[0]));
        assertUnsafe(probe, writeId3("extended-header.mp3", 4, 0x40, new byte[0]));
        assertUnsafe(probe, writeInvalidSynchsafeId3("invalid-synchsafe.mp3"));
        assertUnsafe(probe, writeId3WithDeclaredPayloadSize("truncated-tag.mp3", 4, 0, 100, new byte[0]));
        assertUnsafe(probe, writeId3Frame("invalid-frame-id.mp3", 4, 0, "tiT2", 0, new byte[0]));
        assertUnsafe(probe, writeId3Frame("invalid-frame-character.mp3", 4, 0, "!IT2", 0, new byte[0]));
        assertUnsafe(probe, writeId3Frame("truncated-frame.mp3", 4, 0, "TIT2", 5, new byte[0]));
        assertUnsafe(probe, writeId3Frame("v23-encrypted-frame.mp3", 3, 0, "TIT2", 0, new byte[0], 0x40));
        assertUnsafe(probe, writeId3FrameWithInvalidSynchsafeFrameSize());

        assertThat(new DefaultTagBudgetProbe().inspect(writeId3("zero-padding.mp3", 4, 0, new byte[10])
                .toFile()).parserSafe()).isTrue();
        assertThat(probe.inspect(writeId3("short-padding.mp3", 4, 0, new byte[9])
                .toFile()).parserSafe()).isTrue();
        assertThat(probe.inspect(writeId3Footer().toFile()).parserSafe()).isTrue();
        assertThat(probe.inspect(writeId3Frame("small-uslt-frame.mp3", 4, 0, "USLT", 1,
                new byte[]{1}).toFile()).parserSafe()).isTrue();
        assertThat(probe.inspect(writeId3Frame("small-sylt-frame.mp3", 4, 0, "SYLT", 1,
                new byte[]{1}).toFile()).parserSafe()).isTrue();
        assertThat(probe.inspect(writeId3Frame("v23-uncompressed-frame.mp3", 3, 0, "TIT2", 0,
                new byte[0]).toFile()).parserSafe()).isTrue();

        Path largeTitleFrame = writeId3Frame("large-title-frame.mp3", 4, 0, "TIT2",
                TagReader.MAX_EMBEDDED_LYRIC_BYTES + 1, new byte[TagReader.MAX_EMBEDDED_LYRIC_BYTES + 1]);
        assertThat(probe.inspect(largeTitleFrame.toFile()).parserSafe()).isTrue();
    }

    @Test
    void boundsFlacMetadataBlocksAndRejectsTruncatedOrUnboundedChains() throws Exception {
        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();

        assertThat(probe.inspect(writeFlacBlock("empty-flac.flac", true, 0, 0, new byte[0])
                .toFile()).parserSafe()).isTrue();
        assertUnsafe(probe, writeFile("truncated-flac-header.flac", "fLaC".getBytes(StandardCharsets.US_ASCII)));
        assertUnsafe(probe, writeFlacBlock("oversized-flac-block.flac", true, 0, 0,
                new byte[0], DefaultTagBudgetProbe.MAX_TAG_PARSE_BYTES + 1));
        assertUnsafe(probe, writeFlacBlock("truncated-flac-block.flac", true, 0, 0,
                new byte[0], 1));

        byte[] boundaryPayload = new byte[DefaultTagBudgetProbe.MAX_TAG_PARSE_BYTES - 8];
        ByteArrayOutputStream boundaryFlac = new ByteArrayOutputStream();
        boundaryFlac.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        writeFlacBlockHeader(boundaryFlac, false, 0, boundaryPayload.length);
        boundaryFlac.write(boundaryPayload);
        writeFlacBlockHeader(boundaryFlac, true, 0, 1);
        boundaryFlac.write(0);
        assertUnsafe(probe, writeFile("metadata-total-over-budget.flac", boundaryFlac.toByteArray()));

        byte[] tooManyBlocks = new byte[4 + 1024 * 4];
        System.arraycopy("fLaC".getBytes(StandardCharsets.US_ASCII), 0, tooManyBlocks, 0, 4);
        for (int block = 0; block < 1024; block++) {
            tooManyBlocks[4 + block * 4] = 0;
        }
        assertUnsafe(probe, writeFile("too-many-flac-blocks.flac", tooManyBlocks));
    }

    @Test
    void boundsFlacVorbisCommentsAndRecognizesLyricKeys() throws Exception {
        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();

        for (String comment : new String[]{"TITLE=Song", "NO_EQUALS", "=value", "LYRICS=short"}) {
            assertThat(probe.inspect(writeFlacComment(comment).toFile()).parserSafe())
                    .as("comment %s", comment.length() > 30 ? comment.substring(0, 30) : comment)
                    .isTrue();
        }
        assertUnsafe(probe, writeFlacBlock("short-comment.flac", true, 4, 7, new byte[7]));
        assertUnsafe(probe, writeFlacCommentWithPayload("short-comment-list.flac", new byte[]{0, 0, 0, 0, 1, 0, 0, 0}));
        assertUnsafe(probe, writeFlacCommentWithPayload("comment-exceeds-list.flac",
                new byte[]{0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0}));
        assertUnsafe(probe, writeFlacCommentCount("too-many-comments.flac", 4097));
        assertUnsafe(probe, writeFlacComment("LYRICS=" + "x".repeat(TagReader.MAX_EMBEDDED_LYRIC_BYTES + 1)));
        assertUnsafe(probe, writeFlacCommentWithVendorLength("oversized-vendor.flac", 64, new byte[8]));
    }

    @Test
    void checksApeFooterSizeAgainstConfiguredBudgetAndFileExtent() throws Exception {
        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();

        assertThat(probe.inspect(writeApeFooter("small-ape.mp3", 1).toFile()).parserSafe()).isTrue();
        assertUnsafe(probe, writeApeFooter("oversized-ape.mp3", DefaultTagBudgetProbe.MAX_TAG_PARSE_BYTES + 1L));
        assertUnsafe(probe, writeApeFooter("ape-exceeds-file.mp3", 64));
    }

    @Test
    void utf8BudgetCountsChineseAndEmojiAsBytesWithoutChangingTheAcceptedBoundary() {
        assertThat(TagReader.utf8AtMost("a".repeat(16), 16)).isTrue();
        assertThat(TagReader.utf8AtMost("中".repeat(6), 17)).isFalse();
        assertThat(TagReader.utf8AtMost("😀".repeat(4), 16)).isTrue();
        assertThat(TagReader.utf8AtMost("😀".repeat(5), 16)).isFalse();
    }

    private void assertUnsafe(DefaultTagBudgetProbe probe, Path file) throws IOException {
        assertThat(probe.inspect(file.toFile()).parserSafe()).as(file.getFileName().toString()).isFalse();
    }

    private Path writeFile(String name, byte[] bytes) throws IOException {
        Path path = temp.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    private Path writeId3(String name, int majorVersion, int flags, byte[] payload) throws IOException {
        byte[] file = new byte[10 + payload.length];
        file[0] = 'I';
        file[1] = 'D';
        file[2] = '3';
        file[3] = (byte) majorVersion;
        file[5] = (byte) flags;
        setSynchsafe(file, 6, payload.length);
        System.arraycopy(payload, 0, file, 10, payload.length);
        return writeFile(name, file);
    }

    private Path writeInvalidSynchsafeId3(String name) throws IOException {
        byte[] header = new byte[10];
        header[0] = 'I';
        header[1] = 'D';
        header[2] = '3';
        header[3] = 4;
        header[6] = (byte) 0x80;
        return writeFile(name, header);
    }

    private Path writeId3WithDeclaredPayloadSize(String name, int majorVersion, int flags,
                                                  int declaredPayloadBytes, byte[] payload) throws IOException {
        byte[] file = new byte[10 + payload.length];
        file[0] = 'I';
        file[1] = 'D';
        file[2] = '3';
        file[3] = (byte) majorVersion;
        file[5] = (byte) flags;
        setSynchsafe(file, 6, declaredPayloadBytes);
        System.arraycopy(payload, 0, file, 10, payload.length);
        return writeFile(name, file);
    }

    private Path writeId3FrameWithInvalidSynchsafeFrameSize() throws IOException {
        Path file = writeId3Frame("invalid-frame-size.mp3", 4, 0, "TIT2", 0, new byte[0]);
        byte[] bytes = Files.readAllBytes(file);
        bytes[14] = (byte) 0x80;
        Files.write(file, bytes);
        return file;
    }

    private Path writeId3Footer() throws IOException {
        byte[] footer = new byte[10];
        byte[] header = new byte[10];
        header[0] = 'I';
        header[1] = 'D';
        header[2] = '3';
        header[3] = 4;
        header[5] = 0x10;
        setSynchsafe(header, 6, footer.length);
        byte[] file = new byte[header.length + footer.length + footer.length];
        System.arraycopy(header, 0, file, 0, header.length);
        System.arraycopy(footer, 0, file, header.length, footer.length);
        System.arraycopy(footer, 0, file, header.length + footer.length, footer.length);
        return writeFile("id3v24-footer.mp3", file);
    }

    private Path writeId3Frame(String name, int majorVersion, int tagFlags, String frameId,
                               int declaredFrameBytes, byte[] framePayload) throws IOException {
        return writeId3Frame(name, majorVersion, tagFlags, frameId, declaredFrameBytes, framePayload, 0);
    }

    private Path writeId3Frame(String name, int majorVersion, int tagFlags, String frameId,
                               int declaredFrameBytes, byte[] framePayload, int frameFlags) throws IOException {
        byte[] payload = new byte[10 + framePayload.length];
        byte[] id = frameId.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, payload, 0, Math.min(4, id.length));
        if (majorVersion == 4) {
            setSynchsafe(payload, 4, declaredFrameBytes);
        } else {
            payload[4] = (byte) (declaredFrameBytes >>> 24);
            payload[5] = (byte) (declaredFrameBytes >>> 16);
            payload[6] = (byte) (declaredFrameBytes >>> 8);
            payload[7] = (byte) declaredFrameBytes;
        }
        payload[9] = (byte) frameFlags;
        System.arraycopy(framePayload, 0, payload, 10, framePayload.length);
        return writeId3(name, majorVersion, tagFlags, payload);
    }

    private static void setSynchsafe(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 21) & 0x7f);
        target[offset + 1] = (byte) ((value >>> 14) & 0x7f);
        target[offset + 2] = (byte) ((value >>> 7) & 0x7f);
        target[offset + 3] = (byte) (value & 0x7f);
    }

    private Path writeFlacBlock(String name, boolean last, int blockType, int declaredBytes,
                                byte[] payload) throws IOException {
        return writeFlacBlock(name, last, blockType, declaredBytes, payload, declaredBytes);
    }

    private Path writeFlacBlock(String name, boolean last, int blockType, int declaredBytes,
                                byte[] payload, long declaredPayloadBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        writeFlacBlockHeader(output, last, blockType, declaredPayloadBytes);
        output.write(payload);
        return writeFile(name, output.toByteArray());
    }

    private static void writeFlacBlockHeader(ByteArrayOutputStream output, boolean last,
                                              int blockType, long payloadBytes) {
        output.write((last ? 0x80 : 0) | (blockType & 0x7f));
        output.write((int) (payloadBytes >>> 16) & 0xff);
        output.write((int) (payloadBytes >>> 8) & 0xff);
        output.write((int) payloadBytes & 0xff);
    }

    private Path writeFlacComment(String comment) throws IOException {
        byte[] encoded = comment.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeLittleEndianInt(payload, 0);
        writeLittleEndianInt(payload, 1);
        writeLittleEndianInt(payload, encoded.length);
        payload.write(encoded);
        byte[] bytes = payload.toByteArray();
        return writeFlacBlock("flac-comment.flac", true, 4, bytes.length, bytes);
    }

    private Path writeFlacCommentWithVendorLength(String name, int vendorLength, byte[] payload) throws IOException {
        ByteArrayOutputStream comment = new ByteArrayOutputStream();
        writeLittleEndianInt(comment, vendorLength);
        writeLittleEndianInt(comment, 0);
        comment.write(payload);
        byte[] bytes = comment.toByteArray();
        return writeFlacBlock(name, true, 4, bytes.length, bytes);
    }

    private Path writeFlacCommentWithPayload(String name, byte[] payload) throws IOException {
        return writeFlacBlock(name, true, 4, payload.length, payload);
    }

    private Path writeFlacCommentCount(String name, int count) throws IOException {
        ByteArrayOutputStream comment = new ByteArrayOutputStream();
        writeLittleEndianInt(comment, 0);
        writeLittleEndianInt(comment, count);
        byte[] bytes = comment.toByteArray();
        return writeFlacBlock(name, true, 4, bytes.length, bytes);
    }

    private Path writeApeFooter(String name, long tagBytes) throws IOException {
        byte[] file = new byte[10 + 32];
        file[0] = 'I';
        file[1] = 'D';
        file[2] = '3';
        file[3] = 4;
        byte[] magic = "APETAGEX".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, file, 10, magic.length);
        int offset = 10 + 12;
        file[offset] = (byte) tagBytes;
        file[offset + 1] = (byte) (tagBytes >>> 8);
        file[offset + 2] = (byte) (tagBytes >>> 16);
        file[offset + 3] = (byte) (tagBytes >>> 24);
        return writeFile(name, file);
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
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
