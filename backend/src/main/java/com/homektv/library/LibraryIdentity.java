package com.homektv.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileStore;
import java.util.Objects;

/**
 * Stable identity evidence for the active library root.
 *
 * <p>The source library is read-only. Identity is therefore derived only from
 * path/filesystem metadata; the application never writes a marker into the
 * source tree.</p>
 */
public record LibraryIdentity(String normalizedRoot, String token, boolean verified) {
    public static LibraryIdentity resolve(Path root) {
        if (root == null) return new LibraryIdentity("", "", false);
        Path normalized = root.toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(normalized)) {
                return new LibraryIdentity(normalized.toString(), "", false);
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            FileStore store = Files.getFileStore(normalized);
            String fileKey = Objects.toString(attributes.fileKey(), "");
            String storeKey = store.name() + "|" + store.type();
            boolean verified = !fileKey.isBlank();
            String token = storeKey + "|" + fileKey;
            return new LibraryIdentity(normalized.toString(), token, verified);
        } catch (IOException | RuntimeException failure) {
            return new LibraryIdentity(normalized.toString(), "", false);
        }
    }

    public String persistedValue() {
        return token.isBlank() ? normalizedRoot : normalizedRoot + "|" + token;
    }

    public static IdentityState compare(String stored, LibraryIdentity current) {
        if (current == null || current.normalizedRoot().isBlank()) {
            return IdentityState.UNKNOWN;
        }
        if (stored == null || stored.isBlank()) {
            return IdentityState.UNKNOWN;
        }
        if (stored.equals(current.persistedValue())) {
            return IdentityState.MATCH;
        }
        // Rows created before V46 only carried the normalized path. Keep those
        // rows claimable once so V46 can upgrade them to filesystem evidence.
        if (stored.equals(current.normalizedRoot())) {
            return IdentityState.MATCH;
        }
        // A different configured path is already strong evidence that the
        // process is looking at another library, even when the filesystem does
        // not expose a stable file key (common on some Windows mounts).
        int separator = stored.indexOf('|');
        String storedRoot = separator < 0 ? stored : stored.substring(0, separator);
        if (!storedRoot.equals(current.normalizedRoot())) {
            return IdentityState.MISMATCH;
        }
        if (!current.verified()) {
            return IdentityState.UNKNOWN;
        }
        return IdentityState.MISMATCH;
    }

    public enum IdentityState {
        MATCH,
        MISMATCH,
        UNKNOWN
    }
}

