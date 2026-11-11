package com.homektv.library;

import com.homektv.ai.AiConfigService;
import com.homektv.ai.SecretCryptoService;
import com.homektv.discovery.DiscoverySettingsChangedEvent;
import com.homektv.domain.Setting;
import com.homektv.repo.SettingRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/** Real Spring/PostgreSQL transaction checks for settings and AI private-network permission. */
@SpringBootTest(properties = {
        "app.discovery.enabled=false",
        "app.ai.allow-private-network=false"
})
@Testcontainers
@Isolated("Testcontainers JUnit extension does not support parallel test execution")
@Import(ApplicationSettingsTransactionIntegrationTest.ProbeConfiguration.class)
class ApplicationSettingsTransactionIntegrationTest {

    private static final String PRIVATE_MODEL_URL = "http://127.0.0.1:11434/v1";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ktv").withUsername("ktv").withPassword("ktv");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired SettingService settingService;
    @Autowired AiConfigService aiConfigService;
    @Autowired SettingRepository settingRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DiscoveryEventProbe discoveryEventProbe;
    @MockitoSpyBean SecretCryptoService secretCryptoService;

    @BeforeEach
    void clearTestSettingsAndEventProbe() {
        jdbc.update("DELETE FROM settings WHERE key LIKE 'ai.%' OR key IN (?, ?)",
                SettingService.DISCOVERY_ENABLED, SettingService.DISCOVERY_NAME);
        discoveryEventProbe.clear();
    }

    @Test
    void discoverySettingsPublishOnlyAfterRealPostgresCommitAndRemainSilentOnRollback() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        DiscoverySettingsChangedEvent committed = new DiscoverySettingsChangedEvent(false, "实际提交的包房");

        transaction.executeWithoutResult(status -> {
            settingService.putEditable(Map.of(
                    SettingService.DISCOVERY_ENABLED, false,
                    SettingService.DISCOVERY_NAME, committed.name()));
            assertThat(discoveryEventProbe.events()).isEmpty();
        });

        assertThat(discoveryEventProbe.events()).containsExactly(committed);
        assertThat(settingRepository.findById(SettingService.DISCOVERY_NAME))
                .get().extracting(Setting::getValue).isEqualTo("\"实际提交的包房\"");

        discoveryEventProbe.clear();
        transaction.executeWithoutResult(status -> {
            settingService.putEditable(Map.of(SettingService.DISCOVERY_NAME, "必须回滚的包房"));
            status.setRollbackOnly();
        });

        assertThat(discoveryEventProbe.events()).isEmpty();
        assertThat(settingRepository.findById(SettingService.DISCOVERY_NAME))
                .get().extracting(Setting::getValue).isEqualTo("\"实际提交的包房\"");
        assertThat(settingService.getDiscoveryName("fallback")).isEqualTo(committed.name());
    }

    @Test
    void failedAiUpdateRollsBackDatabaseAndPrivateNetworkPermission() {
        doThrow(new IllegalStateException("simulated encryption failure"))
                .when(secretCryptoService).encrypt(anyString(), anyString());

        assertThatThrownBy(() -> aiConfigService.update(new AiConfigService.ConfigUpdate(
                false, PRIVATE_MODEL_URL, "test-key", false, "local-model", null,
                10, 0.97, 0.92, "AUTO", 2, 1, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated encryption failure");

        assertThat(settingRepository.findById("ai.allow_private_network")).isEmpty();
        assertThatThrownBy(() -> aiConfigService.validateOutboundBaseUrl(PRIVATE_MODEL_URL))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("KTV_AI_ALLOW_PRIVATE_NETWORK=true");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        DiscoveryEventProbe discoveryEventProbe() {
            return new DiscoveryEventProbe();
        }
    }

    static class DiscoveryEventProbe {
        private final List<DiscoverySettingsChangedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void onDiscoverySettingsChanged(DiscoverySettingsChangedEvent event) {
            events.add(event);
        }

        List<DiscoverySettingsChangedEvent> events() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }
}
