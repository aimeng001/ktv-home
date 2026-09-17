package com.homektv.tv.net

internal object DiscoveryFallbackPolicy {
    fun shouldRunFallback(foundCount: Int): Boolean = foundCount <= 0
}
