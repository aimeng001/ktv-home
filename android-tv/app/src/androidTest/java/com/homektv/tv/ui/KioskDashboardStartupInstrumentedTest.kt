package com.homektv.tv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homektv.tv.R
import com.homektv.tv.net.AppConfig
import com.homektv.tv.ui.kiosk.KtvCommercialDashboardView
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies cold startup into MainActivity Dashboard, queue drawer dismiss before browser inflation,
 * and lazy three-column browser activation without UninitializedPropertyAccessException.
 */
@RunWith(AndroidJUnit4::class)
class KioskDashboardStartupInstrumentedTest {
    @Test
    fun coldStartToDashboardQueueDrawerDismissAndLazyBrowserLaunchDoNotCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val server = MinimalKtvHttpStub().start()
        val config = AppConfig(context)
        val previousHost = config.serverHost
        config.serverHost = server.hostPort

        try {
            // Exercise SetupActivity -> MainActivity auto-forward when serverHost is configured.
            val setupScenario = ActivityScenario.launch<SetupActivity>(Intent(context, SetupActivity::class.java))
            SystemClock.sleep(800L)
            setupScenario.close()

            val mainScenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            try {
                assertTrue(
                    "Dashboard should become visible without lateinit browserBinding crash",
                    waitUntilMain(mainScenario, timeoutMs = 10_000L) { activity ->
                        activity.findViewById<KtvCommercialDashboardView>(R.id.kioskDashboardView)?.isShown == true
                    },
                )

                // Keep browserBinding lazy on startup and stay stable across initial StateFlow emission.
                SystemClock.sleep(1_500L)
                mainScenario.onActivity { activity ->
                    assertNull(
                        "Three-column browser should stay uninflated on initial Dashboard cold start",
                        activity.findViewById<View>(R.id.threeColumnBrowserRoot),
                    )
                    activity.findViewById<View>(R.id.btnDockQueue).performClick()
                }

                // Dismiss the queue drawer on Dashboard to exercise onDismissDrawer -> applyKeyboardVisibility.
                SystemClock.sleep(400L)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                SystemClock.sleep(400L)

                mainScenario.onActivity { activity ->
                    assertTrue(
                        "Dashboard should remain visible after dismissing queue drawer",
                        activity.findViewById<KtvCommercialDashboardView>(R.id.kioskDashboardView)?.isShown == true,
                    )
                    activity.findViewById<View>(R.id.tileHotRanking).performClick()
                }

                assertTrue(
                    "Clicking a dashboard tile should lazily inflate and display the three-column browser",
                    waitUntilMain(mainScenario, timeoutMs = 8_000L) { activity ->
                        activity.findViewById<View>(R.id.threeColumnBrowserRoot)?.isShown == true
                    },
                )
            } finally {
                mainScenario.close()
            }
        } finally {
            config.serverHost = previousHost
            server.close()
        }
    }

    private fun waitUntilMain(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long,
        predicate: (MainActivity) -> Boolean,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { matched = predicate(it) }
            if (matched) return true
            SystemClock.sleep(100L)
        }
        return false
    }

    private class MinimalKtvHttpStub : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        val hostPort: String = "127.0.0.1:${socket.localPort}"

        fun start(): MinimalKtvHttpStub {
            executor.execute {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    executor.execute {
                        client.use { conn ->
                            val input = conn.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                            val requestLine = input.readLine().orEmpty()
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val body = when {
                                requestLine.contains("/api/state") || requestLine.contains("/api/queue") ->
                                    """{"playing":null,"queue":[],"state":"idle","vocalMode":"original","stateRevision":1}"""
                                requestLine.contains("/api/catalog") || requestLine.contains("/api/ranking") ||
                                    requestLine.contains("/api/artists") || requestLine.contains("/api/songs") ->
                                    """{"items":[],"total":0,"hasMore":false}"""
                                else -> """{"status":"UP","ok":true}"""
                            }.toByteArray(StandardCharsets.UTF_8)
                            val out = conn.getOutputStream()
                            out.write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(StandardCharsets.ISO_8859_1),
                            )
                            out.write(body)
                            out.flush()
                        }
                    }
                }
            }
            return this
        }

        override fun close() {
            runCatching { socket.close() }
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
