package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibraryScanFeedbackPolicyTest {
    @Test
    fun acceptedResponseDoesNotClaimScanCompletion() {
        assertEquals("服务端已接受曲库扫描请求", LibraryScanFeedbackPolicy.acceptedMessage)
        assertFalse(LibraryScanFeedbackPolicy.acceptedMessage.contains("已完成"))
    }

    @Test
    fun failedResponseSurfacesErrorInsteadOfReportingSubmission() {
        assertEquals(
            "曲库扫描请求失败：服务暂不可用",
            LibraryScanFeedbackPolicy.failureMessage(" 服务暂不可用 "),
        )
        assertFalse(LibraryScanFeedbackPolicy.failureMessage("服务暂不可用").contains("已提交"))
    }

    @Test
    fun missingErrorDetailUsesRecoverableConnectionMessage() {
        assertEquals(
            "曲库扫描请求失败，请检查服务连接后重试",
            LibraryScanFeedbackPolicy.failureMessage("  "),
        )
        assertEquals(
            "曲库扫描请求失败，请检查服务连接后重试",
            LibraryScanFeedbackPolicy.connectionFailureMessage,
        )
    }

    @Test
    fun httpSuccessWithRejectedSubmissionIsReportedAsRejected() {
        assertEquals(
            "曲库根目录身份已变化，需要管理员重新确认",
            LibraryScanFeedbackPolicy.messageFor(
                com.homektv.tv.net.LibraryScanSubmission(
                    accepted = false,
                    requestStatus = "NEEDS_REBIND",
                    scanState = "IDLE",
                    errorCode = "LIBRARY_ROOT_IDENTITY_CHANGED",
                ),
            ),
        )
    }
}
