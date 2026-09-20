package com.homektv.web;

import com.homektv.domain.PlaybackVariant;
import com.homektv.playback.PlaybackVariantService;
import com.homektv.repo.PlaybackVariantRepository;
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

/** Range stream for validated playback-cache files. */
@RestController
@RequestMapping("/api/playback")
public class PlaybackVariantStreamController {

    private static final int BUF = 64 * 1024;
    private static final long LEASE_RENEW_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    private final PlaybackVariantRepository variants;
    private final PlaybackVariantService variantService;

    public PlaybackVariantStreamController(PlaybackVariantRepository variants,
                                            PlaybackVariantService variantService) {
        this.variants = variants;
        this.variantService = variantService;
    }

    @GetMapping("/stream/{variantId}")
    public ResponseEntity<StreamingResponseBody> stream(
            @PathVariable Long variantId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
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
            return ResponseEntity.ok()
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .contentLength(length)
                    .body(writeRegion(file, 0, length, variantId));
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
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length)
                .contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(count)
                .body(writeRegion(file, start, count, variantId));
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
                long nextLeaseRenewal = System.nanoTime() + LEASE_RENEW_INTERVAL_NANOS;
                while (remaining > 0) {
                    int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                    if (System.nanoTime() >= nextLeaseRenewal) {
                        variantService.renewStreamLease(variantId);
                        nextLeaseRenewal = System.nanoTime() + LEASE_RENEW_INTERVAL_NANOS;
                    }
                }
                out.flush();
            }
        };
    }
}
