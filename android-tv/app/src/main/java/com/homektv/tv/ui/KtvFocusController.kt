package com.homektv.tv.ui

/**
 * 返回键层级动作枚举。
 */
enum class BackAction {
    DISMISS_DRAWER,
    INNER_DETAIL_BACK,
    EXIT_KIOSK,
    EXIT_APP,
}

/**
 * 电视端遥控器 DPAD 焦点与返回键层级路由控制器。
 *
 * Dispatches Back key by strict priority:
 * 1. Dismiss secondary modal/drawer if open;
 * 2. Return to parent category/artist grid if inside sub-detail list;
 * 3. Exit kiosk ordering console back to fullscreen MV;
 * 4. Double-back / system confirm exit app.
 */
class KtvFocusController {
    var isDrawerOpen: Boolean = false
    var isKioskActive: Boolean = false
    var hasInnerDetailBack: Boolean = false

    fun shouldInterceptBack(action: Int): Boolean {
        return isDrawerOpen || (isKioskActive && hasInnerDetailBack) || isKioskActive
    }

    fun shouldInterceptMenu(): Boolean = isKioskActive || isDrawerOpen

    fun handleBackPress(
        onDismissDrawer: () -> Unit,
        onInnerDetailBack: () -> Unit = {},
        onExitKiosk: () -> Unit,
        onExitApp: () -> Unit,
    ): BackAction {
        return when {
            isDrawerOpen -> {
                onDismissDrawer()
                BackAction.DISMISS_DRAWER
            }
            hasInnerDetailBack -> {
                onInnerDetailBack()
                BackAction.INNER_DETAIL_BACK
            }
            isKioskActive -> {
                onExitKiosk()
                BackAction.EXIT_KIOSK
            }
            else -> {
                onExitApp()
                BackAction.EXIT_APP
            }
        }
    }
}
