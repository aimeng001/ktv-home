package com.homektv.tv.controller

import com.homektv.tv.net.LibraryStatus
import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogStatusPolicyTest {
    @Test
    fun scanningStatusIsVisibleAndReadyRevisionCanReplaceIt() {
        val scanning = CatalogStatusPolicy.from(
            LibraryStatus(scanState = "RUNNING", rootState = "READABLE", catalogRevision = 3),
            hasItems = true,
            hasFilter = false,
        )
        val ready = CatalogStatusPolicy.from(
            LibraryStatus(scanState = "COMPLETED", rootState = "READABLE", catalogRevision = 4),
            hasItems = true,
            hasFilter = false,
        )

        assertEquals(CatalogLoadState.SCANNING, scanning.state)
        assertEquals(CatalogLoadState.READY, ready.state)
        assertTrue(CatalogStatusPolicy.acceptsRevision(scanning.revision, ready.revision))
        assertFalse(CatalogStatusPolicy.acceptsRevision(4, 3))
    }

    @Test
    fun emptyAndFilteredEmptyRemainDifferentFromOfflineAndRootFailure() {
        assertEquals(
            CatalogLoadState.EMPTY,
            CatalogStatusPolicy.from(LibraryStatus(scanState = "COMPLETED", rootState = "READABLE"), false, false).state,
        )
        assertEquals(
            CatalogLoadState.FILTER_EMPTY,
            CatalogStatusPolicy.from(LibraryStatus(scanState = "COMPLETED", rootState = "READABLE"), false, true).state,
        )
        assertEquals(CatalogLoadState.ROOT_UNAVAILABLE,
            CatalogStatusPolicy.from(LibraryStatus(rootState = "NOT_READABLE"), false, false).state)
        assertEquals(CatalogLoadState.OFFLINE, CatalogStatusPolicy.from(null, false, false).state)
    }

    @Test
    fun transportFailureIsHttpErrorAndDoesNotReuseOldRevision() {
        val status = CatalogStatusPolicy.fromError(
            KtvApiError(KtvApiErrorKind.HTTP, "HTTP_503", "service unavailable"),
            revision = 7,
        )
        assertEquals(CatalogLoadState.HTTP_ERROR, status.state)
        assertEquals(7L, status.revision)
        assertEquals("HTTP_503", status.code)
    }

    @Test
    fun completedCatalogWithUnknownOrMismatchedRootIdentityIsUnavailable() {
        val mismatch = CatalogStatusPolicy.from(
            LibraryStatus(
                rootState = "READABLE",
                rootIdentityState = "MISMATCH",
                countsKnown = false,
                scanState = "COMPLETED",
                catalogRevision = 8,
            ),
            hasItems = true,
            hasFilter = false,
        )
        val unknown = CatalogStatusPolicy.from(
            LibraryStatus(
                rootState = "READABLE",
                rootIdentityState = "UNKNOWN",
                countsKnown = false,
                scanState = "COMPLETED",
                catalogRevision = 8,
            ),
            hasItems = true,
            hasFilter = false,
        )

        assertEquals(CatalogLoadState.ROOT_UNAVAILABLE, mismatch.state)
        assertEquals(CatalogLoadState.ROOT_UNAVAILABLE, unknown.state)
    }

    @Test
    fun legacyServerResponsesResolveReadyFilteredEmptyAndEmptyStates() {
        val legacy = LibraryStatus()

        assertEquals(CatalogLoadState.READY, CatalogStatusPolicy.from(legacy, true, false).state)
        assertEquals(CatalogLoadState.FILTER_EMPTY, CatalogStatusPolicy.from(legacy, false, true).state)
        assertEquals(CatalogLoadState.EMPTY, CatalogStatusPolicy.from(legacy, false, false).state)
    }

    @Test
    fun completedCatalogWithKnownCountsAndUnknownIdentityRemainsAvailable() {
        val status = CatalogStatusPolicy.from(
            LibraryStatus(
                rootState = "READABLE",
                rootIdentityState = "UNKNOWN",
                countsKnown = true,
                scanState = "COMPLETED",
                catalogRevision = 0,
                statusRevision = 9,
            ),
            hasItems = true,
            hasFilter = false,
        )

        assertEquals(CatalogLoadState.READY, status.state)
        assertEquals(9L, status.revision)
    }

    @Test
    fun unavailableAndCompletedPhasesAreNotReportedAsScanning() {
        val missingRoot = CatalogStatusPolicy.from(
            LibraryStatus(rootState = "NOT_FOUND", statusRevision = 3), false, false,
        )
        val completedIdle = CatalogStatusPolicy.from(
            LibraryStatus(rootState = "READABLE", scanState = "IDLE", phase = "COMPLETED"), false, false,
        )

        assertEquals(CatalogLoadState.ROOT_UNAVAILABLE, missingRoot.state)
        assertEquals(3L, missingRoot.revision)
        assertEquals(CatalogLoadState.EMPTY, completedIdle.state)
    }

    @Test
    fun partialFailuresAndServerErrorCodesRemainVisible() {
        val failed = CatalogStatusPolicy.from(
            LibraryStatus(rootState = "READABLE", scanState = "FAILED", statusRevision = 6), true, false,
        )
        val partial = CatalogStatusPolicy.from(
            LibraryStatus(rootState = "READABLE", scanState = "PARTIAL", catalogRevision = 7), true, false,
        )
        val errorCode = CatalogStatusPolicy.from(
            LibraryStatus(rootState = "READABLE", scanState = "COMPLETED", errorCode = "SCAN_WARNING"),
            true,
            false,
        )

        assertEquals(CatalogLoadState.PARTIAL_FAILURE, failed.state)
        assertEquals(CatalogLoadState.PARTIAL_FAILURE, partial.state)
        assertEquals(CatalogLoadState.PARTIAL_FAILURE, errorCode.state)
        assertEquals("SCAN_WARNING", errorCode.code)
    }

    @Test
    fun networkErrorsAndNonPositiveRevisionsRemainBackwardCompatible() {
        val network = CatalogStatusPolicy.fromError(
            KtvApiError(KtvApiErrorKind.NETWORK, "OFFLINE", "network unavailable"),
        )

        assertEquals(CatalogLoadState.OFFLINE, network.state)
        assertTrue(CatalogStatusPolicy.acceptsRevision(8, 0))
        assertTrue(CatalogStatusPolicy.acceptsRevision(8, -1))
        assertTrue(CatalogStatusPolicy.acceptsRevision(0, 1))
        assertTrue(CatalogStatusPolicy.acceptsRevision(-1, 1))
    }
}
