package com.homektv.tv.ui

/** Selects a live secondary presentation display without treating ordinary/virtual displays as HDMI. */
internal object KtvExternalDisplayPolicy {
    data class DisplayCandidate(
        val id: Int,
        val isPresentation: Boolean,
        val isOff: Boolean,
    )

    fun choose(displays: List<DisplayCandidate>): DisplayCandidate? = displays
        .asSequence()
        .filter { it.id != 0 && it.isPresentation && !it.isOff }
        .sortedBy { it.id }
        .firstOrNull()
}
