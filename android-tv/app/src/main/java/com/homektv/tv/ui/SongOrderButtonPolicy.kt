package com.homektv.tv.ui

object SongOrderButtonPolicy {
    data class ButtonState(val text: String, val isEnabled: Boolean)

    fun initialText(isTop: Boolean): String = if (isTop) "优先" else "点歌"
    fun submittingText(isTop: Boolean): String = if (isTop) "插播中..." else "点歌中..."
    fun successText(isTop: Boolean): String = if (isTop) "已置顶" else "已点"

    fun shouldApplyResult(boundSongId: Long?, targetSongId: Long): Boolean =
        boundSongId != null && boundSongId == targetSongId

    fun resolveStateOnBind(
        isTop: Boolean,
        isSubmitting: Boolean,
        queueState: SongQueueState? = null,
        playable: Boolean = true,
    ): ButtonState = when {
        !playable -> ButtonState(text = "准备中", isEnabled = false)
        isSubmitting -> ButtonState(text = submittingText(isTop), isEnabled = false)
        queueState == SongQueueState.Playing -> if (isTop) {
            ButtonState(text = initialText(isTop), isEnabled = false)
        } else {
            ButtonState(text = "演唱中", isEnabled = false)
        }
        queueState is SongQueueState.Waiting -> if (isTop) {
            ButtonState(text = initialText(isTop), isEnabled = queueState.canManage)
        } else {
            ButtonState(text = successText(isTop), isEnabled = false)
        }
        else -> ButtonState(text = initialText(isTop), isEnabled = true)
    }

    fun onSubmit(isTop: Boolean): ButtonState =
        ButtonState(text = submittingText(isTop), isEnabled = false)

    fun onComplete(isTop: Boolean, success: Boolean, playable: Boolean = true): ButtonState =
        if (!playable) {
            ButtonState(text = "准备中", isEnabled = false)
        } else if (success) {
            ButtonState(text = successText(isTop), isEnabled = false)
        } else {
            ButtonState(text = initialText(isTop), isEnabled = true)
        }
}
