package com.homektv.web;

import com.homektv.domain.PlaybackVariant;
import com.homektv.playback.PlaybackVariantService;
import com.homektv.repo.PlaybackVariantRepository;
import com.homektv.resource.ResourceBudget;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Range stream for validated playback-cache files. */
@RestController
@RequestMapping("/api/playback")
public class PlaybackVariantStreamController {

    private static final int BUF = 64 * 1024;
    private static final long LEASE_RENEW_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    private final PlaybackVariantRepository variants;
    private final PlaybackVariantService variantService;
    private final LongSupplier nanoTime;
    private final ResourceBudget resourceBudget;

    @Autowired
    public PlaybackVariantStreamController(PlaybackVariantRepository variants,
                                            PlaybackVariantService variantService,
                                            ResourceBudget resourceBudget) {
        this(variants, variantService, System::nanoTime, resourceBudget);
    }

    public PlaybackVariantStreamController(PlaybackVariantRepository variants,
                                            PlaybackVariantService variantService) {
        this(variants, variantService, System::nanoTime, standaloneResourceBudget());
    }

    PlaybackVariantStreamController(PlaybackVariantRepository variants,
                                    PlaybackVariantService variantService,
                                    LongSupplier nanoTime) {
        this(variants, variantService, nanoTime, standaloneResourceBudget());
    }

    PlaybackVariantStreamController(PlaybackVariantRepository variants,
                                    PlaybackVariantService variantService,
                                    LongSupplier nanoTime,
                                    ResourceBudget resourceBudget) {
        this.variants = variants;
        this.variantService = variantService;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.resourceBudget = Objects.requireNonNull(resourceBudget, "resourceBudget");
    }

    @GetMapping("/stream/{variantId}")
    public ResponseEntity<StreamingResponseBody> stream(
            @PathVariable Long variantId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            HttpServletRequest request) {
        return streamInternal(variantId, rangeHeader, request);
    }

    public ResponseEntity<StreamingResponseBody> stream(Long variantId, String rangeHeader) {
        return streamInternal(variantId, rangeHeader, null);
    }

    private ResponseEntity<StreamingResponseBody> streamInternal(
            Long variantId, String rangeHeader, HttpServletRequest request) {
        PlaybackVariant variant = variants.findById(variantId).orElse(null);
        if (variant == null || !variant.isReady()) return ResponseEntity.notFound().build();
        final Path path;
        try {
            path = variantService.readableCachePath(variant);
        } catch (ApiException e) {
            return ResponseEntity.notFound().build();
        }
        File file = path.toFile();
        long length = file.length();
        if (length <= 0) return ResponseEntity.notFound().build();
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return streamWithMediaLease(ResponseEntity.ok()
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .contentLength(length), writeRegion(file, 0, length, variantId), request);
        }
        long start;
        long end;
        try {
            List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
            if (ranges.isEmpty()) return rangeNotSatisfiable(length);
            HttpRange range = ranges.get(0);
            start = range.getRangeStart(length);
            end = range.getRangeEnd(length);
        } catch (IllegalArgumentException e) {
            return rangeNotSatisfiable(length);
        }
        if (start < 0 || start >= length || end < start) return rangeNotSatisfiable(length);
        long count = end - start + 1;
        return streamWithMediaLease(ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length)
                .contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(count), writeRegion(file, start, count, variantId), request);
    }

    private ResponseEntity<StreamingResponseBody> streamWithMediaLease(
            ResponseEntity.BodyBuilder response,
            StreamingResponseBody body,
            HttpServletRequest request) {
        ResourceBudget.Permit permit = resourceBudget.tryAcquireMediaStream().orElse(null);
        if (permit == null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .build();
        }
        MediaStreamLease lease = new MediaStreamLease(permit);
        try {
            lease.attachTo(request);
            return response.body(lease.wrap(body));
        } catch (RuntimeException | Error error) {
            lease.closeBeforeBodyIfReserved();
            throw error;
        }
    }

    private static ResourceBudget standaloneResourceBudget() {
        return new ResourceBudget(4, 4, 4, () -> false);
    }

    private ResponseEntity<StreamingResponseBody> rangeNotSatisfiable(long length) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + length)
                .build();
    }

    private StreamingResponseBody writeRegion(File file, long offset, long count, long variantId) {
        return out -> {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                byte[] buffer = new byte[BUF];
                long remaining = count;
                long nextLeaseRenewal = nanoTime.getAsLong() + LEASE_RENEW_INTERVAL_NANOS;
                while (remaining > 0) {
                    int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                    if (nanoTime.getAsLong() >= nextLeaseRenewal) {
                        variantService.renewStreamLease(variantId);
                        nextLeaseRenewal = nanoTime.getAsLong() + LEASE_RENEW_INTERVAL_NANOS;
                    }
                }
                out.flush();
            }
        };
    }
}
