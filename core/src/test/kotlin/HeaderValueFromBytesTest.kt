package org.nxtspec

import kotlin.test.Test
import kotlin.test.assertEquals

class HeaderValueFromBytesTest {

    @Test
    fun `valid UTF-8 stays text`() {
        assertEquals("zürich", headerValueFromBytes("zürich".toByteArray()))
    }

    @Test
    fun `bytes that are not UTF-8 are stored with the base64 prefix`() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())

        assertEquals("base64:wyj/", headerValueFromBytes(bytes))
    }
}
