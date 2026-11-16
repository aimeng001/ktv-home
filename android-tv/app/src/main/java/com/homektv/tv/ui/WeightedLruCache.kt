package com.homektv.tv.ui

import androidx.annotation.MainThread

/** Access-ordered cache with both entry and weighted byte limits. */
@MainThread
internal class WeightedLruCache<K, V>(
    private val capacity: Int,
    private val maxBytes: Long,
    private val weight: (V) -> Long,
) {
    private val values = LinkedHashMap<K, V>(capacity.coerceAtLeast(1), 0.75f, true)
    private val loading = mutableSetOf<K>()
    var byteSize: Long = 0L
        private set

    operator fun get(key: K): V? = values[key]

    operator fun set(key: K, value: V) {
        put(key, value)
    }

    fun contains(key: K): Boolean = values[key] != null

    fun tryStartLoad(key: K): Boolean {
        if (values[key] != null || loading.contains(key)) return false
        return loading.add(key)
    }

    fun complete(key: K, value: V) {
        loading.remove(key)
        put(key, value)
    }

    fun fail(key: K) {
        loading.remove(key)
    }

    fun put(key: K, value: V) {
        values.remove(key)?.let { byteSize -= weightOf(it) }
        values[key] = value
        byteSize += weightOf(value)
        trim()
    }

    fun clear() {
        loading.clear()
        values.clear()
        byteSize = 0L
    }

    val size: Int
        get() = values.size

    private fun trim() {
        while (values.size > capacity || byteSize > maxBytes) {
            val iterator = values.entries.iterator()
            if (!iterator.hasNext()) {
                byteSize = 0L
                return
            }
            val removed = iterator.next()
            byteSize -= weightOf(removed.value)
            iterator.remove()
        }
    }

    private fun weightOf(value: V): Long = weight(value).coerceAtLeast(0L)
}
