package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.discovery.DiscoverySettingsChangedEvent;
import com.homektv.domain.Setting;
import com.homektv.repo.SettingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettingServiceDiscoveryEventTest {
    private final SettingRepository repository = mock(SettingRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final List<Setting> stored = new ArrayList<>();

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void discoverySettingsPublishSnapshotOnlyAfterCommit() {
        configureRepository();
        SettingService service = new SettingService(repository, new ObjectMapper(), null, eventPublisher);
        beginTransactionSynchronization();

        service.putEditable(Map.of(
                SettingService.DISCOVERY_ENABLED, false,
                SettingService.DISCOVERY_NAME, "VIP包房"));

        verify(eventPublisher, never()).publishEvent(any(Object.class));
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        var event = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new DiscoverySettingsChangedEvent(false, "VIP包房"));
    }

    @Test
    void unchangedDiscoverySettingsDoNotPublishAnEvent() {
        configureRepository();
        SettingService service = new SettingService(repository, new ObjectMapper(), null, eventPublisher);

        service.putEditable(Map.of(
                SettingService.DISCOVERY_ENABLED, true,
                SettingService.DISCOVERY_NAME, "家庭KTV"));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void discoverySettingsDoNotPublishWhenTransactionRollsBack() {
        configureRepository();
        SettingService service = new SettingService(repository, new ObjectMapper(), null, eventPublisher);
        beginTransactionSynchronization();

        service.putEditable(Map.of(SettingService.DISCOVERY_ENABLED, false));
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rolledBackDiscoverySettingsDoNotReplaceCommittedCacheValues() {
        configureRepository();
        SettingService service = new SettingService(repository, new ObjectMapper(), null, eventPublisher);
        assertThat(service.isDiscoveryEnabled(true)).isTrue();
        List<Setting> committedValues = new ArrayList<>(stored.stream()
                .map(setting -> new Setting(setting.getKey(), setting.getValue())).toList());
        beginTransactionSynchronization();

        service.putEditable(Map.of(SettingService.DISCOVERY_ENABLED, false));

        assertThat(service.isDiscoveryEnabled(true)).isTrue();
        stored.clear();
        stored.addAll(committedValues);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(service.isDiscoveryEnabled(true)).isTrue();
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void configureRepository() {
        when(repository.findAll()).thenAnswer(invocation -> new ArrayList<>(stored));
        when(repository.findById(anyString())).thenAnswer(invocation -> stored.stream()
                .filter(setting -> invocation.getArgument(0).equals(setting.getKey()))
                .findFirst());
        when(repository.save(any(Setting.class))).thenAnswer(invocation -> {
            Setting setting = invocation.getArgument(0);
            stored.removeIf(existing -> setting.getKey().equals(existing.getKey()));
            stored.add(setting);
            return setting;
        });
    }
}
