package com.homektv.tv.ui

import android.content.Context
import android.content.SharedPreferences

data class KtvTvSettingsSnapshot(
    val autoFullscreenEnabled: Boolean,
    val globalSearchEnabled: Boolean,
    val exitOnBackEnabled: Boolean,
    val startupSyncEnabled: Boolean = false,
)

internal interface KtvTvBooleanPreferences {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean): Boolean
}

private class SharedPreferencesKtvTvBooleanPreferences(
    private val preferences: SharedPreferences,
) : KtvTvBooleanPreferences {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        preferences.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean): Boolean =
        preferences.edit().putBoolean(key, value).commit()
}

/** Android TV-only preferences. These keys never enter AppConfig or backend settings. */
class KtvTvSettingsStore internal constructor(
    private val preferences: KtvTvBooleanPreferences,
) {
    constructor(context: Context) : this(
        SharedPreferencesKtvTvBooleanPreferences(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
    )

    fun snapshot(): KtvTvSettingsSnapshot = KtvTvSettingsSnapshot(
        autoFullscreenEnabled = preferences.getBoolean(KEY_AUTO_FULLSCREEN, DEFAULT_AUTO_FULLSCREEN),
        globalSearchEnabled = preferences.getBoolean(KEY_GLOBAL_SEARCH, DEFAULT_GLOBAL_SEARCH),
        exitOnBackEnabled = preferences.getBoolean(KEY_EXIT_ON_BACK, DEFAULT_EXIT_ON_BACK),
        startupSyncEnabled = preferences.getBoolean(KEY_STARTUP_SYNC, DEFAULT_STARTUP_SYNC),
    )

    fun set(key: String, value: Boolean): Boolean = when (key) {
        KEY_AUTO_FULLSCREEN, KEY_GLOBAL_SEARCH, KEY_EXIT_ON_BACK, KEY_STARTUP_SYNC -> preferences.putBoolean(key, value)
        else -> false
    }

    companion object {
        const val PREFERENCES_NAME = "ktv_tv_ui_settings"
        const val KEY_AUTO_FULLSCREEN = "auto_fullscreen"
        const val KEY_GLOBAL_SEARCH = "global_search"
        const val KEY_EXIT_ON_BACK = "back_exit"
        const val KEY_STARTUP_SYNC = "startup_sync"

        const val DEFAULT_AUTO_FULLSCREEN = false
        const val DEFAULT_GLOBAL_SEARCH = true
        const val DEFAULT_EXIT_ON_BACK = true
        const val DEFAULT_STARTUP_SYNC = false
    }
}
