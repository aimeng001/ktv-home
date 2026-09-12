package com.homektv.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Utf8BudgetTest {
    @Test
    fun budget_is_measured_in_utf8_bytes_not_utf16_code_units() {
        assertTrue(Utf8Budget.atMost("a".repeat(16), 16))
        assertFalse(Utf8Budget.atMost("中".repeat(6), 17))
        assertTrue(Utf8Budget.atMost("😀".repeat(4), 16))
        assertFalse(Utf8Budget.atMost("😀".repeat(5), 16))
    }

    @Test
    fun byte_count_can_be_bounded_without_allocating_an_encoded_copy() {
        assertTrue(Utf8Budget.countAtMost("中", 3) == 3)
        assertTrue(Utf8Budget.countAtMost("中", 2) == null)
    }
}
