package com.homektv.tv.controller

import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.QueueSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerStateDomainErrorTest {

    @Test
    fun testSearchFailureIsRecordedInSearchDomain() {
        val initial = ControllerUiState()
        val error = KtvApiError(kind = KtvApiErrorKind.NETWORK, code = "NETWORK_ERROR", message = "Search network failure")
        val updated = ControllerStateReducer.withSearchFailure(initial, error)

        assertEquals(error, updated.errorFor(UiDomain.SEARCH))
        assertEquals(error, updated.domainErrors[UiDomain.SEARCH])
    }

    @Test
    fun testSnapshotRefreshOnlyClearsQueueDomainErrorsAndPreservesSearchDomainErrors() {
        val initial = ControllerUiState()
        val searchError = KtvApiError(kind = KtvApiErrorKind.NETWORK, code = "NETWORK_ERROR", message = "Search network error")
        val withSearchErr = ControllerStateReducer.withSearchFailure(initial, searchError)

        val queueError = KtvApiError(kind = KtvApiErrorKind.HTTP, code = "QUEUE_FAILED", message = "Queue fetch error")
        val withBothErrors = ControllerStateReducer.withDomainFailure(withSearchErr, UiDomain.QUEUE, queueError)

        assertEquals(searchError, withBothErrors.errorFor(UiDomain.SEARCH))
        assertEquals(queueError, withBothErrors.errorFor(UiDomain.QUEUE))

        // Incoming WebSocket queue snapshot
        val snapshot = QueueSnapshot(stateRevision = 5L, state = "idle")
        val afterSnapshot = ControllerStateReducer.withSnapshot(withBothErrors, snapshot)

        // Queue domain error must be cleared
        assertNull(afterSnapshot.errorFor(UiDomain.QUEUE))
        // Search domain error MUST be preserved!
        assertEquals(searchError, afterSnapshot.errorFor(UiDomain.SEARCH))
    }

    @Test
    fun testDomainMessageDoesNotLeakCatalogFailureIntoHistoryPresentation() {
        val catalogError = KtvApiError(kind = KtvApiErrorKind.HTTP, code = "CATALOG_FAILED", message = "catalog")
        val historyError = KtvApiError(kind = KtvApiErrorKind.NETWORK, code = "HISTORY_FAILED", message = "history")
        val withCatalog = ControllerStateReducer.withDomainFailure(ControllerUiState(), UiDomain.CATALOG, catalogError)
        val withBoth = ControllerStateReducer.withDomainFailure(withCatalog, UiDomain.HISTORY, historyError)

        assertEquals("服务端暂时不可用", withBoth.messageFor(UiDomain.CATALOG))
        assertEquals("无法连接点歌服务", withBoth.messageFor(UiDomain.HISTORY))
        assertEquals(catalogError, withBoth.errorFor(UiDomain.CATALOG))
        assertEquals(historyError, withBoth.errorFor(UiDomain.HISTORY))
    }
    @Test
    fun testSearchSuccessClearsSearchDomainErrorOnly() {
        val initial = ControllerUiState()
        val favError = KtvApiError(kind = KtvApiErrorKind.HTTP, code = "FAV_FAILED", message = "Favorites load failed")
        val withFavErr = ControllerStateReducer.withDomainFailure(initial, UiDomain.FAVORITES, favError)

        val searchError = KtvApiError(kind = KtvApiErrorKind.NETWORK, code = "NETWORK_TIMEOUT", message = "Search timeout")
        val withBoth = ControllerStateReducer.withSearchFailure(withFavErr, searchError)

        val afterSearchStarted = ControllerStateReducer.withSearchStarted(withBoth, "周杰伦")
        assertNull(afterSearchStarted.errorFor(UiDomain.SEARCH))
        // Favorites error must still exist
        assertEquals(favError, afterSearchStarted.errorFor(UiDomain.FAVORITES))
    }
    @Test
    fun testCatalogFailureStopsCatalogLoading() {
        val initial = ControllerUiState(catalogLoading = true)
        val error = KtvApiError(kind = KtvApiErrorKind.HTTP, code = "CATALOG_FAILED", message = "catalog")

        val updated = ControllerStateReducer.withDomainFailure(initial, UiDomain.CATALOG, error)

        assertEquals(false, updated.catalogLoading)
        assertEquals(error, updated.errorFor(UiDomain.CATALOG))
    }
}
