package com.homektv.tv.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.homektv.tv.R
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.MediaApi
import com.homektv.tv.session.DeviceMode
import com.homektv.tv.session.DeviceSessionChangePolicy
import com.homektv.tv.session.DeviceSessionFingerprint
import com.homektv.tv.ui.controller.ControllerFragment

/** Activity host for the native controller Fragment. */
class ControllerActivity : AppCompatActivity() {

    private var sessionFingerprint: DeviceSessionFingerprint? = null
    private var updateManager: AndroidUpdateManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialConfig = AppConfig(this)
        sessionFingerprint = DeviceSessionFingerprint(
            serverHost = initialConfig.serverHost,
            mode = initialConfig.effectiveMode(),
            nickname = initialConfig.nicknameFor(),
        )
        applyOrientation()
        setContentView(R.layout.activity_controller)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.controllerFragmentHost, ControllerFragment.newInstance(
                    intent.getBooleanExtra(ControllerFragment.EXTRA_REALTIME, true),
                ))
                .commit()
        }
        if (initialConfig.isConfigured) {
            val manager = AndroidUpdateManager(this, MediaApi(initialConfig))
            updateManager = manager
            manager.checkForUpdate(lifecycleScope)
        }
    }

    override fun onResume() {
        super.onResume()
        val currentConfig = AppConfig(this)
        val previous = sessionFingerprint ?: return
        val current = DeviceSessionFingerprint(
            serverHost = currentConfig.serverHost,
            mode = currentConfig.effectiveMode(),
            nickname = currentConfig.nicknameFor(),
        )
        if (DeviceSessionChangePolicy.requiresRestart(previous, current)) {
            if (current.mode == previous.mode && current.mode == DeviceMode.CONTROLLER) {
                recreate()
                return
            }
            val target = if (current.mode == DeviceMode.CONTROLLER) {
                ControllerActivity::class.java
            } else {
                MainActivity::class.java
            }
            startActivity(Intent(this, target).apply {
                if (target == ControllerActivity::class.java) {
                    putExtra(ControllerFragment.EXTRA_REALTIME, intent.getBooleanExtra(
                        ControllerFragment.EXTRA_REALTIME, true,
                    ))
                }
            })
            finish()
            return
        }
        if (updateManager == null && currentConfig.isConfigured) {
            val manager = AndroidUpdateManager(this, MediaApi(currentConfig))
            updateManager = manager
            manager.checkForUpdate(lifecycleScope)
        }
    }

    private fun applyOrientation() {
        val isTelevision = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        requestedOrientation = when {
            isTelevision || !hasTouchscreen -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            resources.configuration.smallestScreenWidthDp < 600 ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    companion object {
        const val EXTRA_REALTIME = ControllerFragment.EXTRA_REALTIME
    }
}
