package dev.ivo.droiddrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReceivedFileActionsTest {
    @Test
    fun openFolderStopsAtFirstWorkingTarget() {
        val tried = mutableListOf<ReceivedFileActions.FolderTarget>()
        val opened = ReceivedFileActions.openFolder { target ->
            tried += target
            target == ReceivedFileActions.FolderTarget.AOSP_DOCUMENTS_UI
        }
        assertTrue(opened)
        assertEquals(
            listOf(
                ReceivedFileActions.FolderTarget.GOOGLE_DOCUMENTS_UI,
                ReceivedFileActions.FolderTarget.AOSP_DOCUMENTS_UI,
            ),
            tried,
        )
    }

    @Test
    fun openFolderFallsBackToDownloadsAndReportsFailureWhenAllFail() {
        val tried = mutableListOf<ReceivedFileActions.FolderTarget>()
        val opened = ReceivedFileActions.openFolder { target ->
            tried += target
            false
        }
        assertFalse(opened)
        assertEquals(
            listOf(
                ReceivedFileActions.FolderTarget.GOOGLE_DOCUMENTS_UI,
                ReceivedFileActions.FolderTarget.AOSP_DOCUMENTS_UI,
                ReceivedFileActions.FolderTarget.DOWNLOADS,
            ),
            tried,
        )
    }

    @Test
    fun deleteRefreshAndNotificationRules() {
        assertTrue(ReceivedFileActions.shouldRefreshAfterDelete(ReceivedFiles.DeleteResult.DELETED))
        assertTrue(ReceivedFileActions.shouldRefreshAfterDelete(ReceivedFiles.DeleteResult.ALREADY_GONE))
        assertFalse(ReceivedFileActions.shouldRefreshAfterDelete(ReceivedFiles.DeleteResult.FAILED))

        assertTrue(ReceivedFileActions.shouldCancelNotificationAfterDelete(ReceivedFiles.DeleteResult.DELETED))
        assertTrue(ReceivedFileActions.shouldCancelNotificationAfterDelete(ReceivedFiles.DeleteResult.ALREADY_GONE))
        assertFalse(ReceivedFileActions.shouldCancelNotificationAfterDelete(ReceivedFiles.DeleteResult.FAILED))
    }

    @Test
    fun existenceCheckOnlyAbortsWhenConfirmedGone() {
        assertEquals(
            ReceivedFileActions.ExistenceDecision.LAUNCH,
            ReceivedFileActions.onExistenceCheck(ReceivedFiles.Existence.EXISTS),
        )
        assertEquals(
            ReceivedFileActions.ExistenceDecision.LAUNCH,
            ReceivedFileActions.onExistenceCheck(ReceivedFiles.Existence.ERROR),
        )
        assertEquals(
            ReceivedFileActions.ExistenceDecision.FILE_GONE,
            ReceivedFileActions.onExistenceCheck(ReceivedFiles.Existence.GONE),
        )
    }

    @Test
    fun reconcileSelectionKeepsSelectionUntilConfirmedAbsent() {
        val uri = "content://media/external_primary/downloads/42"
        assertNull(ReceivedFileActions.reconcileSelection(null, setOf(uri)))
        assertEquals(uri, ReceivedFileActions.reconcileSelection(uri, null))
        assertEquals(uri, ReceivedFileActions.reconcileSelection(uri, setOf(uri, "content://other")))
        assertNull(ReceivedFileActions.reconcileSelection(uri, setOf("content://other")))
        assertNull(ReceivedFileActions.reconcileSelection(uri, emptySet()))
    }
}
