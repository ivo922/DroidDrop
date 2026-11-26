package dev.ivo.droiddrop

/** Pure decision logic for received-file quick actions, separated from Activity glue so it can be unit tested. */
object ReceivedFileActions {
    /** Targets tried in order when opening Download/DroidDrop/ in a file manager. */
    enum class FolderTarget { GOOGLE_DOCUMENTS_UI, AOSP_DOCUMENTS_UI, DOWNLOADS }

    /** What to do after checking whether a received file still exists in MediaStore. */
    enum class ExistenceDecision { LAUNCH, FILE_GONE }

    /**
     * Tries each folder target in order until one opens. Targeting the system DocumentsUI packages first avoids
     * third-party file managers that claim directory intents but cannot open externalstorage document URIs.
     */
    fun openFolder(tryOpen: (FolderTarget) -> Boolean): Boolean =
        FolderTarget.entries.any(tryOpen)

    /** True when the received list should reload after a delete attempt (deleted now or already absent). */
    fun shouldRefreshAfterDelete(result: ReceivedFiles.DeleteResult): Boolean =
        result != ReceivedFiles.DeleteResult.FAILED

    /** True when any single-file received notification pointing at the file is now stale and should be cancelled. */
    fun shouldCancelNotificationAfterDelete(result: ReceivedFiles.DeleteResult): Boolean =
        result != ReceivedFiles.DeleteResult.FAILED

    /**
     * Only abort when MediaStore confirmed the row is gone. On a transient query error, proceed with the intent launch
     * so startActivity's own exception handling decides.
     */
    fun onExistenceCheck(existence: ReceivedFiles.Existence): ExistenceDecision = when (existence) {
        ReceivedFiles.Existence.GONE -> ExistenceDecision.FILE_GONE
        ReceivedFiles.Existence.EXISTS, ReceivedFiles.Existence.ERROR -> ExistenceDecision.LAUNCH
    }

    /**
     * Keeps an open sheet/dialog URI while the list has not loaded (or failed to load) or still contains that URI.
     * Clears it only once a successful snapshot confirms the file is absent.
     */
    fun reconcileSelection(selectedUri: String?, loadedUris: Set<String>?): String? = when {
        selectedUri == null -> null
        loadedUris == null -> selectedUri
        selectedUri in loadedUris -> selectedUri
        else -> null
    }
}
