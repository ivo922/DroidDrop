package dev.ivo.droiddrop

import android.content.Context
import androidx.core.content.edit

/** Persists update check timestamps and dismissed versions across app launches. */
class UpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun lastCheckMillis(): Long? = if (prefs.contains(KEY_LAST_CHECK)) prefs.getLong(KEY_LAST_CHECK, 0L) else null

    fun recordCheck(atMillis: Long) = prefs.edit { putLong(KEY_LAST_CHECK, atMillis) }

    fun dismissedVersionCode(): Long = prefs.getLong(KEY_DISMISSED_CODE, 0L)

    fun dismiss(versionCode: Long) = prefs.edit { putLong(KEY_DISMISSED_CODE, versionCode) }

    private companion object {
        const val KEY_LAST_CHECK = "last_check_millis"
        const val KEY_DISMISSED_CODE = "dismissed_version_code"
    }
}
