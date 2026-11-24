package com.homektv.web;

import com.homektv.musicsource.MusicMetadataScrapeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/metadata-scrapes")
public class MusicMetadataScrapeController {
    private final MusicMetadataScrapeService service;

    public MusicMetadataScrapeController(MusicMetadataScrapeService service) { this.service = service; }

    @PostMapping
    public Map<String, Object> start(@RequestBody StartRequest request) {
        return service.start(request.all(), request.songIds(), request.autoApplyThreshold());
    }

    @GetMapping("/latest")
    public Map<String, Object> latest() { return service.latest(); }

    @GetMapping("/{batchId}")
    public Map<String, Object> details(@PathVariable String batchId,
                                       @RequestParam(defaultValue = "") String status,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return service.details(batchId, status, page, size);
    }

    @PostMapping("/{batchId}/pause")
    public Map<String, Object> pause(@PathVariable String batchId) { return service.pause(batchId); }

    @PostMapping("/{batchId}/cancel")
    public Map<String, Object> cancel(@PathVariable String batchId) { return service.cancel(batchId); }

    @PostMapping("/{batchId}/resume")
    public Map<String, Object> resume(@PathVariable String batchId) { return service.resume(batchId); }

    @PostMapping("/{batchId}/items/{itemId}/retry")
    public Map<String, Object> retry(@PathVariable String batchId, @PathVariable long itemId) {
        return service.retryItem(batchId, itemId);
    }

    @PostMapping("/{batchId}/items/{itemId}/apply")
    public Map<String, Object> apply(@PathVariable String batchId, @PathVariable long itemId,
                                     @RequestBody(required = false) ApplyRequest request) {
        return service.applyItem(batchId, itemId, request == null ? Set.of() : request.fields(),
                request == null ? Map.of() : request.overrides(), request == null ? null : request.provider(),
                request == null ? null : request.externalId(), request != null && request.completeOnly());
    }

    public record StartRequest(boolean all, List<Long> songIds, Double autoApplyThreshold) {}
    public record ApplyRequest(Set<String> fields, Map<String, String> overrides, String provider, String externalId,
                               boolean completeOnly) {}
}
