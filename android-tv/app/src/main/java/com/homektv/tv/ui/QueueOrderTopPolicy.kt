package com.homektv.tv.ui

import com.homektv.tv.net.KtvApiResult
import com.homektv.tv.net.QueueSnapshot

sealed class OrderTopOutcome {
    data class SuccessTop(val snapshot: QueueSnapshot, val message: String) : OrderTopOutcome()
    data class FallbackOrderOnly(val snapshot: QueueSnapshot, val warning: String) : OrderTopOutcome()
    data class OrderFailed(val error: String) : OrderTopOutcome()
}

/**
 * 优先插播决策策略，杜绝陈旧快照与盲目虚假成功提示。
 */
object QueueOrderTopPolicy {
    suspend fun resolve(
        orderResult: KtvApiResult<QueueSnapshot>,
        songId: Long,
        topInvoker: suspend (queueId: Long) -> KtvApiResult<QueueSnapshot>,
    ): OrderTopOutcome {
        return when (orderResult) {
            is KtvApiResult.Failure -> OrderTopOutcome.OrderFailed(orderResult.error.message)
            is KtvApiResult.Success -> {
                val targetEntry = orderResult.value.list.lastOrNull { it.song?.id == songId }
                val queueId = targetEntry?.queueId
                val isNowPlaying = orderResult.value.playing?.song?.id == songId
                when {
                    queueId != null -> {
                        when (val topResult = topInvoker(queueId)) {
                            is KtvApiResult.Success -> OrderTopOutcome.SuccessTop(topResult.value, "已优先插播")
                            is KtvApiResult.Failure -> OrderTopOutcome.FallbackOrderOnly(
                                orderResult.value,
                                "已点歌但置顶失败: ${topResult.error.message}",
                            )
                        }
                    }
                    isNowPlaying -> OrderTopOutcome.SuccessTop(orderResult.value, "已优先播放")
                    else -> OrderTopOutcome.FallbackOrderOnly(orderResult.value, "已点歌，未找到待播序号")
                }
            }
        }
    }
}
