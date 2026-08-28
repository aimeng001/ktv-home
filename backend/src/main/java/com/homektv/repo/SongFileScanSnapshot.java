package com.homektv.repo;

/**
 * Lightweight scan-start snapshot.  The scanner only needs identity and queue
 * state before walking the filesystem; loading full SongFile entities here can
 * consume a large heap on a library with hundreds of thousands of rows.
 */
public interface SongFileScanSnapshot {
    Long getId();

    String getFilePath();

    Boolean getProbePending();
}
