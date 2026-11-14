package com.homektv.tv.ui

enum class KioskBrowserBackAction {
    LEAVE_INNER_DETAIL,
    RETURN_TO_DASHBOARD,
}

object KioskBackNavigationPolicy {
    fun resolveBrowserBack(hasInnerDetailBack: Boolean): KioskBrowserBackAction =
        if (hasInnerDetailBack) {
            KioskBrowserBackAction.LEAVE_INNER_DETAIL
        } else {
            KioskBrowserBackAction.RETURN_TO_DASHBOARD
        }
}
