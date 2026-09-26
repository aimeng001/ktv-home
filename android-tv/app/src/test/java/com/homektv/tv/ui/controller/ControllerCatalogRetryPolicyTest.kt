package com.homektv.tv.ui.controller

import com.homektv.tv.controller.CatalogLoadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerCatalogRetryPolicyTest {
    @Test
    fun retryIsAvailableForRequestFailuresButNotSuccessfulEmptyOrScanningStates() {
        assertTrue(ControllerCatalogRetryPolicy.shouldOfferRetry(false, CatalogLoadState.OFFLINE))
        assertTrue(ControllerCatalogRetryPolicy.shouldOfferRetry(false, CatalogLoadState.HTTP_ERROR))
        assertTrue(ControllerCatalogRetryPolicy.shouldOfferRetry(false, CatalogLoadState.ROOT_UNAVAILABLE))
        assertTrue(ControllerCatalogRetryPolicy.shouldOfferRetry(false, CatalogLoadState.PARTIAL_FAILURE))
        assertTrue(ControllerCatalogRetryPolicy.shouldOfferRetry(true, CatalogLoadState.READY))

        assertFalse(ControllerCatalogRetryPolicy.shouldOfferRetry(false, CatalogLoadState.SCANNING))
        assertFalse(ControllerCatalogRetryPolicy.shouldOfferRetry(false, CatalogLoadState.EMPTY))
        assertFalse(ControllerCatalogRetryPolicy.shouldOfferRetry(false, CatalogLoadState.FILTER_EMPTY))
        assertFalse(ControllerCatalogRetryPolicy.shouldOfferRetry(false, CatalogLoadState.LOADING))
    }
}
