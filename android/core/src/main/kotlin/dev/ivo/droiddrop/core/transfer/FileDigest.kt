package dev.ivo.droiddrop.core.transfer

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

object FileDigest {
    private const val PIECE_SIZE = 1_048_576

    /** Lowercase hex SHA-256 of the whole channel, using positional reads from 0 (the position is not moved). */
    fun sha256(channel: FileChannel): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(PIECE_SIZE)
        var position = 0L
        while (true) {
            buffer.clear()
            val read = channel.read(buffer, position)
            if (read < 0) break
            buffer.flip()
            digest.update(buffer)
            position += read
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    fun sha256(file: File): String = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { sha256(it) }
}
