package com.homektv.system;

import com.homektv.config.AppProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reports the identity of the running artifact without exposing configuration secrets.
 * The migration value is read from the database so an old image cannot claim the
 * schema version of the source tree.
 */
@Service
public class BuildInfoService {
    private final AppProperties properties;
    private final Flyway flyway;
    private final String gitSha;
    private final String buildTime;

    public BuildInfoService(AppProperties properties, Flyway flyway,
                            @Value("${build.git-sha:unknown}") String gitSha,
                            @Value("${build.time:unknown}") String buildTime) {
        this.properties = properties;
        this.flyway = flyway;
        this.gitSha = normalize(gitSha);
        this.buildTime = normalize(buildTime);
    }

    public BuildInfo get() {
        return new BuildInfo(
                normalize(properties.getRelease().getVersion()),
                gitSha,
                buildTime,
                currentMigration());
    }

    private int currentMigration() {
        try {
            MigrationInfo current = flyway.info().current();
            if (current == null || current.getVersion() == null) return 0;
            String version = current.getVersion().getVersion();
            return Integer.parseInt(version);
        } catch (RuntimeException ignored) {
            // Build identity remains useful when the database is temporarily unavailable.
            return 0;
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    public record BuildInfo(String version, String gitSha, String buildTime, int latestMigration) {}
}
