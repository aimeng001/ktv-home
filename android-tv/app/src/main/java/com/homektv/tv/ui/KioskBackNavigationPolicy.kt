package com.homektv.tv.ui

enum class KioskBrowserBackAction {
    LEAVE_INNER_DETAIL,
    RETURN_TO_DASHBOARD,
}

enum class KioskBrowserReturnTarget {
    INNER_DETAIL,
    DASHBOARD,
    SEARCH_ORIGIN,
}

object KioskBackNavigationPolicy {
    fun resolveBrowserBack(hasInnerDetailBack: Boolean): KioskBrowserBackAction =
        if (hasInnerDetailBack) {
            KioskBrowserBackAction.LEAVE_INNER_DETAIL
        } else {
            KioskBrowserBackAction.RETURN_TO_DASHBOARD
        }

    fun resolveBrowserBackTarget(
        hasInnerDetailBack: Boolean,
        hasPendingSearchReturnFocus: Boolean,
    ): KioskBrowserReturnTarget = when {
        hasInnerDetailBack -> KioskBrowserReturnTarget.INNER_DETAIL
        hasPendingSearchReturnFocus -> KioskBrowserReturnTarget.SEARCH_ORIGIN
        else -> KioskBrowserReturnTarget.DASHBOARD
    }
}
