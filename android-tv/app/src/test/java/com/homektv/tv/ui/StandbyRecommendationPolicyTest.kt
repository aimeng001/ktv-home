package com.homektv.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyRecommendationPolicyTest {
    @Test
    fun emptyServerRecommendationsHideTheWholeSection() {
        assertFalse(StandbyRecommendationPolicy.isSectionVisible(0))
        assertFalse(StandbyRecommendationPolicy.isSectionVisible(-1))
        assertTrue(StandbyRecommendationPolicy.isSectionVisible(1))
        assertTrue(StandbyRecommendationPolicy.isSectionVisible(4))
    }
}
