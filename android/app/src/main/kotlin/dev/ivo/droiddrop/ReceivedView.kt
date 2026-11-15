package dev.ivo.droiddrop

import android.content.Context
import androidx.core.content.edit

/** How the View all screen lays out the received files. [stored] is the saved spelling, so renames keep the choice. */
enum class ReceivedView(val label: String, val stored: String) {
    TIMELINE("Timeline", "timeline"),
    GALLERY("Gallery", "gallery");

    companion object {
        /** Timeline until a choice is saved, and for anything unknown. */
        fun fromStored(value: String?): ReceivedView = entries.firstOrNull { it.stored == value } ?: TIMELINE
    }
}

/** Remembers the last "View as" choice. */
class ReceivedViewStore(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun load(): ReceivedView = ReceivedView.fromStored(prefs.getString(KEY, null))

    fun save(view: ReceivedView) = prefs.edit { putString(KEY, view.stored) }

    private companion object {
        const val KEY = "received_view"
    }
}
