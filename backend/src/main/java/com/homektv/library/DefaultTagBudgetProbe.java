package com.homektv.library;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads only fixed-size container headers before jaudiotagger is invoked.
 *
 * <p>The parser is deliberately fail-closed for formats whose metadata extent
 * cannot be bounded with this small probe. This keeps the resource guarantee
 * stronger than the best-effort limits applied after a third-party parser has
 * already materialized a tag.</p>
 */
final class DefaultTagBudgetProbe implements TagBudgetProbe {

    static final int MAX_TAG_PARSE_BYTES = AssetWriter.MAX_IMAGE_BYTES;

    private static final int ID3_HEADER_BYTES = 10;
    private static final int APE_FOOTER_BYTES = 32;
    private static final int FLAC_HEADER_BYTES = 4;
    private static final int FLAC_BLOCK_HEADER_BYTES = 4;
    private static final int MAX_FLAC_METADATA_BLOCKS = 1024;
    private static final int ID3_FRAME_HEADER_BYTES = 10;
    private static final int MAX_VORBIS_COMMENTS = 4096;
    private static final int MAX_COMMENT_KEY_BYTES = 128;

    @Override
    public TagBudget inspect(File file) throws IOException {
        if (file == null || !file.isFile()) return TagBudget.UNSAFE;

        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < FLAC_HEADER_BYTES) return TagBudget.UNSAFE;

