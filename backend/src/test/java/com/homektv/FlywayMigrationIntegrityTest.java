package com.homektv;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIntegrityTest {

    private static final Pattern MIGRATION_FILE_PATTERN = Pattern.compile("^V(\\d+)__(.+)\\.sql$");
    private static final int CURRENT_LATEST_VERSION = 46;

    @Test
    void allMigrationVersionsFromOneToLatestArePresentAndContiguous() throws Exception {
        Path migrationDirectory = migrationDirectory();
        Map<Integer, String> versionToFileName = new TreeMap<>();

        try (var files = Files.list(migrationDirectory)) {
            List<Path> sqlFiles = files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .toList();

            for (Path sqlFile : sqlFiles) {
                String fileName = sqlFile.getFileName().toString();
                Matcher matcher = MIGRATION_FILE_PATTERN.matcher(fileName);
                assertThat(matcher.matches())
                        .as("Migration file %s must follow standard naming V<version>__<description>.sql", fileName)
                        .isTrue();

                int version = Integer.parseInt(matcher.group(1));
                String previous = versionToFileName.put(version, fileName);
                assertThat(previous)
                        .as("Duplicate migration version detected: %d in both %s and %s", version, previous, fileName)
                        .isNull();
            }
        }

        assertThat(versionToFileName.keySet())
                .as("Migrations must start from V1 and reach V%d continuously without gaps", CURRENT_LATEST_VERSION)
                .containsExactlyElementsOf(generateExpectedVersions(CURRENT_LATEST_VERSION));
    }

    @Test
    void migrationFilesAreNonEmptyAndPreserveKnownContracts() throws Exception {
        Path migrationDirectory = migrationDirectory();

        for (int i = 1; i <= CURRENT_LATEST_VERSION; i++) {
            Path file = findMigrationPath(migrationDirectory, i);
            assertThat(file)
                    .as("Migration file for V%d must exist", i)
                    .isNotNull();

            String content = Files.readString(file);
            assertThat(content)
                    .as("Migration V%d (%s) must not be empty", i, file.getFileName())
                    .isNotBlank();
        }

        // 固化 V24 必须包含 song_artists 表创建
        Path v24Path = findMigrationPath(migrationDirectory, 24);
        String v24Content = Files.readString(v24Path);
        assertThat(v24Content).contains("CREATE TABLE song_artists");

        Path v46Path = findMigrationPath(migrationDirectory, 46);
        String v46Content = Files.readString(v46Path);
        assertThat(v46Content).contains("library_catalog_revision", "root_identity");
    }

    private Path findMigrationPath(Path migrationDirectory, int version) throws Exception {
        try (var files = Files.list(migrationDirectory)) {
            return files
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        Matcher matcher = MIGRATION_FILE_PATTERN.matcher(name);
                        return matcher.matches() && Integer.parseInt(matcher.group(1)) == version;
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    private List<Integer> generateExpectedVersions(int latest) {
        return java.util.stream.IntStream.rangeClosed(1, latest).boxed().toList();
    }

    private Path migrationDirectory() throws Exception {
        URI resource = getClass().getClassLoader().getResource("db/migration").toURI();
        return Paths.get(resource);
    }
}
