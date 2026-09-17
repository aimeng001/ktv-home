package com.homektv.tv.ui.controller

internal object StableIdPolicy {
    fun hash64(prefix: Long, key: String): Long {
        var hash = -3750763034362895579L
        for (i in 0 until key.length) {
            hash = hash xor key[i].code.toLong()
            hash *= 1099511628211L
        }
        val positiveOffset = hash and 0x00FF_FFFF_FFFF_FFFFL
        return prefix - positiveOffset
    }
}
