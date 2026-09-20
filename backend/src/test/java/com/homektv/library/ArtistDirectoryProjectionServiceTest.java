package com.homektv.library;

import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ArtistDirectoryProjectionServiceTest {

    @Test
    void concurrentRefreshesMustBeSerializedToAvoidDuplicateProjectionRows() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicBoolean firstUpdate = new AtomicBoolean(true);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                if (firstUpdate.compareAndSet(true, false)) {
                    firstEntered.countDown();
                    release.await(2, TimeUnit.SECONDS);
                } else {
                    secondEntered.countDown();
                }
                return 1;
            } finally {
                inFlight.decrementAndGet();
            }
        }).when(jdbc).update(anyString(), any(Object[].class));

        ArtistDirectoryProjectionService service = new ArtistDirectoryProjectionService(jdbc);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(service::refresh);
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(service::refresh);

            assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(maxInFlight.get()).isEqualTo(1);

            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertThat(maxInFlight.get()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void refreshQueryMustUseSingleCreditGenderAndPreserveManualProfiles() {
        assertThat(ArtistDirectoryProjectionService.REFRESH_SQL)
                .contains("credit_count = 1")
                .contains("count(DISTINCT artist_gender) = 1")
                .contains("gender_status = 'MANUAL'");
    }

    @Test
    void projectionMigrationMustProvideLookupIndexes() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V48__artist_directory_projection.sql");
        assertThat(Files.readString(migration))
                .contains("CREATE TABLE artist_directory_stats")
                .contains("idx_artist_directory_stats_lookup")
                .contains("artist_directory_projection_state");
    }
}
