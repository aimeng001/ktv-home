package com.homektv.tv.ui

import androidx.annotation.MainThread

/**
 * 待机卡片封面 LRU 缓存与加载状态机，约束在主线程操作。
 */
@MainThread
internal class RecommendationCoverCache<V>(private val capacity: Int) {
    private val values = object : LinkedHashMap<Long, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, V>?): Boolean {
            return size > capacity
        }
    }
    private val loading = mutableSetOf<Long>()

    fun get(id: Long): V? = values[id]

    fun contains(id: Long): Boolean = values.containsKey(id)

    fun tryStartLoad(id: Long): Boolean {
        if (values.containsKey(id) || loading.contains(id)) return false
        return loading.add(id)
    }

    fun complete(id: Long, value: V) {
        loading.remove(id)
        values[id] = value
    }

    fun fail(id: Long) {
        loading.remove(id)
    }

    fun size(): Int = values.size

    fun clear() {
        loading.clear()
        values.clear()
    }
}
