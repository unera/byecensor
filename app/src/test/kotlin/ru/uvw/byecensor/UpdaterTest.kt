package ru.uvw.byecensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.uvw.byecensor.logic.Updater

/**
 * Version comparison decides whether a phone ever sees an update, and it fails quietly: a wrong
 * answer means "обновлений нет" forever. The cases below are the ones a plain string comparison
 * gets wrong.
 */
class UpdaterTest {
    @Test
    fun tenIsNewerThanNine() {
        assertTrue(Updater.newer("0.10.0", "0.9.28"))
    }

    @Test
    fun patchIsNewerThanMinor() {
        assertTrue(Updater.newer("0.9.30", "0.9.29"))
        assertFalse(Updater.newer("0.9.9", "0.9.28"))
    }

    @Test
    fun sameVersionIsNotAnUpdate() {
        assertFalse(Updater.newer("0.9.29", "0.9.29"))
        assertFalse(Updater.newer("0.9", "0.9.0"))
        assertFalse(Updater.newer("v0.9.29", "0.9.29"))
    }

    @Test
    fun tagPrefixDoesNotMatter() {
        assertTrue(Updater.newer("v0.9.30", "0.9.29"))
    }

    @Test
    fun olderIsNotNewer() {
        assertFalse(Updater.newer("0.8.9", "0.9.0"))
    }
}
