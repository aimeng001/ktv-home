package com.homektv.tv.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistRegionResponsePolicyTest {
    @Test
    fun rejectsMissingOrIgnoredRegionEchoFromAnOlderServer() {
        assertFalse(ArtistRegionResponsePolicy.accepts("港台", null, false))
        assertFalse(ArtistRegionResponsePolicy.accepts("港台", "", true))
        assertFalse(ArtistRegionResponsePolicy.accepts("港台", "内地", true))
    }

    @Test
    fun acceptsOnlyAnExplicitlyAppliedRegionWithAvailableReferenceData() {
        assertTrue(ArtistRegionResponsePolicy.accepts("港台", "港台", true))
        assertFalse(ArtistRegionResponsePolicy.accepts("港台", "港台", false))
        assertTrue(ArtistRegionResponsePolicy.accepts("", null, false))
    }

    @Test
    fun givesDistinctTruthfulStateForMissingDataAndOldServer() {
        assertEquals("歌手地区参考资料尚未载入", ArtistRegionResponsePolicy.unavailableMessage(false))
        assertEquals(
            "当前点歌服务未应用地区筛选，已停止显示未过滤结果",
            ArtistRegionResponsePolicy.unavailableMessage(true),
        )
    }
}
