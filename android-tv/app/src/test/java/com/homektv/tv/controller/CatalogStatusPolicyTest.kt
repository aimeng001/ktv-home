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
}
