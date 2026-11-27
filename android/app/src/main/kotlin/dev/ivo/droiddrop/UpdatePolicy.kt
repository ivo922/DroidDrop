package dev.ivo.droiddrop

/** Pure decision logic for Android update checks, banner dismissal, and download validation. */
object UpdatePolicy {
    fun isNewer(remoteVersionCode: Long, currentVersionCode: Long): Boolean =
        remoteVersionCode > currentVersionCode

    fun shouldAutoCheck(
        lastCheckMillis: Long?,
        nowMillis: Long,
        intervalMillis: Long = UpdateConfig.CHECK_INTERVAL_MILLIS,
    ): Boolean = lastCheckMillis == null || nowMillis - lastCheckMillis >= intervalMillis

    fun shouldShowBanner(remoteVersionCode: Long, dismissedVersionCode: Long, manual: Boolean): Boolean =
        manual || remoteVersionCode > dismissedVersionCode

    fun apkFileName(versionCode: Long): String = "DroidDrop-$versionCode.apk"

    fun isAllowedRedirect(url: String): Boolean = url.startsWith("https://")
}
