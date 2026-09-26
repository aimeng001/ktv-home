package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskFeedbackRoutingPolicyTest {
    @Test
    fun visibleMatchingPageErrorIsNotRepeatedAsAToast() {
        assertEquals(
            KioskFeedbackDestination.INLINE,
            KioskFeedbackRoutingPolicy.resolve(
                message = "请先配置点歌服务",
                inlineErrorMessage = "请先配置点歌服务",
                inlineErrorIsLatest = true,
            ),
        )
    }

    @Test
    fun actionFeedbackAndErrorsWithoutVisibleInlineCopyRemainTransient() {
        assertEquals(
            KioskFeedbackDestination.TRANSIENT,
            KioskFeedbackRoutingPolicy.resolve(
                message = "已点歌：测试歌曲",
                inlineErrorMessage = "请先配置点歌服务",
                inlineErrorIsLatest = true,
            ),
        )
        assertEquals(
            KioskFeedbackDestination.TRANSIENT,
            KioskFeedbackRoutingPolicy.resolve(
                message = "没有操作权限",
                inlineErrorMessage = null,
                inlineErrorIsLatest = false,
            ),
        )
    }

    @Test
    fun staleDomainErrorCannotSwallowAnUnrelatedNewActionMessage() {
        assertEquals(
            KioskFeedbackDestination.TRANSIENT,
            KioskFeedbackRoutingPolicy.resolve(
                message = "请先配置点歌服务",
                inlineErrorMessage = "请先配置点歌服务",
                inlineErrorIsLatest = false,
            ),
        )
    }

    @Test
    fun missingMessageProducesNoFeedback() {
        assertEquals(
            KioskFeedbackDestination.NONE,
            KioskFeedbackRoutingPolicy.resolve(null, inlineErrorMessage = null, inlineErrorIsLatest = false),
        )
    }
}
