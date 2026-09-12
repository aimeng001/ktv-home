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

        TagBudget budget = new DefaultTagBudgetProbe().inspect(file.toFile());

        assertThat(budget).isEqualTo(TagBudget.SAFE);
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
