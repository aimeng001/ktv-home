package com.homektv.web;

import com.homektv.domain.PlaybackVariant;
import com.homektv.domain.PlaybackVariantProfile;
import com.homektv.playback.PlaybackVariantService;
import com.homektv.repo.PlaybackVariantRepository;
import com.homektv.resource.ResourceBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaybackVariantStreamControllerTest {
    @TempDir
    Path tempDir;

    private PlaybackVariantRepository variants;
    private PlaybackVariantService variantService;
    private PlaybackVariantStreamController controller;
    private PlaybackVariant readyVariant;
    private Path cacheFile;

    @BeforeEach
    void setUp() throws IOException {
        variants = mock(PlaybackVariantRepository.class);
        variantService = mock(PlaybackVariantService.class);
        controller = new PlaybackVariantStreamController(variants, variantService);
        readyVariant = new PlaybackVariant(51L, "fingerprint", PlaybackVariantProfile.H264_AAC_MP4_V1);
        readyVariant.setId(7L);
        readyVariant.markReady("synthetic.mp4", 6);
        cacheFile = Files.write(tempDir.resolve("synthetic.mp4"), new byte[]{0, 1, 2, 3, 4, 5});
        when(variants.findById(7L)).thenReturn(Optional.of(readyVariant));
        when(variantService.readableCachePath(readyVariant)).thenReturn(cacheFile);
    }

    @Test
    void missingOrUnreadyVariantReturnsNotFoundWithoutOpeningCache() {
        when(variants.findById(8L)).thenReturn(Optional.empty());

        assertThat(controller.stream(8L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        readyVariant.setStatus(com.homektv.domain.PlaybackVariantStatus.PREPARING);
        assertThat(controller.stream(7L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(variantService, never()).readableCachePath(readyVariant);
    }

    @Test
    void invalidCacheOrEmptyFileReturnsNotFound() throws IOException {
        when(variantService.readableCachePath(readyVariant))
                .thenThrow(new ApiException("PLAYBACK_CACHE_MISSING", "missing"));
        assertThat(controller.stream(7L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        doReturn(Files.createFile(tempDir.resolve("empty.mp4")))
                .when(variantService).readableCachePath(readyVariant);
        assertThat(controller.stream(7L, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void fullRequestStreamsEveryByteWithRangeSupportHeaders() throws Exception {
        var response = controller.stream(7L, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ((StreamingResponseBody) response.getBody()).writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(6);
        assertThat(output.toByteArray()).containsExactly((byte) 0, (byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5);
    }

    @Test
    void validRangeStreamsOnlyTheRequestedInclusiveBytes() throws Exception {
        var response = controller.stream(7L, "bytes=2-4");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ((StreamingResponseBody) response.getBody()).writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-4/6");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
        assertThat(output.toByteArray()).containsExactly((byte) 2, (byte) 3, (byte) 4);
    }

    @Test
    void variantStreamsUseTheSharedAdmissionLimitAndPreserveRangeResponses() throws Exception {
        ResourceBudget budget = new ResourceBudget(1, 1, 1, 1, 1, () -> false);
        PlaybackVariantStreamController limitedController = new PlaybackVariantStreamController(
                variants, variantService, System::nanoTime, budget);

        var full = limitedController.stream(7L, null);
        var rejected = limitedController.stream(7L, null);
        var invalidRange = limitedController.stream(7L, "bytes=8-");

        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(rejected.getBody()).isNull();
        assertThat(invalidRange.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(budget.activeMediaStreamCount()).isEqualTo(1);

        ((StreamingResponseBody) full.getBody()).writeTo(new ByteArrayOutputStream());
        assertThat(budget.activeMediaStreamCount()).isZero();
        var range = limitedController.stream(7L, "bytes=2-4");
        assertThat(range.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        ((StreamingResponseBody) range.getBody()).writeTo(new ByteArrayOutputStream());
        assertThat(budget.activeMediaStreamCount()).isZero();
    }

    @Test
    void malformedAndOutOfBoundsRangesReturnUnsatisfiedContentRange() {
        var malformed = controller.stream(7L, "items=2-4");
        var outOfBounds = controller.stream(7L, "bytes=7-9");
        var reversed = controller.stream(7L, "bytes=5-4");
        var empty = controller.stream(7L, "bytes=");

        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(malformed.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */6");
        assertThat(outOfBounds.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(reversed.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    }

    @Test
    void blankRangeHeaderStreamsTheFullFile() throws Exception {
        var response = controller.stream(7L, " \t ");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        ((StreamingResponseBody) response.getBody()).writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(output.toByteArray()).containsExactly((byte) 0, (byte) 1, (byte) 2,
                (byte) 3, (byte) 4, (byte) 5);
    }

    @Test
    void shortReadAfterTheCacheFileIsTruncatedEndsTheResponseCleanly() throws Exception {
        var response = controller.stream(7L, "bytes=0-5");
        Files.write(cacheFile, new byte[]{0, 1});
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        ((StreamingResponseBody) response.getBody()).writeTo(output);

        assertThat(output.toByteArray()).containsExactly((byte) 0, (byte) 1);
    }

    @Test
    void renewsTheStreamLeaseWhenAReadCrossesTheRefreshInterval() throws Exception {
        AtomicInteger clockReads = new AtomicInteger();
        long[] times = {0L, TimeUnit.SECONDS.toNanos(30)};
        PlaybackVariantStreamController timedController = new PlaybackVariantStreamController(
                variants, variantService, () -> times[Math.min(clockReads.getAndIncrement(), times.length - 1)]);
        var response = timedController.stream(7L, null);

        ((StreamingResponseBody) response.getBody()).writeTo(new ByteArrayOutputStream());

        verify(variantService).renewStreamLease(7L);
    }

    @Test
    void streamBodyReadsOnlyAndReportsAnIoFailureToTheResponseWriter() throws Exception {
        var response = controller.stream(7L, "bytes=0-5");
        Files.delete(cacheFile);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ((StreamingResponseBody) response.getBody()).writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class);
        verify(variantService).readableCachePath(readyVariant);
    }
}
