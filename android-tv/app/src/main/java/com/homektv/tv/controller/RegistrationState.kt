package com.homektv.tv.controller

import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.UserProfile

/** Registration is a write gate, not a best-effort decoration of the UI. */
enum class RegistrationStatus {
    IDLE,
    REGISTERING,
    REGISTERED,
    RETRY_REQUIRED,
}

object RegistrationReducer {
    private val registrationOptionalActions = setOf(
        "play", "pause", "stop", "seek", "restart", "next",
        "set_volume", "mute", "set_vocal", "swap_vocal_tracks", "effect",
    )

    fun started(state: ControllerUiState): ControllerUiState = state.copy(
        registration = RegistrationStatus.REGISTERING,
        error = null,
        message = null,
    )

    fun succeeded(state: ControllerUiState, profile: UserProfile): ControllerUiState {
        if (profile.id <= 0L || profile.nickname.isBlank()) {
            return failed(
                state,
                KtvApiError(
                    kind = com.homektv.tv.net.KtvApiErrorKind.DECODE,
                    code = "INVALID_USER",
                    message = "服务端未返回有效用户身份",
                ),
            )
        }
        val isHost = state.roomHost.claimed && state.roomHost.hostUserId == profile.id
        return state.copy(
            currentUser = profile,
            roomHost = state.roomHost.copy(isHost = isHost),
            queueProjection = com.homektv.tv.ui.SongQueueProjection.from(
                state.queue,
                profile.id,
                isHost,
            ),
            registration = RegistrationStatus.REGISTERED,
            error = null,
            message = null,
        )
    }

    fun failed(state: ControllerUiState, error: KtvApiError): ControllerUiState = state.copy(
        currentUser = null,
        registration = RegistrationStatus.RETRY_REQUIRED,
        error = error,
        message = "点歌身份注册失败，请检查服务连接后重试",
    )

    fun canWrite(state: ControllerUiState): Boolean =
        state.registration == RegistrationStatus.REGISTERED && state.currentUser?.id ?: 0L > 0L

    /** Playback controls do not change user-owned queue state and can work while identity retries. */
    fun requiresRegistration(action: String): Boolean = action !in registrationOptionalActions

    fun canPerform(state: ControllerUiState, action: String): Boolean =
        !requiresRegistration(action) || canWrite(state)
}
