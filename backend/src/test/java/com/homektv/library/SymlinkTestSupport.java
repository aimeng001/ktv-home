package com.homektv.library;

import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Capability gate for security tests that require the host to create real symbolic links. */
final class SymlinkTestSupport {
    private SymlinkTestSupport() { }

    static Path createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            throw new TestAbortedException("Host does not permit symbolic links for this test", unavailable);
        }
    }

    static void requireSymbolicLinkSupport(Path directory) throws IOException {
        Path target = Files.createTempFile(directory, "symlink-target-", ".tmp");
        Path link = directory.resolve("symlink-probe-" + UUID.randomUUID());
        try {
            createSymbolicLinkOrSkip(link, target);
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(target);
        }
    }
}
