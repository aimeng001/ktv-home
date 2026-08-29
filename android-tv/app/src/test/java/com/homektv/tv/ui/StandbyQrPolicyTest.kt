package com.homektv.tv.ui

import com.homektv.tv.net.StandbyContent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyQrPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun miniQrSettingControlsBothImageAndLabel() {
        assertTrue(StandbyQrPolicy.visibility(true).imageVisible)
        assertTrue(StandbyQrPolicy.visibility(true).labelVisible)
        assertFalse(StandbyQrPolicy.visibility(false).imageVisible)
        assertFalse(StandbyQrPolicy.visibility(false).labelVisible)
    }

    @Test
    fun standbyContentDecodesTheServerMiniQrFlag() {
        val content = json.decodeFromString<StandbyContent>("""{"miniQr":false}""")

        assertFalse(content.miniQr)
    }
}
