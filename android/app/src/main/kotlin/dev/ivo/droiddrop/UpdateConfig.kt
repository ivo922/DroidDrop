package dev.ivo.droiddrop

/** Release feed endpoints and Ed25519 verification key for in-app Android updates. */
object UpdateConfig {
    const val MANIFEST_URL = "https://github.com/ivo922/DroidDrop/releases/latest/download/update.json"
    const val SIGNATURE_URL = "https://github.com/ivo922/DroidDrop/releases/latest/download/update.json.sig"
    const val ALLOWED_DOWNLOAD_PREFIX = "https://github.com/ivo922/DroidDrop/releases/download/"

    /** Base64 of the 32-byte raw Ed25519 public key matching `DROIDDROP_UPDATE_KEY_BASE64` in CI. */
    const val PUBLIC_KEY_BASE64 = "BEtFvQOnEd+q/MbtBU8cm5bs27MOALwEy8UwsfM7z+4="
    const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
}
