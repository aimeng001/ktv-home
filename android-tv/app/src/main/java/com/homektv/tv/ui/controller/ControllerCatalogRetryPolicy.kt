package com.homektv.tv.ui.controller

import com.homektv.tv.controller.CatalogLoadState

/** Offer an explicit retry for recoverable request/catalog failures, not valid empty or scanning states. */
internal object ControllerCatalogRetryPolicy {
    fun shouldOfferRetry(hasDomainError: Boolean, status: CatalogLoadState): Boolean = when (status) {
        CatalogLoadState.OFFLINE,
        CatalogLoadState.HTTP_ERROR,
        CatalogLoadState.ROOT_UNAVAILABLE,
        CatalogLoadState.PARTIAL_FAILURE -> true
        else -> hasDomainError
    }
}
