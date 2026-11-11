package com.homektv.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueDrawerPipPositionPolicyTest {
    @Test
    fun centersTheLivePipInTheUncoveredAreaForRightAndLeftDrawers() {
        assertEquals(
            0.25f,
            QueueDrawerPipPositionPolicy.horizontalBias(1_000, 200, 400, drawerVisibleOnRight = true),
            0.001f,
        )
        assertEquals(
            0.75f,
            QueueDrawerPipPositionPolicy.horizontalBias(1_000, 200, 400, drawerVisibleOnRight = false),
            0.001f,
        )
    }

    @Test
    fun alignsPipToTheVisibleEdgeWhenDrawerLeavesLessRoomThanTheVideo() {
        assertEquals(
            0f,
            QueueDrawerPipPositionPolicy.horizontalBias(1_000, 200, 900, drawerVisibleOnRight = true),
            0f,
        )
    }

    @Test
    fun centersPipWhenItCannotMoveOrInputsAreNotMeasuredYet() {
        assertEquals(
            0.5f,
            QueueDrawerPipPositionPolicy.horizontalBias(0, 200, 400, drawerVisibleOnRight = true),
            0f,
        )
        assertEquals(
            0.5f,
            QueueDrawerPipPositionPolicy.horizontalBias(400, 400, 200, drawerVisibleOnRight = true),
            0f,
        )
    }
}
