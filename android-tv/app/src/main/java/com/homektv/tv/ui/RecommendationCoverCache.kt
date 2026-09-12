package com.homektv.tv.ui

import androidx.annotation.MainThread

/**
 * 待机卡片封面 LRU 缓存与加载状态机，约束在主线程操作。
 */
@MainThread
internal class RecommendationCoverCache<V>(
    capacity: Int,
    maxBytes: Long = Long.MAX_VALUE,
    weight: (V) -> Long = { 1L },
) {
    private val values = WeightedLruCache<Long, V>(capacity, maxBytes, weight)
    private val loading = mutableSetOf<Long>()

    fun get(id: Long): V? = values[id]

    fun contains(id: Long): Boolean = values[id] != null

    fun tryStartLoad(id: Long): Boolean {
        if (values[id] != null || loading.contains(id)) return false
        return loading.add(id)
    }

    fun complete(id: Long, value: V) {
        loading.remove(id)
        values.put(id, value)
    }

    fun fail(id: Long) {
        loading.remove(id)
    }

    fun size(): Int = values.size

    fun byteSize(): Long = values.byteSize

    fun clear() {
        loading.clear()
        values.clear()
    }
}

/** Access-ordered cache with both entry and weighted byte limits. */
@MainThread
internal class WeightedLruCache<K, V>(
    private val capacity: Int,
    private val maxBytes: Long,
    private val weight: (V) -> Long,
) {
    private val values = LinkedHashMap<K, V>(capacity.coerceAtLeast(1), 0.75f, true)
    var byteSize: Long = 0L
        private set

    operator fun get(key: K): V? = values[key]

    operator fun set(key: K, value: V) {
        put(key, value)
    }

    fun put(key: K, value: V) {
        values.remove(key)?.let { byteSize -= weightOf(it) }
        values[key] = value
        byteSize += weightOf(value)
        trim()
    }

    fun clear() {
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
