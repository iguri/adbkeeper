package com.adbkeeper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AdbLinkTest {

    @Test
    fun `ipv4 command built when address and port present`() {
        assertEquals("adb connect 192.168.1.8:5555", AdbLink.ipv4("192.168.1.8", "5555"))
    }

    @Test
    fun `ipv6 command wrapped in brackets`() {
        assertEquals("adb connect [240e:1234::1]:32937", AdbLink.ipv6("240e:1234::1", "32937"))
    }

    @Test
    fun `missing pieces produce null`() {
        assertNull(AdbLink.ipv4(null, "5555"))
        assertNull(AdbLink.ipv4("192.168.1.8", null))
        assertNull(AdbLink.ipv6(null, null))
    }
}
