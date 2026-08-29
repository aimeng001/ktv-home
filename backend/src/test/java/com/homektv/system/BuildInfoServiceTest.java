package com.homektv.system;

import com.homektv.config.AppProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuildInfoServiceTest {

    @Test
    void buildInfoContainsImmutableArtifactIdentityAndCurrentMigration() {
        AppProperties properties = new AppProperties();
        properties.getRelease().setVersion("1.0.11");
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService migrations = mock(MigrationInfoService.class);
        MigrationInfo current = mock(MigrationInfo.class);
        when(flyway.info()).thenReturn(migrations);
        when(migrations.current()).thenReturn(current);
        when(current.getVersion()).thenReturn(MigrationVersion.fromVersion("25"));

        BuildInfoService service = new BuildInfoService(
                properties, flyway, "0123456789abcdef0123456789abcdef01234567", "2026-08-29T12:00:00Z");

        BuildInfoService.BuildInfo info = service.get();

        assertThat(info.version()).isEqualTo("1.0.11");
        assertThat(info.gitSha()).matches("[0-9a-f]{40}");
        assertThat(info.buildTime()).isEqualTo("2026-08-29T12:00:00Z");
        assertThat(info.latestMigration()).isEqualTo(25);
    }

    @Test
    void buildInfoUsesSafeUnknownValuesWhenRuntimeMetadataIsUnavailable() {
        AppProperties properties = new AppProperties();
        properties.getRelease().setVersion("dev");
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenThrow(new IllegalStateException("database not ready"));

        BuildInfoService.BuildInfo info = new BuildInfoService(
                properties, flyway, "unknown", "unknown").get();

        assertThat(info.version()).isEqualTo("dev");
        assertThat(info.gitSha()).isEqualTo("unknown");
        assertThat(info.buildTime()).isEqualTo("unknown");
        assertThat(info.latestMigration()).isZero();
    }
}
