package com.homektv.tv.ui

import com.homektv.tv.net.LibraryScanSubmission

/** Keeps scan feedback aligned with the HTTP outcome instead of implying a successful request. */
internal object LibraryScanFeedbackPolicy {
    const val acceptedMessage = "服务端已接受曲库扫描请求"
    const val connectionFailureMessage = "曲库扫描请求失败，请检查服务连接后重试"

    fun failureMessage(serverMessage: String?): String {
        val detail = serverMessage?.trim()?.takeIf(String::isNotEmpty)
        return detail?.let { "曲库扫描请求失败：$it" } ?: connectionFailureMessage
    }

    fun messageFor(submission: LibraryScanSubmission): String = when (submission.requestStatus) {
        "ACCEPTED" -> if (submission.accepted) acceptedMessage else failureMessage(null)
        "ALREADY_RUNNING" -> if (submission.accepted) "曲库扫描已在运行" else failureMessage(null)
        "NEEDS_REBIND" -> "曲库根目录身份已变化，需要管理员重新确认"
        "ROOT_UNAVAILABLE" -> "曲库根目录当前不可读取"
        "NOT_APPLICABLE" -> "当前服务模式不支持外部曲库扫描"
        "UNAVAILABLE" -> "服务端无法确认曲库扫描状态，未能确认受理"
        else -> if (submission.accepted) acceptedMessage else "服务端未能受理曲库扫描请求，请检查曲库状态后重试"
    }
}
