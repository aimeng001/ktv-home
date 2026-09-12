package com.homektv.tv.controller

enum class KioskPersonalView {
    LIST,
    DETAIL_LOADING,
    DETAIL,
    DETAIL_ERROR,
}

/** Keeps a TV playlist detail request from falling back to stale list content. */
object KioskPersonalPresentationPolicy {
    fun resolve(
        inDetail: Boolean,
        loading: Boolean,
        hasDetail: Boolean,
        hasError: Boolean,
    ): KioskPersonalView = when {
        inDetail && loading -> KioskPersonalView.DETAIL_LOADING
        inDetail && hasDetail -> KioskPersonalView.DETAIL
        inDetail && hasError -> KioskPersonalView.DETAIL_ERROR
        else -> KioskPersonalView.LIST
    }
}
