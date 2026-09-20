package com.homektv.tv.ui.kiosk

/** Tracks catalog roots independently so one successful root cannot mask another. */
enum class KioskCategoryMode {
    NEW,
    LANGUAGES,
    TAGS,
}

class KioskCategoryLoadPolicy {
    private val loadedModes = mutableSetOf<KioskCategoryMode>()

    fun shouldLoad(mode: KioskCategoryMode): Boolean = mode !in loadedModes

    fun markLoaded(mode: KioskCategoryMode) {
        loadedModes += mode
    }
}
