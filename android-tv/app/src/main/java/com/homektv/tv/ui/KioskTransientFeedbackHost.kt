package com.homektv.tv.ui

import android.view.View
import android.widget.TextView
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shows short controller feedback inside the kiosk content stage without covering playback controls. */
internal class KioskTransientFeedbackHost(
    private val view: TextView,
    private val scope: CoroutineScope,
) {
    private var dismissJob: Job? = null

    @MainThread
    fun show(message: String) {
        val visibleMessage = message.trim()
        if (visibleMessage.isEmpty()) {
            clear()
            return
        }

        dismissJob?.cancel()
        view.text = visibleMessage
        view.visibility = View.VISIBLE
        dismissJob = scope.launch {
            delay(DISPLAY_DURATION_MILLIS)
            dismissJob = null
            hideView()
        }
    }

    @MainThread
    fun clear() {
        dismissJob?.cancel()
        dismissJob = null
        hideView()
    }

    private fun hideView() {
        view.visibility = View.GONE
        view.text = ""
    }

    private companion object {
        const val DISPLAY_DURATION_MILLIS = 2_500L
    }
}
