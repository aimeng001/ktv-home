package com.homektv.tv.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.LanScanner
import com.homektv.tv.net.SavedServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Live, read-only probe for the same health/readiness and saved-identity gate used by setup. */
@RunWith(AndroidJUnit4::class)
class LiveDockerSetupVerificationTest {
    @Test
    fun reachableDockerServerIsAcceptedForTheSavedHost() = runBlocking {
        val host = InstrumentationRegistry.getArguments().getString(ARG_SERVER_HOST)
            ?.let { AppConfig.normalizeHost(it) }
            ?: error("Pass -e $ARG_SERVER_HOST <host:port> to the instrumentation runner")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = AppConfig(context)
        val requested = config.serverForHost(host) ?: SavedServer(host, host)
        val scanner = LanScanner()

        try {
            val discovered = scanner.discover(host)
            assertNotNull("Android health/readiness probe did not discover the live service", discovered)
            assertNotNull(
                "Setup verification rejected the currently saved server identity",
                SetupVerificationPolicy.confirm(requested, discovered!!),
            )
        } finally {
            scanner.close()
        }
    }

    companion object {
        private const val ARG_SERVER_HOST = "serverHost"
    }
}
