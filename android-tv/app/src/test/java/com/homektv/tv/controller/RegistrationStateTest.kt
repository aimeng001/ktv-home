package com.homektv.tv.controller

import com.homektv.tv.net.KtvApiError
import com.homektv.tv.net.KtvApiErrorKind
import com.homektv.tv.net.QueueSnapshot
import com.homektv.tv.net.RoomHostStatus
import com.homektv.tv.net.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationStateTest {
    @Test
    fun startingRegistrationClearsStaleErrorAndMessage() {
        val error = KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline")
        val state = ControllerUiState(
            registration = RegistrationStatus.RETRY_REQUIRED,
            error = error,
            message = "上一次注册失败",
        )

        val started = RegistrationReducer.started(state)

        assertEquals(RegistrationStatus.REGISTERING, started.registration)
        assertNull(started.error)
        assertNull(started.message)
    }

    @Test
    fun registrationFailureIsVisibleAndBlocksIdentityWrites() {
        val error = KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline")

        val failed = RegistrationReducer.failed(ControllerUiState(), error)

        assertEquals(RegistrationStatus.RETRY_REQUIRED, failed.registration)
        assertEquals(error, failed.error)
        assertFalse(RegistrationReducer.canWrite(failed))
        assertFalse(RegistrationReducer.canPerform(failed, "order"))
        assertTrue(RegistrationReducer.canPerform(failed, "pause"))
    }

    @Test
    fun aSnapshotCannotEraseAnIdentityFailure() {
        val error = KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline")
        val failed = RegistrationReducer.failed(ControllerUiState(), error)

        val updated = ControllerStateReducer.withSnapshot(failed, QueueSnapshot())

        assertEquals(error, updated.error)
        assertEquals(RegistrationStatus.RETRY_REQUIRED, updated.registration)
    }

    @Test
    fun successfulRegistrationExposesServerIssuedIdAndUnlocksWrites() {
        val profile = UserProfile(id = 37L, nickname = "小明#2")

        val registered = RegistrationReducer.succeeded(ControllerUiState(), profile)

        assertEquals(profile, registered.currentUser)
        assertEquals(RegistrationStatus.REGISTERED, registered.registration)
        assertTrue(RegistrationReducer.canWrite(registered))
        assertNull(registered.error)
    }

    @Test
    fun successfulRegistrationRestoresHostPrivilegesForTheClaimingIdentity() {
        val profile = UserProfile(id = 37L, nickname = "小明#2")
        val state = ControllerUiState(roomHost = RoomHostStatus(claimed = true, hostUserId = profile.id))

        val registered = RegistrationReducer.succeeded(state, profile)

        assertTrue(registered.roomHost.isHost)
        assertEquals(profile, registered.currentUser)
        assertTrue(RegistrationReducer.canPerform(registered, "top"))
    }

    @Test
    fun malformedRegistrationResponseRemainsFailClosed() {
        val failed = RegistrationReducer.succeeded(
            ControllerUiState(),
            UserProfile(id = 0L, nickname = ""),
        )

        assertEquals(RegistrationStatus.RETRY_REQUIRED, failed.registration)
        assertFalse(RegistrationReducer.canWrite(failed))
        assertEquals("INVALID_USER", failed.error?.code)
    }

    @Test
    fun registrationRejectsPositiveIdWithBlankNickname() {
        val failed = RegistrationReducer.succeeded(
            ControllerUiState(),
            UserProfile(id = 37L, nickname = "   "),
        )

        assertEquals(RegistrationStatus.RETRY_REQUIRED, failed.registration)
        assertEquals("INVALID_USER", failed.error?.code)
        assertFalse(RegistrationReducer.canWrite(failed))
    }

    @Test
    fun registrationRejectsNonPositiveIdEvenWhenNicknameIsPresent() {
        val failed = RegistrationReducer.succeeded(
            ControllerUiState(),
            UserProfile(id = -1L, nickname = "小明"),
        )

        assertEquals(RegistrationStatus.RETRY_REQUIRED, failed.registration)
        assertEquals("INVALID_USER", failed.error?.code)
        assertFalse(RegistrationReducer.canWrite(failed))
    }

    @Test
    fun registeredStateWithoutPositiveIdentityCannotWrite() {
        assertFalse(RegistrationReducer.canWrite(ControllerUiState(
            registration = RegistrationStatus.REGISTERED,
            currentUser = null,
        )))
        assertFalse(RegistrationReducer.canWrite(ControllerUiState(
            registration = RegistrationStatus.REGISTERED,
            currentUser = UserProfile(id = 0L, nickname = "小明"),
        )))
    }

    @Test
    fun playbackControlsRemainAvailableWhileIdentityRetries() {
        val failed = RegistrationReducer.failed(
            ControllerUiState(),
            KtvApiError(KtvApiErrorKind.NETWORK, "NETWORK_ERROR", "offline"),
        )

        assertTrue(RegistrationReducer.canPerform(failed, "seek"))
        assertTrue(RegistrationReducer.canPerform(failed, "set_volume"))
        assertFalse(RegistrationReducer.canPerform(failed, "top"))
    }
}
