package eu.kanade.tachiyomi.data.cache

import android.content.Context
import com.hippo.unifile.UniFile
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import nl.adaptivity.xmlutil.serialization.XML
import okio.buffer
import okio.sink
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.acbf.AcbfDocument
import java.io.File

/**
 * Disk cache for generated ACBF panel documents, keyed per chapter archive.
 *
 * One entry per chapter: a single ACBF XML string covering every page in that chapter, mirroring
 * ACBF's own one-document-per-book model (see [tachiyomi.core.metadata.acbf.AcbfDocument]) rather
 * than one entry per page, the way [ChapterCache] caches per-page data.
 *
 * This cache is purely additive/app-private: it never reads from or writes to the user's
 * original archive file. Invalidation is implicit — the cache key incorporates the archive
 * file's size and last-modified time, so a replaced/re-downloaded chapter simply misses and gets
 * a fresh entry; the stale one just ages out of the LRU.
 */
class AcbfCache(context: Context) {

    private val diskCache = DiskLruCache.open(
        File(context.cacheDir, "acbf_disk_cache"),
        PARAMETER_APP_VERSION,
        PARAMETER_VALUE_COUNT,
        PARAMETER_CACHE_SIZE,
    )

    /**
     * Returns the cached [AcbfDocument] for [mangaId]/[chapterId]'s archive [archiveFile], or
     * null if there is no entry for the archive's current size/last-modified signature.
     */
    fun get(mangaId: Long, chapterId: Long, archiveFile: UniFile, xml: XML): AcbfDocument? {
        val key = keyFor(mangaId, chapterId, archiveFile)
        return try {
            diskCache.get(key)?.use { snapshot ->
                xml.decodeFromString(AcbfDocument.serializer(), snapshot.getString(0))
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to read ACBF cache entry" }
            null
        }
    }

    /** Writes [document] to the cache for [mangaId]/[chapterId]'s archive [archiveFile]. */
    fun put(mangaId: Long, chapterId: Long, archiveFile: UniFile, document: AcbfDocument, xml: XML) {
        val key = keyFor(mangaId, chapterId, archiveFile)
        var editor: DiskLruCache.Editor? = null

        try {
            val encoded = xml.encodeToString(AcbfDocument.serializer(), document)

            editor = diskCache.edit(key) ?: return
            editor.newOutputStream(0).sink().buffer().use {
                it.write(encoded.toByteArray())
                it.flush()
            }

            diskCache.flush()
            editor.commit()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to write ACBF cache entry" }
        } finally {
            editor?.abortUnlessCommitted()
        }
    }

    private fun keyFor(mangaId: Long, chapterId: Long, archiveFile: UniFile): String {
        val signature = "${archiveFile.length()}_${archiveFile.lastModified()}"
        return DiskUtil.hashKeyForDisk("${mangaId}_${chapterId}_$signature")
    }
}

/** Application cache version. */
private const val PARAMETER_APP_VERSION = 1

/** The number of values per cache entry. Must be positive. */
private const val PARAMETER_VALUE_COUNT = 1

/** The maximum number of bytes this cache should use to store. */
private const val PARAMETER_CACHE_SIZE = 25L * 1024 * 1024
