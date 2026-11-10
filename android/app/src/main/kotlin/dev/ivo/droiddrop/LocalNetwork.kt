package dev.ivo.droiddrop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object LocalNetwork {
    /** Android 17 blocks LAN sockets until this runtime permission is granted. Older releases allow them with INTERNET. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}
