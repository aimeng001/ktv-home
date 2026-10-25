package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KtvApiResultTest {
    @Test
    fun mapValueTransformsOnlySuccessfulPayloads() {
        var transformCalls = 0

        val result = KtvApiResult.Success("12").mapValue { value ->
            transformCalls++
            value.toInt()
        }

        assertEquals(KtvApiResult.Success(12), result)
        assertEquals(1, transformCalls)
    }

    @Test
    fun mapValuePreservesFailuresWithoutRunningTheTransform() {
        val failure = KtvApiResult.Failure(
            KtvApiError(KtvApiErrorKind.HTTP, "HTTP_503", "服务端暂时不可用", status = 503),
        )
        var transformCalls = 0
        val source: KtvApiResult<String> = failure

        val result = source.mapValue {
            transformCalls++
            it.length
        }

        assertSame(failure, result)
        assertEquals(0, transformCalls)
        assertTrue(result is KtvApiResult.Failure)
    }
}
