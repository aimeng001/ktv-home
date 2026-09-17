package com.homektv.tv.ui

import com.homektv.tv.net.DiscoveredServer
import com.homektv.tv.net.SavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupVerificationPolicyTest {
    private val instanceA = "550e8400-e29b-41d4-a716-446655440000"
    private val instanceB = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

    @Test
    fun confirmedDiscoveryCommitsTheIdentityReturnedByTheServer() {
        val confirmed = SetupVerificationPolicy.confirm(
            requested = SavedServer("nas-a:8080", "旧名称"),
            discovered = DiscoveredServer("nas-a:8080", "客厅", instanceA.uppercase()),
        )

        assertEquals(SavedServer("nas-a:8080", "客厅", instanceA), confirmed)
    }

    @Test
    fun expectedIdentityMismatchIsRejectedBeforeCommit() {
        assertNull(
            SetupVerificationPolicy.confirm(
                requested = SavedServer("nas-a:8080", "客厅", instanceA),
                discovered = DiscoveredServer("nas-a:8080", "替代服务", instanceB),
            ),
        )
    }

    @Test
    fun hostMismatchIsRejectedEvenWhenIdentityMatches() {
        assertNull(
            SetupVerificationPolicy.confirm(
                requested = SavedServer("nas-a:8080", "客厅", instanceA),
                discovered = DiscoveredServer("nas-b:8080", "其他", instanceA),
            ),
        )
    }

    @Test
    fun malformedAdvertisedIdentityIsRejected() {
        assertNull(
            SetupVerificationPolicy.confirm(
                requested = SavedServer("nas-a:8080", "客厅"),
                discovered = DiscoveredServer("nas-a:8080", "服务", "not-a-uuid"),
            ),
        )
    }
}
