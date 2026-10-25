package com.homektv.musicsource;

import com.homektv.web.ApiException;
import com.homektv.testutil.FakeFfmpegProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoverImageNormalizerTest {
    @TempDir
    Path temp;

    private final CoverImageNormalizer normalizer = new CoverImageNormalizer("ffmpeg-command-not-needed");

    @Test
    void springCreatesComponentUsingConfiguredFfmpegPath() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(CoverImageNormalizer.class);
            context.refresh();

            assertThat(context.getBean(CoverImageNormalizer.class)).isNotNull();
        }
    }

    @Test
    void normalizesImageByActualBytesRegardlessOfResponseMime() throws Exception {
        BufferedImage source = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        byte[] normalized = normalizer.normalize(png.toByteArray());

        assertThat(normalized).startsWith((byte) 0xff, (byte) 0xd8);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
        assertThat(decoded.getWidth()).isEqualTo(4);
        assertThat(decoded.getHeight()).isEqualTo(3);
    }

    @Test
    void flattensTransparentImagesToJpeg() throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        byte[] normalized = normalizer.normalize(png.toByteArray());

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
        Color background = new Color(decoded.getRGB(0, 0));
        assertThat(background.getRed()).isGreaterThan(245);
        assertThat(background.getGreen()).isGreaterThan(245);
        assertThat(background.getBlue()).isGreaterThan(245);
    }

    @Test
    void preservesOpaquePixelsInsideAnAlphaCapableImage() throws Exception {
        BufferedImage source = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, x < 16 ? 0xffff2000 : 0x80000000);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalizer.normalize(png.toByteArray())));
        Color opaque = new Color(decoded.getRGB(8, 8));
        Color translucent = new Color(decoded.getRGB(24, 8));
        assertThat(opaque.getRed()).isGreaterThan(200);
        assertThat(opaque.getGreen()).isLessThan(80);
        assertThat(translucent.getRed()).isBetween(110, 160);
        assertThat(translucent.getGreen()).isBetween(110, 160);
        assertThat(translucent.getBlue()).isBetween(110, 160);
    }

    @Test
    void rejectsContentThatCannotBeDecodedOrConverted() {
        assertThatThrownBy(() -> normalizer.normalize("not an image".getBytes()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");
    }

    @Test
    void rejectsOversizedInputBeforeImageDecodeOrFfmpegFallback() throws Exception {
        BufferedImage source = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);
        byte[] oversized = Arrays.copyOf(png.toByteArray(), CoverImageNormalizer.MAX_INPUT_BYTES + 1);

        assertThatThrownBy(() -> normalizer.normalize(oversized))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");
    }

    @Test
    void rejectsOversizedImageDimensionsFromTheHeaderBeforeStartingFfmpeg() throws Exception {
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);
        byte[] oversized = pngWithDimensions(png.toByteArray(), 4_097, 1);
        AtomicBoolean processStarted = new AtomicBoolean();
        CoverImageNormalizer guarded = new CoverImageNormalizer("unused", command -> {
            processStarted.set(true);
            throw new AssertionError("Oversized dimensions must be rejected before conversion");
        });

        assertThatThrownBy(() -> guarded.normalize(oversized))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");
        assertThat(processStarted).isFalse();
    }

    @Test
    void rejectsAnOversizedSourceHeightFromTheHeaderBeforeStartingFfmpeg() throws Exception {
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);
        byte[] oversized = pngWithDimensions(png.toByteArray(), 1, 4_097);
        AtomicBoolean processStarted = new AtomicBoolean();
        CoverImageNormalizer guarded = new CoverImageNormalizer("unused", command -> {
            processStarted.set(true);
            throw new AssertionError("Oversized source height must be rejected before conversion");
        });

        assertThatThrownBy(() -> guarded.normalize(oversized))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");
        assertThat(processStarted).isFalse();
    }

    @Test
    void subsamplesAnImageThatFitsTheSourceLimitButExceedsTheOutputDimension() throws Exception {
        BufferedImage source = new BufferedImage(4_096, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        byte[] normalized = normalizer.normalize(png.toByteArray());

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
        assertThat(decoded.getWidth()).isEqualTo(2_048);
        assertThat(decoded.getHeight()).isEqualTo(1);
    }

    @Test
    void subsamplesTallImagesToTheOutputDimensionLimit() throws Exception {
        BufferedImage source = new BufferedImage(1, 4_096, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalizer.normalize(png.toByteArray())));

        assertThat(decoded.getWidth()).isEqualTo(1);
        assertThat(decoded.getHeight()).isEqualTo(2_048);
    }

    @Test
    void rejectsNullAndEmptyInputWithoutStartingFfmpeg() {
        AtomicBoolean processStarted = new AtomicBoolean();
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg", command -> {
            processStarted.set(true);
            throw new AssertionError("Invalid input must be rejected before launching ffmpeg");
        });

        assertThatThrownBy(() -> fallback.normalize(null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");
        assertThatThrownBy(() -> fallback.normalize(new byte[0]))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");
        assertThat(processStarted).isFalse();
    }

    @Test
    void rejectsWhenAllImageDecodePermitsAreBusy() throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(source, "png", png);
        CoverImageNormalizer busy = new CoverImageNormalizer("unused", command -> {
            throw new AssertionError("A busy normalizer must not launch ffmpeg");
        }, new Semaphore(0));

        assertThatThrownBy(() -> busy.normalize(png.toByteArray()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("IMAGE_BUSY");
    }

    @Test
    void removesTemporaryFilesWhenFfmpegExitsWithFailure() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg", args -> {
            command.set(List.copyOf(args));
            return FakeFfmpegProcess.failing(args);
        });

        assertThatThrownBy(() -> fallback.normalize("unsupported upstream bytes".getBytes()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");

        assertTemporaryFilesRemoved(command.get());
    }

    @Test
    void rejectsAnInvalidFfmpegOutputAndRemovesTemporaryFiles() throws Exception {
        Path invalidOutput = temp.resolve("invalid-converted-cover.jpg");
        Files.writeString(invalidOutput, "not a JPEG");
        AtomicReference<List<String>> command = new AtomicReference<>();
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg", args -> {
            command.set(List.copyOf(args));
            return FakeFfmpegProcess.coverFallback(args, invalidOutput);
        });

        assertThatThrownBy(() -> fallback.normalize("unsupported upstream bytes".getBytes()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");

        assertTemporaryFilesRemoved(command.get());
    }

    @Test
    void rejectsAnEmptyFfmpegOutputAndRemovesTemporaryFiles() throws Exception {
        Path emptyOutput = Files.createFile(temp.resolve("empty-converted-cover.jpg"));
        AtomicReference<List<String>> command = new AtomicReference<>();
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg", args -> {
            command.set(List.copyOf(args));
            return FakeFfmpegProcess.coverFallback(args, emptyOutput);
        });

        assertThatThrownBy(() -> fallback.normalize("unsupported upstream bytes".getBytes()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");

        assertTemporaryFilesRemoved(command.get());
    }

    @Test
    void rejectsFfmpegOutputAboveTheByteLimitWithoutLoadingItIntoMemory() throws Exception {
        Path oversizedOutput = temp.resolve("oversized-converted-cover.jpg");
        Files.write(oversizedOutput, new byte[10 * 1024 * 1024 + 1]);
        AtomicReference<List<String>> command = new AtomicReference<>();
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg", args -> {
            command.set(List.copyOf(args));
            return FakeFfmpegProcess.coverFallback(args, oversizedOutput);
        });

        assertThatThrownBy(() -> fallback.normalize("unsupported upstream bytes".getBytes()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");

        assertTemporaryFilesRemoved(command.get());
    }

    @Test
    void rejectsSuccessfulFfmpegExitWhenTheOutputFileIsMissing() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg", args -> {
            command.set(List.copyOf(args));
            Files.delete(Path.of(args.get(args.size() - 1)));
            return new ImmediateExitProcess();
        });

        assertThatThrownBy(() -> fallback.normalize("unsupported upstream bytes".getBytes()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");

        assertTemporaryFilesRemoved(command.get());
    }

    @Test
    void convertsProcessLaunchIoErrorsToInvalidCoverAndCleansTemporaryFiles() {
        AtomicReference<List<String>> command = new AtomicReference<>();
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg", args -> {
            command.set(List.copyOf(args));
            throw new java.io.IOException("synthetic process launch failure");
        });

        assertThatThrownBy(() -> fallback.normalize("unsupported upstream bytes".getBytes()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EXTERNAL_COVER_INVALID");

        assertTemporaryFilesRemoved(command.get());
    }

    @Test
    void terminatesFfmpegAndRestoresInterruptStatusWhenConversionIsInterrupted() {
        InterruptedProcess process = new InterruptedProcess();
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg", command -> process);

        try {
            assertThatThrownBy(() -> fallback.normalize("unsupported upstream bytes".getBytes()))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("中断");
            assertThat(process.forciblyDestroyed).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void waitsForFfmpegToExitAfterAConversionTimeout() {
        TimeoutProcess process = new TimeoutProcess();
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg", command -> process);

        assertThatThrownBy(() -> fallback.normalize("unsupported upstream bytes".getBytes()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超时");

        assertThat(process.destroyed.get()).isTrue();
        assertThat(process.waitedAfterDestroy.get()).isTrue();
    }

    @Test
    void usesFfmpegFallbackWhenImageIoCannotDecodeTheSource() throws Exception {
        BufferedImage fixture = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        fixture.setRGB(0, 0, Color.BLUE.getRGB());
        Path converted = temp.resolve("converted.jpg");
        ImageIO.write(fixture, "jpg", converted.toFile());
        CoverImageNormalizer fallback = new CoverImageNormalizer("fake-ffmpeg",
                command -> FakeFfmpegProcess.coverFallback(command, converted));

        byte[] normalized = fallback.normalize("unsupported upstream bytes".getBytes());

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
        assertThat(decoded.getWidth()).isEqualTo(3);
        assertThat(decoded.getHeight()).isEqualTo(2);
    }

    private static void assertTemporaryFilesRemoved(List<String> command) {
        assertThat(command).isNotNull();
        int inputIndex = command.indexOf("-i") + 1;
        Path input = Path.of(command.get(inputIndex));
        Path output = Path.of(command.get(command.size() - 1));
        assertThat(Files.exists(input)).isFalse();
        assertThat(Files.exists(output)).isFalse();
    }

    private static byte[] pngWithDimensions(byte[] png, int width, int height) {
        ByteBuffer header = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        header.putInt(16, width);
        header.putInt(20, height);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        header.putInt(29, (int) crc.getValue());
        return png;
    }

    private static final class TimeoutProcess extends Process {
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final AtomicBoolean waitedAfterDestroy = new AtomicBoolean();

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 143; }

        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            if (destroyed.get()) {
                waitedAfterDestroy.set(true);
                return true;
            }
            return false;
        }

        @Override public int exitValue() {
            if (!destroyed.get()) throw new IllegalThreadStateException("process is still running");
            return 143;
        }

        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() { destroyed.set(true); return this; }
        @Override public boolean isAlive() { return !destroyed.get(); }
    }

    private static final class ImmediateExitProcess extends Process {
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return false; }
    }

    private static final class InterruptedProcess extends Process {
        private boolean forciblyDestroyed;

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { throw new InterruptedException(); }

        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (!forciblyDestroyed) throw new InterruptedException();
            return true;
        }

        @Override public int exitValue() {
            if (!forciblyDestroyed) throw new IllegalThreadStateException("process is still running");
            return 143;
        }

        @Override public void destroy() { forciblyDestroyed = true; }
        @Override public Process destroyForcibly() { forciblyDestroyed = true; return this; }
        @Override public boolean isAlive() { return !forciblyDestroyed; }
    }
}
