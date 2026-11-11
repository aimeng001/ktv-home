package com.homektv.tv.ui

import com.homektv.tv.net.QueueEntry
import com.homektv.tv.net.QueueSnapshot
import java.util.PriorityQueue

/** Formats and orders short-lived announcements derived from authoritative queue snapshots. */
internal class KtvMarqueeNotificationController(
    private val maxPending: Int = MAX_PENDING_NOTIFICATIONS,
) {
    enum class Priority(val weight: Int) {
        ORDER(1),
        TOP(2),
    }

    data class Notification(
        val text: String,
        val priority: Priority,
        internal val key: String,
        internal val sequence: Long,
    )

    private val pending = PriorityQueue(
        compareByDescending<Notification> { it.priority.weight }
            .thenBy { it.sequence },
    )
    private val pendingKeys = mutableSetOf<String>()
    private val recentlyDeliveredKeys = LinkedHashSet<String>()
    private var nextSequence = 0L

    init {
        require(maxPending > 0) { "maxPending must be positive" }
    }

    fun formatOrderNotification(userName: String?, songTitle: String?): String =
        "来自 ${displayValue(userName, DEFAULT_USER)} 点播了《${displayValue(songTitle, DEFAULT_SONG)}》"

    fun formatTopNotification(userName: String?, songTitle: String?): String =
        "来自 ${displayValue(userName, DEFAULT_USER)} 置顶了《${displayValue(songTitle, DEFAULT_SONG)}》"

    fun formatNextNotification(userName: String?, artist: String?, songTitle: String?): String {
        val songDisplay = if (artist.isNullOrBlank()) "《${displayValue(songTitle, DEFAULT_SONG)}》"
            else "《${displayValue(songTitle, DEFAULT_SONG)}》- $artist"
        val userDisplay = if (userName.isNullOrBlank()) "" else "（点播人：$userName）"
        return "接下来播放：$songDisplay$userDisplay"
    }

    fun enqueueOrder(queueId: Long, userName: String?, songTitle: String?): Boolean = enqueue(
        key = "order:$queueId",
        text = formatOrderNotification(userName, songTitle),
        priority = Priority.ORDER,
    )

    fun enqueueTop(
        queueId: Long,
        userName: String?,
        songTitle: String?,
        revision: Long = 0L,
    ): Boolean {
        val revisionKey = revision.takeIf { it > 0L }?.toString() ?: "sequence:${nextSequence + 1}"
        return enqueue(
            key = "top:$queueId:$revisionKey",
            text = formatTopNotification(userName, songTitle),
            priority = Priority.TOP,
        )
    }

    /**
     * Current servers publish queue mutations as `queue_updated` full snapshots. The explicit
     * `order_song` and `top_song` names are also accepted for servers that expose those events.
     */
    fun enqueueSnapshotChange(
        event: String,
        previous: QueueSnapshot?,
        current: QueueSnapshot,
    ): Int {
        val normalizedEvent = event.trim().lowercase()
        val currentEntries = entries(current)
        val previousIds = previous?.let(::entries)
            ?.mapNotNullTo(mutableSetOf()) { it.queueId }
            .orEmpty()

        return when (normalizedEvent) {
            "order_song" -> {
                val added = currentEntries.filter { it.queueId != null && it.queueId !in previousIds }
                val orderEntries = added.ifEmpty {
                    listOfNotNull(
                        current.playing?.let { playing ->
                            QueueEntry(
                                queueId = playing.queueId,
                                song = playing.song,
                                orderedByNick = playing.orderedByNick,
                                status = "playing",
                            )
                        } ?: current.list.lastOrNull(),
                    )
                }
                orderEntries.count { entry ->
                    val song = entry.song ?: return@count false
                    enqueueOrder(entry.queueId ?: return@count false, entry.orderedByNick, song.title)
                }
            }

            "next_song" -> {
                val playing = current.playing ?: return 0
                val song = playing.song ?: return 0
                val queueId = playing.queueId ?: return 0
                if (enqueue(
                    key = "next:$queueId:${current.stateRevision}",
                    text = formatNextNotification(playing.orderedByNick, song.artist, song.title),
                    priority = Priority.TOP,
                )) 1 else 0
            }

            "top_song" -> current.list.firstOrNull(::isWaiting)?.let(::enqueueTopEntry)?.let { if (it) 1 else 0 } ?: 0

            "queue_updated" -> {
                if (previous == null) return 0
                val added = currentEntries.filter { it.queueId != null && it.queueId !in previousIds }
                if (added.isNotEmpty()) {
                    added.count { entry ->
                        val song = entry.song ?: return@count false
                        enqueueOrder(entry.queueId ?: return@count false, entry.orderedByNick, song.title)
                    }
                } else {
                    val movedToFront = findTopMove(previous, current)
                    if (movedToFront == null) 0 else if (enqueueTopEntry(movedToFront, current.stateRevision)) 1 else 0
                }
            }

            else -> 0
        }
    }

    fun pollNext(): Notification? {
        val notification = pending.poll() ?: return null
        pendingKeys.remove(notification.key)
        recentlyDeliveredKeys += notification.key
        while (recentlyDeliveredKeys.size > MAX_RECENTLY_DELIVERED) {
            recentlyDeliveredKeys.remove(recentlyDeliveredKeys.first())
        }
        return notification
    }

    fun hasPendingNotifications(): Boolean = pending.isNotEmpty()

    private fun enqueue(key: String, text: String, priority: Priority): Boolean {
        if (key in pendingKeys || key in recentlyDeliveredKeys) return false
        val notification = Notification(text, priority, key, ++nextSequence)
        if (pending.size >= maxPending) {
            val lowest = pending.minWithOrNull(
                compareBy<Notification> { it.priority.weight }
                    .thenByDescending { it.sequence },
            ) ?: return false
            if (notification.priority.weight <= lowest.priority.weight) return false
            pending.remove(lowest)
            pendingKeys.remove(lowest.key)
        }
        pending += notification
        pendingKeys += key
        return true
    }

    private fun enqueueTopEntry(entry: QueueEntry, revision: Long = 0L): Boolean {
        val queueId = entry.queueId ?: return false
        val song = entry.song ?: return false
        return enqueueTop(queueId, entry.orderedByNick, song.title, revision)
    }

    private fun findTopMove(previous: QueueSnapshot, current: QueueSnapshot): QueueEntry? {
        val previousWaiting = previous.list.filter(::isWaiting)
        val currentWaiting = current.list.filter(::isWaiting)
        val candidate = currentWaiting.firstOrNull() ?: return null
        val candidateId = candidate.queueId ?: return null
        val oldIndex = previousWaiting.indexOfFirst { it.queueId == candidateId }
        if (oldIndex <= 0) return null

        val oldRemainder = previousWaiting.filterNot { it.queueId == candidateId }.mapNotNull { it.queueId }
        val newRemainder = currentWaiting.drop(1).mapNotNull { it.queueId }
        return candidate.takeIf { oldRemainder == newRemainder }
    }

    private fun entries(snapshot: QueueSnapshot): List<QueueEntry> = buildList {
        snapshot.playing?.let { playing ->
            add(
                QueueEntry(
                    queueId = playing.queueId,
                    song = playing.song,
                    orderedByNick = playing.orderedByNick,
                    status = "playing",
                ),
            )
        }
        addAll(snapshot.list)
    }.distinctBy { it.queueId }

    private fun isWaiting(entry: QueueEntry): Boolean =
        entry.status.equals("waiting", ignoreCase = true)

    private fun displayValue(value: String?, fallback: String): String = value
        ?.trim()
        ?.replace(WHITESPACE, " ")
        ?.take(MAX_LABEL_LENGTH)
        ?.takeIf(String::isNotBlank)
        ?: fallback

    companion object {
        private const val DEFAULT_USER = "手机用户"
        private const val DEFAULT_SONG = "未知歌曲"
        private const val MAX_LABEL_LENGTH = 80
        private const val MAX_PENDING_NOTIFICATIONS = 32
        private const val MAX_RECENTLY_DELIVERED = 128
        private val WHITESPACE = Regex("\\s+")
    }
}
