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
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageReadParam;

@Component
public class CoverImageNormalizer {

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }
    private static final int MAX_DIMENSION = 2_048;
    private static final int MAX_SOURCE_DIMENSION = 4_096;
    private static final long MAX_PIXELS = 2_048L * 2_048L;
    private static final long MAX_SOURCE_PIXELS = 4_096L * 4_096L;
    static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024;
    private static final long CONVERT_TIMEOUT_SECONDS = 20;

    private final String ffmpegPath;
    private final ProcessLauncher processLauncher;
    private final Semaphore decodePermits;

    @Autowired
    public CoverImageNormalizer(@Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this(ffmpegPath, CoverImageNormalizer::startProcess, new Semaphore(2, true));
    }

    CoverImageNormalizer(String ffmpegPath, ProcessLauncher processLauncher) {
        this(ffmpegPath, processLauncher, new Semaphore(2, true));
    }

    CoverImageNormalizer(String ffmpegPath, ProcessLauncher processLauncher,
                         Semaphore decodePermits) {
        this.ffmpegPath = ffmpegPath;
        this.processLauncher = processLauncher;
        this.decodePermits = Objects.requireNonNull(decodePermits, "decodePermits");
    }

    public byte[] normalize(byte[] source) {
        if (!decodePermits.tryAcquire()) {
            throw new ApiException("IMAGE_BUSY", "封面处理繁忙，请稍后重试");
        }
        try {
            if (source == null || source.length == 0 || source.length > MAX_INPUT_BYTES) {
                throw invalid("原始封面无效或过大");
            }
            BufferedImage image = decode(source);
            if (image != null) {
                try {
                    return encodeJpeg(image);
                } finally {
                    image.flush();
                }
            }
            return convertWithFfmpeg(source);
        } finally {
            decodePermits.release();
        }
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
                    "-vf", "scale=2048:2048:force_original_aspect_ratio=decrease",
                    "-q:v", "2", output.toString()));
            if (!process.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                destroyAndWait(process);
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
            try {
                return encodeJpeg(image);
            } finally {
                image.flush();
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            destroyAndWait(process);
            Thread.currentThread().interrupt();
            throw invalid("封面格式转换被中断");
        } catch (IOException ex) {
            throw invalid("封面格式无法识别或转换");
        } finally {
            if (process != null && process.isAlive()) destroyAndWait(process);
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    private static void destroyAndWait(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
                int sourceWidth = reader.getWidth(0);
                int sourceHeight = reader.getHeight(0);
                validateSourceDimensions(sourceWidth, sourceHeight);
                int subsampling = scaleForPixelBudget(sourceWidth, sourceHeight);
                ImageReadParam readParam = reader.getDefaultReadParam();
                if (subsampling > 1) {
                    readParam.setSourceSubsampling(subsampling, subsampling, 0, 0);
                }
                BufferedImage image = reader.read(0, readParam);
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
        BufferedImage rgb = source;
        try {
            if (source.getColorModel().hasAlpha()) {
                rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
                int[] row = new int[source.getWidth()];
                for (int y = 0; y < source.getHeight(); y++) {
                    source.getRGB(0, y, source.getWidth(), 1, row, 0, source.getWidth());
                    for (int x = 0; x < row.length; x++) row[x] = flattenOnWhite(row[x]);
                    rgb.setRGB(0, y, source.getWidth(), 1, row, 0, source.getWidth());
                }
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
        } finally {
            if (rgb != source) rgb.flush();
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

    private static void validateSourceDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION
                || height > MAX_SOURCE_DIMENSION
                || (long) width * height > MAX_SOURCE_PIXELS) {
            throw invalid("封面源尺寸无效或过大");
        }
    }

    private static int scaleForPixelBudget(int width, int height) {
        int scale = Math.max(1,
                (int) Math.ceil(Math.sqrt((double) width * height / MAX_PIXELS)));
        while ((width + scale - 1L) / scale > MAX_DIMENSION
                || (height + scale - 1L) / scale > MAX_DIMENSION) {
            scale++;
        }
        return scale;
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
