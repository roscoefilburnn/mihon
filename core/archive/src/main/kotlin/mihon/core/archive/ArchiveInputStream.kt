package mihon.core.archive

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import mihon.core.archive.ArchiveEntry as MihonArchiveEntry

internal class ArchiveInputStream(buffer: Long, size: Long) : InputStream() {
    private val lock = Any()

    @Volatile
    private var isClosed = false

    private val archive = Archive.readNew()

    init {
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenMemoryUnsafe(archive, buffer, size)
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    override fun read(): Int {
        oneByteBuffer.clear()
        return if (readInto(oneByteBuffer) > 0) oneByteBuffer.get(0).toUByte().toInt() else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        return readInto(ByteBuffer.wrap(b, off, len)).takeIf { it > 0 } ?: -1
    }

    /**
     * Reads into [buffer] between its current position and limit, returning the number of bytes
     * read, or 0 at end of archive entry.
     *
     * Deliberately does not call [ByteBuffer.clear]: on the buffer from `ByteBuffer.wrap(b, off,
     * len)` that would reset position to 0 and limit to the *array's* capacity, discarding both
     * `off` and `len`. libarchive would then write at the wrong offset and up to the whole array,
     * and this method would report more bytes read than the caller asked for. Callers that size a
     * destination to `len` -- notably Skia's JavaInputStreamAdaptor, which copies the returned
     * count out with GetByteArrayRegion -- overrun that destination and crash the process.
     */
    private fun readInto(buffer: ByteBuffer): Int {
        val start = buffer.position()
        Archive.readData(archive, buffer)
        return buffer.position() - start
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }

        Archive.readFree(archive)
    }

    fun getNextEntry(): MihonArchiveEntry? {
        return Archive.readNextHeader(archive).takeUnless { it == 0L }?.let { entry ->
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString() ?: return null
            val isFile = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
            MihonArchiveEntry(name, isFile)
        }
    }
}
