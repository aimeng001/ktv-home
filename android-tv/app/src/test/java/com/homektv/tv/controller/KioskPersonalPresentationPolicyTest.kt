package com.homektv.tv.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskPersonalPresentationPolicyTest {
    @Test
    fun detailLoadingMustNotFallBackToStalePlaylistList() {
        assertEquals(
            KioskPersonalView.DETAIL_LOADING,
            KioskPersonalPresentationPolicy.resolve(
                inDetail = true,
                loading = true,
                hasDetail = false,
                hasError = false,
            ),
        )
    }

    @Test
    fun detailFailureMustRemainVisibleInsteadOfShowingOldList() {
        assertEquals(
            KioskPersonalView.DETAIL_ERROR,
            KioskPersonalPresentationPolicy.resolve(
                inDetail = true,
                loading = false,
                hasDetail = false,
                hasError = true,
            ),
        )
    }
}
