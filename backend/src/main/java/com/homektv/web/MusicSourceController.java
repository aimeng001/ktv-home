package com.homektv.web;

import com.homektv.musicsource.MusicMetadataApplyService;
import com.homektv.musicsource.MusicProvider;
import com.homektv.musicsource.MusicSourceConfig;
import com.homektv.musicsource.MusicSourceConfigService;
import com.homektv.musicsource.MusicSourceSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
public class MusicSourceController {
    private final MusicSourceConfigService configService;
    private final MusicSourceSearchService searchService;
    private final MusicMetadataApplyService applyService;

    public MusicSourceController(MusicSourceConfigService configService, MusicSourceSearchService searchService,
                                 MusicMetadataApplyService applyService) {
        this.configService = configService; this.searchService = searchService; this.applyService = applyService;
    }

    @GetMapping("/music-sources/config")
    public Map<String, Object> config() { return configService.configView(); }

    @PutMapping("/music-sources/config")
    public Map<String, Object> saveConfig(@RequestBody ConfigRequest request) {
        Set<MusicProvider> providers = parseProviders(request.providers());
        configService.save(new MusicSourceConfig(request.enabled(), providers, request.resultLimit(),
                request.timeoutSeconds(), request.searchCacheHours(), request.concurrencyLimit(),
                request.requestIntervalMs(), request.autoApplyThreshold()));
        return configService.configView();
    }

    @PostMapping("/music-sources/test")
    public Map<String, Object> test(@RequestBody(required = false) ProviderRequest request) {
        Set<MusicProvider> providers = request == null ? Set.of() : parseProviders(request.providers());
        return Map.of("results", searchService.test(providers), "providerStatus", configService.states());
    }

    @GetMapping("/songs/{songId}/external-matches")
    public Map<String, Object> songMatches(@PathVariable long songId,
                                           @RequestParam(defaultValue = "false") boolean refresh,
                                           @RequestParam(defaultValue = "") String keyword,
                                           @RequestParam(required = false) List<String> providers) {
        return Map.of("songId", songId, "matches",
                searchService.matches(songId, keyword, parseProviders(providers), refresh));
    }

    @PostMapping("/songs/external-matches/batch")
    public MusicSourceSearchService.BatchMatchResponse batchMatches(@RequestBody BatchMatchRequest request) {
        return searchService.batchMatches(request.songIds());
    }

    @PostMapping("/songs/{songId}/external-matches/{provider}/{externalId}/apply")
    public MusicMetadataApplyService.ApplyResult apply(@PathVariable long songId, @PathVariable String provider,
                                                        @PathVariable String externalId,
                                                        @RequestBody(required = false) MusicMetadataApplyService.ApplyRequest request) {
        validateExternalId(externalId);
        return applyService.apply(songId, MusicProvider.parse(provider), externalId, request);
    }

    @PostMapping("/songs/{songId}/metadata/manual")
    public MusicMetadataApplyService.ApplyResult applyManual(@PathVariable long songId,
                                                              @RequestBody MusicMetadataApplyService.ApplyRequest request) {
        return applyService.applyManual(songId, request);
    }

    private static Set<MusicProvider> parseProviders(List<String> values) {
        Set<MusicProvider> result = new LinkedHashSet<>();
        if (values != null) values.forEach(value -> result.add(MusicProvider.parse(value)));
        return result;
    }

    private static void validateExternalId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,160}"))
            throw new ApiException("EXTERNAL_TRACK_ID_INVALID", "外部歌曲 ID 无效");
    }

    public record ConfigRequest(boolean enabled, List<String> providers, int resultLimit,
                                int timeoutSeconds, int searchCacheHours, int concurrencyLimit,
                                int requestIntervalMs, double autoApplyThreshold) {}
    public record ProviderRequest(List<String> providers) {}
    public record BatchMatchRequest(List<Long> songIds) {}
}
