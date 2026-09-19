package com.homektv.library;

import java.util.Objects;

/**
 * Selects the authoritative value for a library metadata field.
 *
 * Structured filename metadata is opt-in because existing Managed libraries
 * may intentionally prefer embedded tags. A manual lock always wins, including
 * an explicitly empty value.
 */
public final class LibraryMetadataResolver {

    private LibraryMetadataResolver() {}

    public record Value(String text, String source) {
        public Value {
            text = text == null ? "" : text.trim();
            source = source == null ? "unknown" : source.trim();
        }
    }

    public record Candidates(
            boolean manualLocked,
            Value current,
            boolean structuredFilenamePreferred,
            Value structuredFilename,
            Value legacyResolved) {
    }

    public static Value resolve(Candidates input) {
        Objects.requireNonNull(input, "input");
        if (input.manualLocked()) {
            return Objects.requireNonNull(input.current(), "locked current value");
        }
        if (input.structuredFilenamePreferred()
                && input.structuredFilename() != null
                && !input.structuredFilename().text().isBlank()) {
            return input.structuredFilename();
        }
        return Objects.requireNonNull(input.legacyResolved(), "legacyResolved");
    }

    /** A four-part filename is recognized only when all four semantic fields exist. */
    public static boolean isStructured(ParsedMeta parsed) {
        return parsed != null
                && parsed.recognized()
                && !parsed.artist().isBlank()
                && !parsed.title().isBlank()
                && !parsed.language().isBlank()
                && !parsed.category().isBlank();
    }
}
