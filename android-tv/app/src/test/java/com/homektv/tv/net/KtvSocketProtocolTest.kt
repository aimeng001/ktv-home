package com.homektv.tv.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvSocketProtocolTest {
    @Test
    fun playErrorMessageRemainsValidJsonWhenTextContainsControlCharacters() {
        val raw = "磁盘\\异常\"\n下一行\t"

        val root = Json.parseToJsonElement(
            buildPlayErrorMessage(raw, fileId = 11L, queueId = 7L, generation = 3L)
        ).jsonObject

        assertEquals("play_error", root["type"]?.jsonPrimitive?.content)
        val payload = root["payload"]!!.jsonObject
        assertEquals(raw, payload["message"]?.jsonPrimitive?.content)
        assertEquals("11", payload["file_id"]?.jsonPrimitive?.content)
        assertEquals("7", payload["queue_id"]?.jsonPrimitive?.content)
        assertEquals("3", payload["generation"]?.jsonPrimitive?.content)
        assertTrue(payload["message"]!!.jsonPrimitive.isString)
    }

    @Test
    fun finishedMessageCarriesQueueIdentityAndCurrentLeaseGeneration() {
        val root = Json.parseToJsonElement(buildFinishedMessage(42L, 7L)).jsonObject
        val payload = root["payload"]!!.jsonObject

        assertEquals("finished", root["type"]?.jsonPrimitive?.content)
        assertEquals("42", payload["queue_id"]?.jsonPrimitive?.content)
        assertEquals("7", payload["generation"]?.jsonPrimitive?.content)
    }
}
