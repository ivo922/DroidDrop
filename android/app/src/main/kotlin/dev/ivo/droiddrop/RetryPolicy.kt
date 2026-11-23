package dev.ivo.droiddrop

/** Whether failed files stay open for a Retry action. */
object RetryPolicy {
    /** Only when the user can see and tap the summary, and there is still a Mac to send to. */
    fun keep(notificationsEnabled: Boolean, channelEnabled: Boolean, paired: Boolean): Boolean =
        notificationsEnabled && channelEnabled && paired
}
