package com.homektv.web;

import com.homektv.common.BackgroundJobStatusProvider;
import com.homektv.common.BackgroundJobSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Unified read-only overview of all five background processing subsystems.
 */
@RestController
@RequestMapping("/api/admin/jobs")
public class AdminJobsController {

    private final List<BackgroundJobStatusProvider> providers;

    public AdminJobsController(List<BackgroundJobStatusProvider> providers) {
        this.providers = providers == null ? List.of() : providers;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        List<BackgroundJobSummary> jobs = providers.stream()
                .map(BackgroundJobStatusProvider::jobStatus)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(BackgroundJobSummary::jobKey))
                .toList();
        return Map.of(
                "jobs", jobs,
                "activeCount", jobs.stream().filter(BackgroundJobSummary::running).count());
    }
}
