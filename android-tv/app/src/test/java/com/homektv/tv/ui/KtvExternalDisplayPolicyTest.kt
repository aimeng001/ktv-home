package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KtvExternalDisplayPolicyTest {

    @Test
    fun selectsOnlyPresentationDisplayOutsideTheMainScreen() {
        val displays = listOf(
            KtvExternalDisplayPolicy.DisplayCandidate(0, isPresentation = false, isOff = false),
            KtvExternalDisplayPolicy.DisplayCandidate(4, isPresentation = false, isOff = false),
            KtvExternalDisplayPolicy.DisplayCandidate(7, isPresentation = true, isOff = true),
            KtvExternalDisplayPolicy.DisplayCandidate(9, isPresentation = true, isOff = false),
        )

        assertEquals(9, KtvExternalDisplayPolicy.choose(displays)?.id)
    }

    @Test
    fun absentOrOfflinePresentationFallsBackToMainScreen() {
        assertNull(KtvExternalDisplayPolicy.choose(emptyList()))
        assertNull(KtvExternalDisplayPolicy.choose(
            listOf(KtvExternalDisplayPolicy.DisplayCandidate(3, isPresentation = true, isOff = true)),
        ))
    }
}
