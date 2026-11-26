package com.adbkeeper.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun `newer version detected`() {
        assertTrue(Updater.isNewer("2.2", "2.1"))
        assertTrue(Updater.isNewer("v2.2", "2.1"))
        assertTrue(Updater.isNewer("2.1.1", "2.1"))
        assertTrue(Updater.isNewer("3.0", "2.9.9"))
    }

    @Test
    fun `same or older version rejected`() {
        assertFalse(Updater.isNewer("2.1", "2.1"))
        assertFalse(Updater.isNewer("2.0", "2.1"))
        assertFalse(Updater.isNewer("2.1", "2.1.1"))
        assertFalse(Updater.isNewer("", "2.1"))
    }

    @Test
    fun `dev suffix ignored in comparison`() {
        assertTrue(Updater.isNewer("2.2-dev", "2.1-dev"))
        assertFalse(Updater.isNewer("2.1-dev", "2.1"))
    }
}
