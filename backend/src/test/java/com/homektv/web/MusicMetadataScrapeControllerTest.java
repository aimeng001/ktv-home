package com.homektv.web;

import com.homektv.musicsource.MusicMetadataScrapeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MusicMetadataScrapeControllerTest {

    @Test
    void delegatesBatchLifecycleAndPagingOperations() {
        MusicMetadataScrapeService service = mock(MusicMetadataScrapeService.class);
        MusicMetadataScrapeController controller = new MusicMetadataScrapeController(service);
        Map<String, Object> started = Map.of("batchId", "batch-1");
        Map<String, Object> latest = Map.of("status", "RUNNING");
        Map<String, Object> details = Map.of("items", List.of());
        Map<String, Object> paused = Map.of("status", "PAUSED");
        when(service.start(false, List.of(2L, 4L), 0.8)).thenReturn(started);
        when(service.latest()).thenReturn(latest);
        when(service.details("batch-1", "FAILED", 2, 5)).thenReturn(details);
        when(service.pause("batch-1")).thenReturn(paused);

        assertThat(controller.start(new MusicMetadataScrapeController.StartRequest(false, List.of(2L, 4L), 0.8)))
                .isSameAs(started);
        assertThat(controller.latest()).isSameAs(latest);
        assertThat(controller.details("batch-1", "FAILED", 2, 5)).isSameAs(details);
        assertThat(controller.pause("batch-1")).isSameAs(paused);
        controller.resume("batch-1");
        controller.retry("batch-1", 9L);

        verify(service).resume("batch-1");
        verify(service).retryItem("batch-1", 9L);
    }

    @Test
    void applyUsesEmptyDefaultsForNoBodyAndForwardsAllExplicitOverrides() {
        MusicMetadataScrapeService service = mock(MusicMetadataScrapeService.class);
        MusicMetadataScrapeController controller = new MusicMetadataScrapeController(service);
        Map<String, Object> result = Map.of("applied", true);
        when(service.applyItem(eq("batch-2"), eq(11L), anySet(), anyMap(), eq(null), eq(null), eq(false)))
                .thenReturn(result);

        assertThat(controller.apply("batch-2", 11L, null)).isSameAs(result);
        verify(service).applyItem("batch-2", 11L, Set.of(), Map.of(), null, null, false);

        Set<String> fields = Set.of("title", "artist");
        Map<String, String> overrides = Map.of("title", "人工标题");
        var request = new MusicMetadataScrapeController.ApplyRequest(fields, overrides, "QQ", "ext-1", true);
        controller.apply("batch-2", 12L, request);
        verify(service).applyItem("batch-2", 12L, fields, overrides, "QQ", "ext-1", true);

        var incompleteRequest = new MusicMetadataScrapeController.ApplyRequest(fields, overrides, "QQ", "ext-2", false);
        controller.apply("batch-2", 13L, incompleteRequest);
        verify(service).applyItem("batch-2", 13L, fields, overrides, "QQ", "ext-2", false);
    }
}
