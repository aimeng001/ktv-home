package com.homektv.web;

import com.homektv.common.BackgroundJobStatusProvider;
import com.homektv.common.BackgroundJobSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminJobsControllerTest {

    @Test
    void aggregatesAllProvidersSortedByJobKeyAndCountsActiveJobs() {
        BackgroundJobStatusProvider p1 = () -> new BackgroundJobSummary(
                "metadata-scrape", "在线元数据刮削", "RUNNING", true, 100, 40, 2, 58, true, true, Map.of());
        BackgroundJobStatusProvider p2 = () -> new BackgroundJobSummary(
                "ai-analysis", "AI 分析/修复", "IDLE", false, 0, 0, 0, 0, true, true, Map.of());

        AdminJobsController controller = new AdminJobsController(List.of(p1, p2));
        Map<String, Object> overview = controller.overview();

        assertThat(overview).containsEntry("activeCount", 1L);
        @SuppressWarnings("unchecked")
        List<BackgroundJobSummary> jobs = (List<BackgroundJobSummary>) overview.get("jobs");
        assertThat(jobs).extracting(BackgroundJobSummary::jobKey)
                .containsExactly("ai-analysis", "metadata-scrape");
    }
}
