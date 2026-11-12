package dev.ivo.droiddrop

import dev.ivo.droiddrop.core.transfer.FileSender
import dev.ivo.droiddrop.core.transfer.TransferOutcome
import dev.ivo.droiddrop.core.transfer.TransferStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide transfer state shared by the service, activities and notifications. */
object Transfers {
    val sender = FileSender()
    val incoming = MutableStateFlow<TransferStatus?>(null)
    val received = MutableSharedFlow<TransferOutcome>(extraBufferCapacity = 64)
}
