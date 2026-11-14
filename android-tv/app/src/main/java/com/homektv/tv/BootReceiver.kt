package com.homektv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.homektv.tv.net.AppConfig
import com.homektv.tv.session.BootLaunchPolicy
import com.homektv.tv.ui.MainActivity
import com.homektv.tv.ui.SetupActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = AppConfig(context)
        if (!config.isConfigured) {
            launch(context, SetupActivity::class.java)
            return
        }
        if (BootLaunchPolicy.shouldLaunchPlayer(configured = true)) {
            launch(context, MainActivity::class.java)
        }
    }

    private fun launch(context: Context, target: Class<*>) {
        context.startActivity(
            Intent(context, target).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
        )
    }
}
