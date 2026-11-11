package com.homektv.tv.ui.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BopomofoPinyinMapperTest {
    @Test
    fun mapsOnlyConfirmedInitialConsonantsToPinyinInitials() {
        assertEquals("B", BopomofoPinyinMapper.toPinyinInitial('ㄅ'))
        assertEquals("Z", BopomofoPinyinMapper.toPinyinInitial('ㄓ'))
        assertEquals("C", BopomofoPinyinMapper.toPinyinInitial('ㄔ'))
        assertEquals("S", BopomofoPinyinMapper.toPinyinInitial('ㄕ'))
        assertEquals("S", BopomofoPinyinMapper.toPinyinInitial('ㄙ'))
        assertNull(BopomofoPinyinMapper.toPinyinInitial('ㄧ'))
        assertNull(BopomofoPinyinMapper.toPinyinInitial('ˊ'))
        assertEquals(41, BopomofoPinyinMapper.keys.size)
    }
}
