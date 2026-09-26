package com.homektv.tv.ui

internal enum class KioskFeedbackDestination {
    NONE,
    INLINE,
    TRANSIENT,
}

/** Keep a message transient unless the same latest error is readable in the active page. */
internal object KioskFeedbackRoutingPolicy {
    fun resolve(
        message: String?,
        inlineErrorMessage: String?,
        inlineErrorIsLatest: Boolean,
    ): KioskFeedbackDestination {
        if (message.isNullOrBlank()) return KioskFeedbackDestination.NONE
        return if (inlineErrorIsLatest && message == inlineErrorMessage) {
            KioskFeedbackDestination.INLINE
        } else {
            KioskFeedbackDestination.TRANSIENT
        }
    }
}
