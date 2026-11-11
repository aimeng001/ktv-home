package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Setting;
import com.homektv.repo.SettingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingServiceRuntimeCacheTest {
    @Test
    void delayedGetAllReadCannotOverwriteTheCommittedRuntimeCache() throws Exception {
        CountDownLatch oldReadCaptured = new CountDownLatch(1);
        CountDownLatch releaseOldRead = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> storedValue = new AtomicReference<>("\"旧包房名\"");
        SettingRepository repository = repositoryWith(storedValue, reads, 2, oldReadCaptured, releaseOldRead);
        SettingService service = new SettingService(repository, new ObjectMapper());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            assertThat(service.getDiscoveryName("fallback")).isEqualTo("旧包房名");
            Future<Map<String, Object>> staleRead = executor.submit(service::getAll);
            assertThat(oldReadCaptured.await(5, TimeUnit.SECONDS)).isTrue();

            service.putEditable(Map.of(SettingService.DISCOVERY_NAME, "新包房名"));
            assertThat(service.getDiscoveryName("fallback")).isEqualTo("新包房名");

            releaseOldRead.countDown();
            assertThat(staleRead.get(5, TimeUnit.SECONDS))
                    .containsEntry(SettingService.DISCOVERY_NAME, "旧包房名");
            assertThat(service.getDiscoveryName("fallback")).isEqualTo("新包房名");
        } finally {
            releaseOldRead.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cacheMissStartedBeforeWriteCannotPublishItsOldValueAfterRefresh() throws Exception {
        CountDownLatch oldReadCaptured = new CountDownLatch(1);
        CountDownLatch releaseOldRead = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> storedValue = new AtomicReference<>("\"旧包房名\"");
        SettingRepository repository = repositoryWith(storedValue, reads, 1, oldReadCaptured, releaseOldRead);
        SettingService service = new SettingService(repository, new ObjectMapper());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<String> initialRead = executor.submit(() -> service.getDiscoveryName("fallback"));
            assertThat(oldReadCaptured.await(5, TimeUnit.SECONDS)).isTrue();

            service.putAll(Map.of(SettingService.DISCOVERY_NAME, "新包房名"));
            releaseOldRead.countDown();
            assertThat(initialRead.get(5, TimeUnit.SECONDS)).isEqualTo("新包房名");
            assertThat(service.getDiscoveryName("fallback")).isEqualTo("新包房名");
        } finally {
            releaseOldRead.countDown();
            executor.shutdownNow();
        }
    }

    private static SettingRepository repositoryWith(
            AtomicReference<String> storedValue,
            AtomicInteger reads,
            int blockedReadNumber,
            CountDownLatch oldReadCaptured,
            CountDownLatch releaseOldRead) {
        SettingRepository repository = mock(SettingRepository.class);
        when(repository.findAll()).thenAnswer(invocation -> {
            String valueAtReadStart = storedValue.get();
            int readNumber = reads.incrementAndGet();
            if (readNumber == blockedReadNumber) {
                oldReadCaptured.countDown();
                if (!releaseOldRead.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release stale settings read");
                }
            }
            return List.of(new Setting(SettingService.DISCOVERY_NAME, valueAtReadStart));
        });
        when(repository.findById(anyString())).thenAnswer(invocation -> {
            String value = storedValue.get();
            return SettingService.DISCOVERY_NAME.equals(invocation.getArgument(0))
                    ? java.util.Optional.of(new Setting(SettingService.DISCOVERY_NAME, value))
                    : java.util.Optional.empty();
        });
        when(repository.save(any(Setting.class))).thenAnswer(invocation -> {
            Setting setting = invocation.getArgument(0);
            if (SettingService.DISCOVERY_NAME.equals(setting.getKey())) storedValue.set(setting.getValue());
            return setting;
        });
        return repository;
    }
}
