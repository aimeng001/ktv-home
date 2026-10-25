package com.homektv.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TagBudgetProbeTest {

    @TempDir
    Path temp;

    @Test
    void oversizedFlacLyricsCommentIsRejectedBeforeThirdPartyParserRuns() throws Exception {
        Path file = temp.resolve("oversized-lyrics.flac");
        Files.write(file, flacWithComment("LYRICS", AssetWriter.MAX_LYRIC_BYTES + 1));

        TagBudget budget = new DefaultTagBudgetProbe().inspect(file.toFile());

        assertThat(budget).isEqualTo(TagBudget.UNSAFE);
    }

    @Test
    void smallFlacVorbisCommentRemainsPreflightable() throws Exception {
        Path file = temp.resolve("small-comment.flac");
        Files.write(file, flacWithComment("TITLE", 32));
        Path longTitleFile = write("long-title-comment.flac",
                flacWithComment("TITLE", AssetWriter.MAX_LYRIC_BYTES + 1));

        TagBudget budget = new DefaultTagBudgetProbe().inspect(file.toFile());

        assertThat(budget).isEqualTo(TagBudget.SAFE);
        assertThat(new DefaultTagBudgetProbe().inspect(longTitleFile.toFile())).isEqualTo(TagBudget.SAFE);
    }

    @Test
    void missingNonFileAndTooShortInputsFailClosed() throws Exception {
        Path shortFile = temp.resolve("short.bin");
        Files.write(shortFile, new byte[]{'I', 'D', '3', 4, 0});
        Path emptyFile = write("empty.bin", new byte[0]);
        Path unknownContainer = write("unknown-container.bin", new byte[16]);

        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();
        assertThat(probe.inspect(null)).isEqualTo(TagBudget.UNSAFE);
        assertThat(probe.inspect(temp.toFile())).isEqualTo(TagBudget.UNSAFE);
        assertThat(probe.inspect(temp.resolve("missing.mp3").toFile())).isEqualTo(TagBudget.UNSAFE);
        assertThat(probe.inspect(emptyFile.toFile())).isEqualTo(TagBudget.UNSAFE);
        assertThat(probe.inspect(shortFile.toFile())).isEqualTo(TagBudget.UNSAFE);
        assertThat(probe.inspect(unknownContainer.toFile())).isEqualTo(TagBudget.UNSAFE);
    }

    @Test
    void supportedId3VersionsAcceptValidFramesAndTrailingPadding() throws Exception {
        for (int version : new int[]{3, 4}) {
            byte[] frame = id3Frame(version, "TIT2", new byte[]{1, 2, 3}, 0);
            byte[] paddedBody = Arrays.copyOf(frame, frame.length + 2);
            Path file = write("valid-id3-v2-" + version + ".mp3", id3(version, 0, paddedBody, new byte[0]));

            assertThat(new DefaultTagBudgetProbe().inspect(file.toFile()))
                    .as("ID3v2.%s valid text frame", version)
                    .isEqualTo(TagBudget.SAFE);
        }
    }

    @Test
    void completeZeroFramePaddingAndAbsentApeFooterRemainSafe() throws Exception {
        byte[] frame = id3Frame(4, "TIT2", new byte[]{1}, 0);
        byte[] body = Arrays.copyOf(frame, frame.length + 12);
        Path file = write("id3-zero-frame-padding.mp3", id3(4, 0, body, new byte[0]));

        assertThat(new DefaultTagBudgetProbe().inspect(file.toFile())).isEqualTo(TagBudget.SAFE);
    }

    @Test
    void unsupportedId3VersionsFlagsAndInvalidSynchsafeSizesFailClosed() throws Exception {
        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();
        assertThat(probe.inspect(write("old-id3.mp3", id3(2, 0, new byte[0], new byte[0])).toFile()))
                .isEqualTo(TagBudget.UNSAFE);
        assertThat(probe.inspect(write("future-id3.mp3", id3(5, 0, new byte[0], new byte[0])).toFile()))
                .isEqualTo(TagBudget.UNSAFE);
        assertThat(probe.inspect(write("extended-id3.mp3", id3(4, 0x40, new byte[0], new byte[0])).toFile()))
                .isEqualTo(TagBudget.UNSAFE);
        assertThat(probe.inspect(write("unsynchronized-id3.mp3", id3(4, 0x80, new byte[0], new byte[0])).toFile()))
                .isEqualTo(TagBudget.UNSAFE);

        byte[] truncatedPayload = id3(4, 0, new byte[0], new byte[0]);
        writeSynchsafe(truncatedPayload, 6, 1);
        assertThat(probe.inspect(write("truncated-id3-payload.mp3", truncatedPayload).toFile()))
                .isEqualTo(TagBudget.UNSAFE);

        byte[] oversizedDeclaredPayload = id3(4, 0, new byte[0], new byte[0]);
        writeSynchsafe(oversizedDeclaredPayload, 6, DefaultTagBudgetProbe.MAX_TAG_PARSE_BYTES + 1);
        assertThat(probe.inspect(write("oversized-id3-declared-payload.mp3", oversizedDeclaredPayload).toFile()))
                .isEqualTo(TagBudget.UNSAFE);

        byte[] invalidSize = id3(4, 0, new byte[0], new byte[0]);
        invalidSize[9] = (byte) 0x80;
        assertThat(probe.inspect(write("invalid-synchsafe-id3.mp3", invalidSize).toFile()))
                .isEqualTo(TagBudget.UNSAFE);
    }

    @Test
    void compressedAndEncryptedId3FramesFailClosedForBothSupportedVersions() throws Exception {
        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();
        int[][] versionAndFlags = {{3, 0x80}, {3, 0x40}, {4, 0x08}, {4, 0x04}};

        for (int[] variant : versionAndFlags) {
            int version = variant[0];
            int flags = variant[1];
            String name = "id3-v" + version + "-frame-flags-" + flags + ".mp3";
            byte[] frame = id3Frame(version, "TIT2", new byte[]{1, 2, 3}, flags);

            assertThat(probe.inspect(write(name, id3(version, 0, frame, new byte[0])).toFile()))
                    .as("compressed or encrypted ID3v2.%s frame flags 0x%02X", version, flags)
                    .isEqualTo(TagBudget.UNSAFE);
        }
    }

    @Test
    void oversizedId3LyricsFramesFailClosed() throws Exception {
        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();
        byte[] oversizedLyrics = new byte[AssetWriter.MAX_LYRIC_BYTES + 1];

        for (String frameId : new String[]{"USLT", "SYLT"}) {
            byte[] frame = id3Frame(4, frameId, oversizedLyrics, 0);
            Path file = write(frameId.toLowerCase() + "-oversized.mp3", id3(4, 0, frame, new byte[0]));

            assertThat(probe.inspect(file.toFile()))
                    .as("oversized %s lyrics frame", frameId)
                    .isEqualTo(TagBudget.UNSAFE);
        }
    }

    @Test
    void malformedFlacVendorAndCommentExtentsFailClosed() throws Exception {
        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();

        ByteArrayOutputStream vendorExceedsPayload = new ByteArrayOutputStream();
        writeLittleEndianInt(vendorExceedsPayload, 5);
        writeLittleEndianInt(vendorExceedsPayload, 0);
        Path invalidVendor = write("flac-vendor-out-of-bounds.flac",
                flacBlock(4, true, vendorExceedsPayload.toByteArray()));

        ByteArrayOutputStream missingCommentLength = new ByteArrayOutputStream();
        writeLittleEndianInt(missingCommentLength, 0);
        writeLittleEndianInt(missingCommentLength, 1);
        Path truncatedComments = write("flac-comment-length-missing.flac",
                flacBlock(4, true, missingCommentLength.toByteArray()));

        ByteArrayOutputStream commentExceedsPayload = new ByteArrayOutputStream();
        writeLittleEndianInt(commentExceedsPayload, 0);
        writeLittleEndianInt(commentExceedsPayload, 1);
        writeLittleEndianInt(commentExceedsPayload, 1);
        Path invalidComment = write("flac-comment-out-of-bounds.flac",
                flacBlock(4, true, commentExceedsPayload.toByteArray()));

        assertThat(probe.inspect(invalidVendor.toFile())).isEqualTo(TagBudget.UNSAFE);
        assertThat(probe.inspect(truncatedComments.toFile())).isEqualTo(TagBudget.UNSAFE);
        assertThat(probe.inspect(invalidComment.toFile())).isEqualTo(TagBudget.UNSAFE);
    }

    @Test
    void boundedLongNonLyricFlacCommentRemainsSafe() throws Exception {
        byte[] vendor = "vendor".getBytes(StandardCharsets.US_ASCII);
        byte[] comment = new byte[140];
        byte[] titlePrefix = "TITLE=".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(titlePrefix, 0, comment, 0, titlePrefix.length);
        Arrays.fill(comment, titlePrefix.length, comment.length, (byte) 'x');

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeLittleEndianInt(payload, vendor.length);
        payload.write(vendor);
        writeLittleEndianInt(payload, 1);
        writeLittleEndianInt(payload, comment.length);
        payload.write(comment);
        Path file = write("bounded-long-title.flac", flacBlock(4, true, payload.toByteArray()));

        assertThat(new DefaultTagBudgetProbe().inspect(file.toFile())).isEqualTo(TagBudget.SAFE);
    }

    @Test
    void malformedId3FrameIdAndPayloadExtentFailClosed() throws Exception {
        DefaultTagBudgetProbe probe = new DefaultTagBudgetProbe();
        byte[] invalidId = id3Frame(4, "tiT2", new byte[]{1}, 0);
        assertThat(probe.inspect(write("invalid-frame-id.mp3", id3(4, 0, invalidId, new byte[0])).toFile()))
                .isEqualTo(TagBudget.UNSAFE);

        byte[] truncatedFrame = id3Frame(4, "TIT2", new byte[]{1}, 0);
        writeSynchsafe(truncatedFrame, 4, 5);
        assertThat(probe.inspect(write("truncated-frame.mp3", id3(4, 0, truncatedFrame, new byte[0])).toFile()))
                .isEqualTo(TagBudget.UNSAFE);

        byte[] negativeFrameSize = id3Frame(4, "TIT2", new byte[]{1}, 0);
        negativeFrameSize[4] = (byte) 0x80;
        assertThat(probe.inspect(write("invalid-frame-size.mp3", id3(4, 0, negativeFrameSize, new byte[0])).toFile()))
                .isEqualTo(TagBudget.UNSAFE);
    }

    @Test
    void id3v24FooterAndBoundedApeFooterRemainSafe() throws Exception {
        byte[] footer = new byte[10];
        Path id3Footer = write("id3-footer.mp3", id3(4, 0x10, new byte[0], footer));
        assertThat(new DefaultTagBudgetProbe().inspect(id3Footer.toFile())).isEqualTo(TagBudget.SAFE);

        byte[] apeFooter = apeFooter(32);
        Path apeFile = write("bounded-ape.mp3", id3(4, 0, new byte[0], apeFooter));
        assertThat(new DefaultTagBudgetProbe().inspect(apeFile.toFile())).isEqualTo(TagBudget.SAFE);
    }

    @Test
    void oversizedApeFooterAndTruncatedFlacBlockFailClosed() throws Exception {
        byte[] oversizedApe = apeFooter((int) DefaultTagBudgetProbe.MAX_TAG_PARSE_BYTES + 1);
        Path apeFile = write("oversized-ape.mp3", id3(4, 0, new byte[0], oversizedApe));
        assertThat(new DefaultTagBudgetProbe().inspect(apeFile.toFile())).isEqualTo(TagBudget.UNSAFE);

        Path apeExceedsFile = write("ape-exceeds-file.mp3", apeFooter(33));
        assertThat(new DefaultTagBudgetProbe().inspect(apeExceedsFile.toFile())).isEqualTo(TagBudget.UNSAFE);

        Path flacFile = write("truncated-flac.flac", new byte[]{'f', 'L', 'a', 'C', (byte) 0x80, 0, 0, 8});
        assertThat(new DefaultTagBudgetProbe().inspect(flacFile.toFile())).isEqualTo(TagBudget.UNSAFE);

        Path missingNextBlock = write("missing-flac-block-header.flac", flacBlock(0, false, new byte[0]));
        assertThat(new DefaultTagBudgetProbe().inspect(missingNextBlock.toFile())).isEqualTo(TagBudget.UNSAFE);

        int declaredPayloadBytes = DefaultTagBudgetProbe.MAX_TAG_PARSE_BYTES + 1;
        byte[] oversizedMetadataHeader = {
                'f', 'L', 'a', 'C', (byte) 0x80,
                (byte) (declaredPayloadBytes >>> 16),
                (byte) (declaredPayloadBytes >>> 8),
                (byte) declaredPayloadBytes
        };
        Path oversizedMetadata = write("oversized-flac-metadata-header.flac", oversizedMetadataHeader);
        assertThat(new DefaultTagBudgetProbe().inspect(oversizedMetadata.toFile())).isEqualTo(TagBudget.UNSAFE);
    }

    @Test
    void shortFlacVorbisCommentBlockFailsClosed() throws Exception {
        Path file = write("short-vorbis-comment.flac", flacBlock(4, true, new byte[7]));

        assertThat(new DefaultTagBudgetProbe().inspect(file.toFile())).isEqualTo(TagBudget.UNSAFE);
    }

    @Test
    void flacCommentCountLimitAndMetadataBlockCountLimitFailClosed() throws Exception {
        ByteArrayOutputStream commentPayload = new ByteArrayOutputStream();
        writeLittleEndianInt(commentPayload, 0);
        writeLittleEndianInt(commentPayload, 4097);
        Path comments = write("too-many-comments.flac", flacBlock(4, true, commentPayload.toByteArray()));
        assertThat(new DefaultTagBudgetProbe().inspect(comments.toFile())).isEqualTo(TagBudget.UNSAFE);

        ByteArrayOutputStream manyBlocks = new ByteArrayOutputStream();
        manyBlocks.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        for (int index = 0; index < 1024; index++) {
            manyBlocks.write(0); // non-final STREAMINFO-type block with empty payload
            manyBlocks.write(new byte[3]);
        }
        Path blockLimit = write("too-many-blocks.flac", manyBlocks.toByteArray());
        assertThat(new DefaultTagBudgetProbe().inspect(blockLimit.toFile())).isEqualTo(TagBudget.UNSAFE);
    }

    private Path write(String name, byte[] contents) throws IOException {
        Path file = temp.resolve(name);
        Files.write(file, contents);
        return file;
    }

    private static byte[] id3(int version, int flags, byte[] body, byte[] footer) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("ID3".getBytes(StandardCharsets.US_ASCII));
        output.write(version);
        output.write(0);
        output.write(flags);
        writeSynchsafe(output, body.length);
        output.write(body);
        output.write(footer);
        return output.toByteArray();
    }

    private static byte[] id3Frame(int version, String frameId, byte[] payload, int flags) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(frameId.getBytes(StandardCharsets.US_ASCII));
        if (version == 4) {
            writeSynchsafe(output, payload.length);
        } else {
            output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length).array());
        }
        output.write(0); // frame flags byte 1
        output.write(flags);
        output.write(payload);
        return output.toByteArray();
    }

    private static byte[] flacBlock(int type, boolean last, byte[] payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        output.write((last ? 0x80 : 0) | type);
        output.write((payload.length >>> 16) & 0xff);
        output.write((payload.length >>> 8) & 0xff);
        output.write(payload.length & 0xff);
        output.write(payload);
        return output.toByteArray();
    }

    private static byte[] apeFooter(int tagBytes) throws IOException {
        byte[] footer = new byte[32];
        System.arraycopy("APETAGEX".getBytes(StandardCharsets.US_ASCII), 0, footer, 0, 8);
        System.arraycopy(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(2000).array(),
                0, footer, 8, 4);
        System.arraycopy(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(tagBytes).array(),
                0, footer, 12, 4);
        return footer;
    }

    private static void writeSynchsafe(ByteArrayOutputStream output, int value) throws IOException {
        output.write((value >>> 21) & 0x7f);
        output.write((value >>> 14) & 0x7f);
        output.write((value >>> 7) & 0x7f);
        output.write(value & 0x7f);
    }

    private static void writeSynchsafe(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) ((value >>> 21) & 0x7f);
        bytes[offset + 1] = (byte) ((value >>> 14) & 0x7f);
        bytes[offset + 2] = (byte) ((value >>> 7) & 0x7f);
        bytes[offset + 3] = (byte) (value & 0x7f);
    }

    private static byte[] flacWithComment(String key, int commentBytes) throws IOException {
        byte[] comment = new byte[commentBytes];
        byte[] keyBytes = (key + "=").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(keyBytes, 0, comment, 0, Math.min(keyBytes.length, comment.length));

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeLittleEndianInt(payload, 0); // vendor length
        writeLittleEndianInt(payload, 1); // comment count
        writeLittleEndianInt(payload, comment.length);
        payload.write(comment);

        byte[] block = payload.toByteArray();
        ByteArrayOutputStream flac = new ByteArrayOutputStream();
        flac.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        flac.write(0x84); // last metadata block, type VORBIS_COMMENT (4)
        flac.write((block.length >>> 16) & 0xff);
        flac.write((block.length >>> 8) & 0xff);
        flac.write(block.length & 0xff);
        flac.write(block);
        return flac.toByteArray();
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream output, int value)
            throws IOException {
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value).array());
    }
}
