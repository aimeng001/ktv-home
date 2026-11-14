package com.homektv.tv.ui

enum class SongCardActionSlot {
    ORDER,
    ORDER_TOP,
    FAVORITE,
}

data class SongCardFocusChain(
    val entrySlot: SongCardActionSlot?,
    val nextRightBySlot: Map<SongCardActionSlot, SongCardActionSlot?>,
    val nextLeftBySlot: Map<SongCardActionSlot, SongCardActionSlot?>,
)

/** Builds the horizontal D-pad focus chain across enabled song-card action buttons. */
object SongCardFocusChainPolicy {
    fun resolveChain(
        orderEnabled: Boolean,
        topEnabled: Boolean,
        favoriteEnabled: Boolean,
    ): SongCardFocusChain {
        val enabled = buildList {
            if (orderEnabled) add(SongCardActionSlot.ORDER)
            if (topEnabled) add(SongCardActionSlot.ORDER_TOP)
            if (favoriteEnabled) add(SongCardActionSlot.FAVORITE)
        }
        val nextRight = buildMap {
            enabled.forEachIndexed { index, slot ->
                put(slot, enabled.getOrNull(index + 1))
            }
        }
        val nextLeft = buildMap {
            enabled.forEachIndexed { index, slot ->
                put(slot, if (index == 0) null else enabled[index - 1])
            }
        }
        return SongCardFocusChain(
            entrySlot = enabled.firstOrNull(),
            nextRightBySlot = nextRight,
            nextLeftBySlot = nextLeft,
        )
    }

    fun nextFallbackAfterDisable(
        disabledSlot: SongCardActionSlot,
        orderEnabled: Boolean,
        topEnabled: Boolean,
        favoriteEnabled: Boolean,
    ): SongCardActionSlot? {
        val chain = resolveChain(orderEnabled, topEnabled, favoriteEnabled)
        return when (disabledSlot) {
            SongCardActionSlot.ORDER -> chain.entrySlot
            SongCardActionSlot.ORDER_TOP -> when {
                favoriteEnabled -> SongCardActionSlot.FAVORITE
                orderEnabled -> SongCardActionSlot.ORDER
                else -> null
            }
            SongCardActionSlot.FAVORITE -> when {
                topEnabled -> SongCardActionSlot.ORDER_TOP
                orderEnabled -> SongCardActionSlot.ORDER
                else -> null
            }
        }
    }
}
