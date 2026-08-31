package com.homektv.web;

import com.homektv.library.ArtistLibraryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/artists")
public class ArtistLibraryController {
    private final ArtistLibraryService service;

    public ArtistLibraryController(ArtistLibraryService service) { this.service = service; }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String gender,
                                          @RequestParam(required = false) Boolean reviewed,
                                          @RequestParam(defaultValue = "500") int limit) {
        return service.listForCompatibility(keyword, gender, reviewed, limit);
    }

    @GetMapping("/page")
    public ArtistLibraryService.ArtistPage page(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String gender,
                                                @RequestParam(required = false) Boolean reviewed,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return service.page(keyword, gender, reviewed, page, size);
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody AnalyzeRequest request) { return service.analyze(request.artist()); }

    @PostMapping("/analyze-batch")
    public List<Map<String, Object>> analyzeBatch(@RequestBody BatchAnalyzeRequest request) {
        return service.analyzeBatch(request == null ? List.of() : request.artists());
    }

    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody ApplyRequest request) { return service.apply(request.artist(), request.gender()); }

    public record AnalyzeRequest(String artist) {}
    public record BatchAnalyzeRequest(List<String> artists) {}
    public record ApplyRequest(String artist, String gender) {}
}
