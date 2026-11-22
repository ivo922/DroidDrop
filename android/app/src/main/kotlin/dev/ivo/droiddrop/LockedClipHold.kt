package dev.ivo.droiddrop

import android.content.Context
import androidx.core.content.edit

/**
 * Holds clips from the Mac while the phone is locked, so only the latest one reaches the clipboard on unlock
 * instead of every copy. Locked runs from screen off until the user unlocks. Main thread only; no Android types.
 *
 * Each function returns the text to write to the clipboard now, or null.
 */
class LockedClipHold(private var locked: Boolean, private val enabled: () -> Boolean) {
    private var held: String? = null

    /**
     * [lockedNow] is the phone's actual state when the clip is applied. It wins over the broadcasts, so a missed
     * unlock cannot strand clips (and a held one is superseded by this newer clip anyway).
     */
    fun onClip(text: String, lockedNow: Boolean): String? {
        locked = lockedNow
        if (!locked) held = null
        if (!locked || !enabled()) return text
        held = text
        return null
    }

    /** `ACTION_SCREEN_OFF`. */
    fun onLocked() {
        locked = true
    }

    /** `ACTION_USER_PRESENT`. A clip held before the setting was turned off is still written. */
    fun onUnlocked(): String? {
        locked = false
        return held.also { held = null }
    }

    /** `ACTION_SCREEN_ON`: with no keyguard (no lock screen, or the screen woke after a call) it is an unlock. */
    fun onScreenOn(keyguardLocked: Boolean): String? = if (keyguardLocked) null else onUnlocked()
}

/** The "Hold Mac clips while locked" switch, on until turned off. */
class ClipHoldSetting(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Boolean = prefs.getBoolean(KEY, true)

    fun save(enabled: Boolean) = prefs.edit { putBoolean(KEY, enabled) }

    private companion object {
        const val KEY = "hold_clips_while_locked"
    }
}
