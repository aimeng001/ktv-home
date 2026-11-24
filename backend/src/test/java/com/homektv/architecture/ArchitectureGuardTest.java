package com.homektv.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automated architecture fitness functions protecting module boundaries,
 * security choke points, and concurrency bulkheads against regressions.
 */
class ArchitectureGuardTest {

    private static final Path MAIN_SRC_ROOT = Path.of("src/main/java/com/homektv");
    private static final Pattern PACKAGE_DECL = Pattern.compile("^package\\s+com\\.homektv\\.([a-zA-Z0-9_]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT_DECL = Pattern.compile("^import\\s+(?:static\\s+)?com\\.homektv\\.([a-zA-Z0-9_]+)\\.", Pattern.MULTILINE);
    private static final Pattern HTTP_CLIENT_NEW = Pattern.compile("HttpClient\\s*\\.\\s*(?:newBuilder|newHttpClient)\\s*\\(");
    private static final Pattern FILES_MUTATION = Pattern.compile("Files\\s*\\.\\s*(?:delete|deleteIfExists|move|write|writeString|copy|createFile)\\s*\\(");

    @Test
    void nonWebPackagesNeverDependOnWeb() throws IOException {
        List<String> violations = new ArrayList<>();
        for (JavaSource source : loadMainSources()) {
            if ("web".equals(source.pkg())) continue;
            if (source.imports().contains("web") || source.content().contains("com.homektv.web.")) {
                violations.add(source.relativePath());
            }
        }
        assertTrue(violations.isEmpty(),
                "Domain/service packages must never depend back on com.homektv.web: " + violations);
    }

    @Test
    void packageCycleBaselineCannotRegress() throws IOException {
        Map<String, Set<String>> edges = new HashMap<>();
        for (JavaSource source : loadMainSources()) {
            edges.computeIfAbsent(source.pkg(), ignored -> new HashSet<>());
            for (String target : source.imports()) {
                if (!target.equals(source.pkg())) {
                    edges.get(source.pkg()).add(target);
                }
            }
        }

        Set<String> observedCycles = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : edges.entrySet()) {
            String a = entry.getKey();
            for (String b : entry.getValue()) {
                if (edges.getOrDefault(b, Set.of()).contains(a)) {
                    String pair = a.compareTo(b) < 0 ? a + "<->" + b : b + "<->" + a;
                    observedCycles.add(pair);
                }
            }
        }

        Set<String> allowedLegacyCycles = Set.of(
                "ai<->library",
                "library<->musicsource",
                "library<->queue",
                "queue<->ws"
        );
        Set<String> regressions = new TreeSet<>(observedCycles);
        regressions.removeAll(allowedLegacyCycles);
        assertTrue(regressions.isEmpty(),
                "New package cycles introduced! Observed=" + observedCycles + ", disallowed=" + regressions);
        assertTrue(observedCycles.size() <= allowedLegacyCycles.size(),
                "Cycle count must not exceed " + allowedLegacyCycles.size() + ": " + observedCycles);
    }

    @Test
    void controllersDoNotExpandDirectRepositoryCoupling() throws IOException {
        Set<String> legacyControllers = Set.of(
                "ControlController.java",
                "DiscoveryController.java",
                "FavoriteController.java",
                "HistoryController.java",
                "LibraryStatusController.java",
                "SongController.java",
                "StreamController.java",
                "WishController.java"
        );
        Set<String> actual = new TreeSet<>();
        for (JavaSource source : loadMainSources()) {
            if (!"web".equals(source.pkg()) || !source.fileName().endsWith("Controller.java")) continue;
            if (source.imports().contains("repo")) {
                actual.add(source.fileName());
            }
        }
        Set<String> disallowed = new TreeSet<>(actual);
        disallowed.removeAll(legacyControllers);
        assertTrue(disallowed.isEmpty(),
                "New controllers must route through Application/Query services instead of importing repo directly: " + disallowed);
    }

    @Test
    void outboundHttpClientCreationIsCentralized() throws IOException {
        List<String> violations = new ArrayList<>();
        for (JavaSource source : loadMainSources()) {
            if ("OutboundUrlGuard.java".equals(source.fileName())) continue;
            if (HTTP_CLIENT_NEW.matcher(source.content()).find()) {
                violations.add(source.relativePath());
            }
        }
        assertEquals(List.of(), violations,
                "Outbound HttpClients must be created via OutboundUrlGuard.newNoRedirectHttpClient");
    }

    @Test
    void filesystemMutationsStayInApprovedGuards() throws IOException {
        Set<String> allowedWriters = Set.of(
                "SecretCryptoService.java",
                "AssetWriter.java",
                "ManagedFilesystemGuard.java",
                "ManagedLibraryDeleteService.java",
                "MediaImportService.java",
                "MediaTranscoder.java",
                "OutputPathReservation.java",
                "TranscodeService.java",
                "CoverImageNormalizer.java"
        );
        List<String> violations = new ArrayList<>();
        for (JavaSource source : loadMainSources()) {
            if (allowedWriters.contains(source.fileName())) continue;
            if (FILES_MUTATION.matcher(source.content()).find()) {
                violations.add(source.relativePath());
            }
        }
        assertEquals(List.of(), violations,
                "Direct Files delete/move/write/copy calls outside approved guards are forbidden: " + violations);
    }

    private static List<JavaSource> loadMainSources() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SRC_ROOT), "Missing backend source directory: " + MAIN_SRC_ROOT.toAbsolutePath());
        List<JavaSource> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_SRC_ROOT)) {
            for (Path path : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String content = Files.readString(path);
                Matcher pkgMatcher = PACKAGE_DECL.matcher(content);
                String pkg = pkgMatcher.find() ? pkgMatcher.group(1) : "<root>";
                Set<String> imports = new HashSet<>();
                Matcher impMatcher = IMPORT_DECL.matcher(content);
                while (impMatcher.find()) {
                    imports.add(impMatcher.group(1));
                }
                sources.add(new JavaSource(
                        MAIN_SRC_ROOT.relativize(path).toString().replace('\\', '/'),
                        path.getFileName().toString(),
                        pkg,
                        imports,
                        content));
            }
        }
        return sources;
    }

    private record JavaSource(String relativePath, String fileName, String pkg, Set<String> imports, String content) {
    }
}
