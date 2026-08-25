package com.homektv.library;

/**
 * Selects whether the application owns the playback library or only reads an
 * external source library.
 */
public enum LibraryMode {
    /** The existing source-import and managed playback-library workflow. */
    MANAGED,
    /** Read and index the source path in place; never mutate source media. */
    EXTERNAL_READ_ONLY
}
