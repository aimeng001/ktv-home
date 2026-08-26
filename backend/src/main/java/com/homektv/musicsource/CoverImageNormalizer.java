package com.homektv.musicsource;

import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
class CoverImageNormalizer {

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }
    private static final int MAX_DIMENSION = 8_192;
    private static final long MAX_PIXELS = 4_096L * 4_096L;
    private static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024;
    private static final long CONVERT_TIMEOUT_SECONDS = 20;

    private final String ffmpegPath;
    private final ProcessLauncher processLauncher;

    @Autowired
    CoverImageNormalizer(@Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this(ffmpegPath, CoverImageNormalizer::startProcess);
    }

    CoverImageNormalizer(String ffmpegPath, ProcessLauncher processLauncher) {
        this.ffmpegPath = ffmpegPath;
        this.processLauncher = processLauncher;
    }

    byte[] normalize(byte[] source) {
        BufferedImage image = decode(source);
        if (image != null) return encodeJpeg(image);
        return convertWithFfmpeg(source);
    }

    private byte[] convertWithFfmpeg(byte[] source) {
        Path input = null;
        Path output = null;
        Process process = null;
        try {
            input = Files.createTempFile("home-ktv-cover-", ".image");
            output = Files.createTempFile("home-ktv-cover-", ".jpg");
            Files.write(input, source);
            process = processLauncher.start(List.of(
                    ffmpegPath, "-hide_banner", "-loglevel", "error", "-y",
                    "-i", input.toString(), "-frames:v", "1",
                    "-vf", "scale=4096:4096:force_original_aspect_ratio=decrease",
                    "-q:v", "2", output.toString()));
            if (!process.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw invalid("封面格式转换超时");
            }
            if (process.exitValue() != 0 || !Files.isReadable(output)) {
                throw invalid("封面格式无法识别或转换");
            }
            long size = Files.size(output);
            if (size == 0 || size > MAX_OUTPUT_BYTES) throw invalid("转换后的封面无效或过大");
            byte[] converted = Files.readAllBytes(output);
            BufferedImage image = decode(converted);
            if (image == null) throw invalid("转换后的封面不是有效图片");
            return encodeJpeg(image);
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw invalid("封面格式转换被中断");
        } catch (IOException ex) {
            throw invalid("封面格式无法识别或转换");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    private static Process startProcess(List<String> command) throws IOException {
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                validateDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage image = reader.read(0);
                validateDimensions(image.getWidth(), image.getHeight());
                return image;
            } finally {
                reader.dispose();
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static byte[] encodeJpeg(BufferedImage source) {
        validateDimensions(source.getWidth(), source.getHeight());
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        int[] row = new int[source.getWidth()];
        for (int y = 0; y < source.getHeight(); y++) {
            source.getRGB(0, y, source.getWidth(), 1, row, 0, source.getWidth());
            for (int x = 0; x < row.length; x++) row[x] = flattenOnWhite(row[x]);
            rgb.setRGB(0, y, source.getWidth(), 1, row, 0, source.getWidth());
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(rgb, "jpg", output)) throw invalid("服务器不支持 JPEG 封面编码");
            byte[] normalized = output.toByteArray();
            if (normalized.length == 0 || normalized.length > MAX_OUTPUT_BYTES) {
                throw invalid("转换后的封面无效或过大");
            }
            return normalized;
        } catch (IOException ex) {
            throw invalid("封面转换失败");
        }
    }

    private static int flattenOnWhite(int argb) {
        int alpha = argb >>> 24;
        if (alpha == 255) return argb & 0x00ffffff;
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;
        red = (red * alpha + 255 * (255 - alpha)) / 255;
        green = (green * alpha + 255 * (255 - alpha)) / 255;
        blue = (blue * alpha + 255 * (255 - alpha)) / 255;
        return (red << 16) | (green << 8) | blue;
    }

    private static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) {
            throw invalid("封面尺寸无效或过大");
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException("EXTERNAL_COVER_INVALID", message);
    }
}
