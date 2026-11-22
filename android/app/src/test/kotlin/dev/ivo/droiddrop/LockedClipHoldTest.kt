package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LockedClipHoldTest {
    private var enabled = true
    private fun hold(locked: Boolean) = LockedClipHold(locked) { enabled }

    @Test
    fun appliesImmediatelyWhileUnlocked() {
        assertEquals("a", hold(locked = false).onClip("a", lockedNow = false))
    }

    @Test
    fun holdsOnlyLatestWhileLocked() {
        val hold = hold(locked = false)
        hold.onLocked()
        assertNull(hold.onClip("a", lockedNow = true))
        assertNull(hold.onClip("b", lockedNow = true))
        assertNull(hold.onClip("c", lockedNow = true))
        assertEquals("c", hold.onUnlocked())
        assertEquals("d", hold.onClip("d", lockedNow = false))
    }

    @Test
    fun startsLocked() {
        val hold = hold(locked = true)
        assertNull(hold.onClip("a", lockedNow = true))
        assertEquals("a", hold.onUnlocked())
    }

    @Test
    fun unlockWithNothingHeldWritesNothing() {
        assertNull(hold(locked = true).onUnlocked())
    }

    @Test
    fun relockKeepsHeldAndUnlockWritesOnce() {
        val hold = hold(locked = true)
        assertNull(hold.onClip("a", lockedNow = true))
        hold.onLocked()
        assertEquals("a", hold.onUnlocked())
        assertNull(hold.onUnlocked())
    }

    @Test
    fun screenOnWithoutKeyguardReleases() {
        val hold = hold(locked = true)
        assertNull(hold.onClip("a", lockedNow = true))
        assertNull(hold.onScreenOn(keyguardLocked = true))
        assertNull(hold.onClip("b", lockedNow = true)) // still held behind the lock screen
        assertEquals("b", hold.onScreenOn(keyguardLocked = false))
        assertEquals("c", hold.onClip("c", lockedNow = false))
    }

    @Test
    fun disabledAppliesWhileLocked() {
        enabled = false
        val hold = hold(locked = true)
        assertEquals("a", hold.onClip("a", lockedNow = true))
        assertNull(hold.onUnlocked())
    }

    @Test
    fun heldClipSurvivesTurningSettingOff() {
        val hold = hold(locked = true)
        assertNull(hold.onClip("a", lockedNow = true))
        enabled = false
        assertEquals("a", hold.onUnlocked())
    }

    @Test
    fun missedUnlockDoesNotStrandClips() {
        val hold = hold(locked = false)
        hold.onLocked()
        assertNull(hold.onClip("a", lockedNow = true))
        // The unlock broadcast never arrived, but the phone is unlocked: the newer clip goes through.
        assertEquals("b", hold.onClip("b", lockedNow = false))
        assertNull(hold.onUnlocked()) // "a" was superseded, nothing left to write
    }

    @Test
    fun missedLockStillHolds() {
        val hold = hold(locked = false)
        assertNull(hold.onClip("a", lockedNow = true)) // screen-off broadcast missed
        assertEquals("a", hold.onUnlocked())
    }
}
