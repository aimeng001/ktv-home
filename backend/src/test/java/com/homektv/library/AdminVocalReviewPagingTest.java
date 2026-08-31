package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.ws.WsBroadcaster;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminVocalReviewPagingTest {

    private final SongFileRepository fileRepo = mock(SongFileRepository.class);
    private final AdminService service = new AdminService(
            mock(SongRepository.class), fileRepo, mock(PlayHistoryRepository.class),
            mock(WsBroadcaster.class), mock(AssetWriter.class), mock(QueueItemRepository.class),
            mock(PlayerStateRepository.class), new AppProperties());

    @Test
    void clampsInvalidAndOversizedPageSizes() {
        when(fileRepo.findByVocalConfidence(eq("LOW"), any(Pageable.class)))
                .thenAnswer(invocation -> Page.empty(invocation.getArgument(1)));

        assertThat(service.listVocalReview(0, 10_000).getPageable().getPageSize()).isEqualTo(200);
        assertThat(service.listVocalReview(0, 0).getPageable().getPageSize()).isEqualTo(1);
        assertThat(service.listVocalReview(-1, -1).getPageable().getPageSize()).isEqualTo(1);
        Page<?> normalPage = service.listVocalReview(-1, 20);
        assertThat(normalPage.getPageable().getPageNumber()).isZero();
        assertThat(normalPage.getPageable().getPageSize()).isEqualTo(20);
    }
}