            byte[] magic = readBytes(channel, FLAC_HEADER_BYTES);
            channel.position(0);
            if (isId3(magic)) {
                return inspectId3(channel, fileSize);
            }
            if (isFlac(magic)) {
                return inspectFlac(channel, fileSize);
            }
            return TagBudget.UNSAFE;
        }
    }

    private static TagBudget inspectId3(FileChannel channel, long fileSize) throws IOException {
        if (fileSize < ID3_HEADER_BYTES) return TagBudget.UNSAFE;

        byte[] header = readBytes(channel, ID3_HEADER_BYTES);
        int majorVersion = header[3] & 0xff;
        if (majorVersion < 3 || majorVersion > 4) return TagBudget.UNSAFE;

        // Extended headers and unsynchronisation change the frame layout. A
        // false SAFE result would allow jaudiotagger to materialise an
        // oversized lyric frame, so unsupported variants fail closed.
        if ((header[5] & 0xC0) != 0) return TagBudget.UNSAFE;

        long payloadBytes = decodeSynchsafe(header, 6);
        if (payloadBytes < 0) return TagBudget.UNSAFE;

        long footerBytes = majorVersion == 4 && (header[5] & 0x10) != 0 ? ID3_HEADER_BYTES : 0;
        long totalTagBytes = ID3_HEADER_BYTES + payloadBytes + footerBytes;
        if (totalTagBytes > MAX_TAG_PARSE_BYTES || totalTagBytes > fileSize) {
            return TagBudget.UNSAFE;
        }

        if (!inspectId3Frames(channel, majorVersion, totalTagBytes - footerBytes)) {
            return TagBudget.UNSAFE;
        }

        if (!isApeTagWithinBudget(channel, fileSize)) return TagBudget.UNSAFE;
        return TagBudget.SAFE;
    }

    private static boolean inspectId3Frames(FileChannel channel, int majorVersion,
                                             long contentEnd) throws IOException {
        long position = ID3_HEADER_BYTES;
        while (position < contentEnd) {
            long remaining = contentEnd - position;
            if (remaining < ID3_FRAME_HEADER_BYTES) {
                return true; // ID3 padding at the end of the tag.
            }

            channel.position(position);
            byte[] frameHeader = readBytes(channel, ID3_FRAME_HEADER_BYTES);
            if (allZero(frameHeader)) return true;
            if (!validFrameId(frameHeader)) return false;

            long frameBytes = majorVersion == 4
                    ? decodeSynchsafe(frameHeader, 4)
                    : bigEndianInt(frameHeader, 4);
            if (frameBytes < 0 || frameBytes > contentEnd - position - ID3_FRAME_HEADER_BYTES) {
                return false;
            }
            int formatFlags = frameHeader[9] & 0xff;
            boolean compressedOrEncrypted = majorVersion == 3
                    ? (formatFlags & 0xC0) != 0
                    : (formatFlags & 0x0C) != 0;
            if (compressedOrEncrypted) return false;
            String frameId = new String(frameHeader, 0, 4, StandardCharsets.US_ASCII);
            if (isLyricsFrame(frameId) && frameBytes > AssetWriter.MAX_LYRIC_BYTES) {
                return false;
            }
            position += ID3_FRAME_HEADER_BYTES + frameBytes;
        }
        return true;
    }

    private static TagBudget inspectFlac(FileChannel channel, long fileSize) throws IOException {
        channel.position(FLAC_HEADER_BYTES);
        long metadataBytes = FLAC_HEADER_BYTES;
        for (int block = 0; block < MAX_FLAC_METADATA_BLOCKS; block++) {
            if (metadataBytes + FLAC_BLOCK_HEADER_BYTES > fileSize) return TagBudget.UNSAFE;

            byte[] header = readBytes(channel, FLAC_BLOCK_HEADER_BYTES);
            boolean last = (header[0] & 0x80) != 0;
            long payloadBytes = ((long) (header[1] & 0xff) << 16)
                    | ((long) (header[2] & 0xff) << 8)
                    | (header[3] & 0xffL);
            long nextMetadataBytes = metadataBytes + FLAC_BLOCK_HEADER_BYTES + payloadBytes;
            if (payloadBytes > MAX_TAG_PARSE_BYTES
                    || nextMetadataBytes > MAX_TAG_PARSE_BYTES
                    || nextMetadataBytes > fileSize) {
                return TagBudget.UNSAFE;
            }

            metadataBytes = nextMetadataBytes;
            if ((header[0] & 0x7f) == 4) {
                if (!inspectVorbisComment(channel, payloadBytes)) return TagBudget.UNSAFE;
            } else {
                channel.position(channel.position() + payloadBytes);
            }
            if (last) return TagBudget.SAFE;
        }
        return TagBudget.UNSAFE;
    }

    private static boolean inspectVorbisComment(FileChannel channel, long payloadBytes)
            throws IOException {
        long remaining = payloadBytes;
        if (remaining < 8) return false;

        long vendorBytes = littleEndianUnsigned(channel);
        remaining -= 4;
        if (vendorBytes > remaining) return false;
        skip(channel, vendorBytes);
        remaining -= vendorBytes;

        long commentCount = littleEndianUnsigned(channel);
        remaining -= 4;
        if (commentCount > MAX_VORBIS_COMMENTS) return false;

        for (long index = 0; index < commentCount; index++) {
            if (remaining < 4) return false;
            long commentBytes = littleEndianUnsigned(channel);
            remaining -= 4;
            if (commentBytes > remaining) return false;

            int prefixBytes = (int) Math.min(commentBytes, MAX_COMMENT_KEY_BYTES);
            byte[] prefix = readBytes(channel, prefixBytes);
            if (isLyricsComment(prefix) && commentBytes > AssetWriter.MAX_LYRIC_BYTES) {
                return false;
            }
            remaining -= prefixBytes;
            skip(channel, commentBytes - prefixBytes);
            remaining -= commentBytes - prefixBytes;
        }
        skip(channel, remaining);
        return true;
    }

    private static boolean isApeTagWithinBudget(FileChannel channel, long fileSize) throws IOException {
        if (fileSize < APE_FOOTER_BYTES) return true;

        channel.position(fileSize - APE_FOOTER_BYTES);
        byte[] footer = readBytes(channel, APE_FOOTER_BYTES);
        if (!matches(footer, 0, "APETAGEX")) return true;

        long tagBytes = littleEndianInt(footer, 12);
        return tagBytes <= MAX_TAG_PARSE_BYTES && tagBytes <= fileSize;
    }

    private static long decodeSynchsafe(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            int current = bytes[offset + i] & 0xff;
            if ((current & 0x80) != 0) return -1;
            value = (value << 7) | current;
        }
        return value;
    }

    private static long littleEndianInt(byte[] bytes, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN);
        return Integer.toUnsignedLong(buffer.getInt());
    }

    private static long littleEndianUnsigned(FileChannel channel) throws IOException {
        return littleEndianInt(readBytes(channel, 4), 0);
    }

    private static long bigEndianInt(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xffL);
    }

    private static void skip(FileChannel channel, long bytes) throws IOException {
        if (bytes < 0 || bytes > channel.size() - channel.position()) {
            throw new IOException("metadata block exceeds file");
        }
        channel.position(channel.position() + bytes);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) return false;
        }
        return true;
    }

    private static boolean validFrameId(byte[] bytes) {
        for (int index = 0; index < 4; index++) {
            int value = bytes[index] & 0xff;
            if (!((value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLyricsFrame(String frameId) {
        return "USLT".equals(frameId) || "SYLT".equals(frameId);
    }

    private static boolean isLyricsComment(byte[] prefix) {
        String comment = new String(prefix, StandardCharsets.UTF_8);
        int equals = comment.indexOf('=');
        if (equals <= 0) return false;
        String key = comment.substring(0, equals).trim().toUpperCase(Locale.ROOT);
        return key.contains("LYRIC");
    }

    private static boolean isId3(byte[] bytes) {
        return matches(bytes, 0, "ID3");
    }

    private static boolean isFlac(byte[] bytes) {
        return matches(bytes, 0, "fLaC");
    }

    private static boolean matches(byte[] bytes, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > bytes.length) return false;
        for (int i = 0; i < expected.length(); i++) {
            if (bytes[offset + i] != (byte) expected.charAt(i)) return false;
        }
        return true;
    }

    private static byte[] readBytes(FileChannel channel, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read <= 0) throw new IOException("unexpected end of metadata header");
        }
        return buffer.array();
    }
}
