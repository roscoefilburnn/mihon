package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.AcbfCache
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.archive.ArchiveEntry
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import mihon.core.panels.ChikaPanelPlanner
import mihon.core.panels.DetectionResult
import mihon.core.panels.PanelBox
import mihon.core.panels.PanelDetector
import mihon.core.panels.PanelPlanner
import mihon.core.panels.PanelRect
import mihon.core.panels.ReadingDirection
import mihon.core.panels.TfliteChikaDetector
import mihon.core.panels.acbfeditor.AcbfEditorFrameDetector
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.acbf.ACBF_FILE_EXTENSION
import tachiyomi.core.metadata.acbf.AcbfDocument
import tachiyomi.core.metadata.acbf.AcbfRect
import tachiyomi.core.metadata.acbf.bounds
import tachiyomi.core.metadata.acbf.toAcbfPoints
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

private const val PER_PAGE_DETECTION_TIMEOUT_MS = 10_000L

/**
 * Longest edge, in pixels, a page is decoded to before detection. Panel geometry only needs to be
 * accurate to a few pixels at display scale, so running edge detection over a full 2000x3000 scan
 * is wasted work: it costs ~24MB per decoded page plus an equally large OpenCV copy, for boxes
 * that land in the same place. Detection output is normalized, so results are scaled back up
 * against the page's true dimensions and remain exact in the original coordinate space.
 */
private const val MAX_DETECTION_EDGE_PX = 1600

/**
 * Resolves panel data for every page of an archive chapter, so both local-library and
 * downloaded-online-source CBZ/CBR chapters get a uniform panel data source regardless of how
 * that data was produced. Precedence, resolved once per chapter:
 *
 * 1. An `.acbf` file already embedded in the archive (real, human-authored data) always wins.
 * 2. A previously-generated encoding cached for this exact archive (see [AcbfCache]).
 * 3. Otherwise, per page: the classical [AcbfEditorFrameDetector] first (edge/contour-based,
 *    ported from ACBF Editor's own "Detect Frames"), falling back to the ML [TfliteChikaDetector]
 *    only when the classical pass is inconclusive. The winning boxes are planned into reading
 *    order by [planner] and the whole chapter's result is cached.
 *
 * Encoding normally happens at download time (see `Downloader`), so by the time a chapter is
 * opened its panels are already cached. Opening a chapter whose encode is still running does not
 * start a second one: both callers coalesce onto the same in-flight job via [encodeOnce], and the
 * reader simply awaits it — which is what makes the reader wait for encoding to finish rather
 * than racing it.
 *
 * This never reads from or mutates the user's original archive file — the ACBF document this
 * class produces for step 3 is written only to [AcbfCache], an app-private cache.
 */
class AcbfPanelResolver(
    private val context: Application = Injekt.get(),
    private val acbfCache: AcbfCache = Injekt.get(),
    private val xml: XML = Injekt.get(),
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    private val classicalDetector: PanelDetector = AcbfEditorFrameDetector(),
    private val mlDetector: PanelDetector = TfliteChikaDetector(Injekt.get<Application>()),
    private val planner: PanelPlanner = ChikaPanelPlanner(),
) {

    /**
     * Encode jobs are owned by this scope rather than by whichever caller happened to start them,
     * so a reader that backs out mid-encode doesn't cancel a download-triggered job that another
     * caller may still be awaiting.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Map<String, List<PanelRect>>>>()

    /**
     * Encodes [archiveFile] now, so the reader doesn't have to wait for it later. Called after a
     * chapter finishes downloading; a no-op when Guided Panel is off.
     */
    suspend fun encodeAfterDownload(
        mangaId: Long,
        chapterId: Long,
        archiveFile: UniFile,
        readingDirection: ReadingDirection,
    ) {
        resolve(mangaId, chapterId, archiveFile, readingDirection)
    }

    /**
     * Returns each image entry's planned panels, keyed by [ArchiveEntry.name]. Suspends until any
     * in-flight encode for this archive completes.
     */
    suspend fun resolve(
        mangaId: Long,
        chapterId: Long,
        archiveFile: UniFile,
        readingDirection: ReadingDirection,
    ): Map<String, List<PanelRect>> {
        if (!readerPreferences.guidedPanel.get()) return emptyMap()

        val key = acbfCache.keyFor(mangaId, chapterId, archiveFile)
        return try {
            encodeOnce(key) {
                // Re-checked inside the job: a cache hit written by a job that finished while this
                // one was queued behind the mutex makes the whole encode unnecessary.
                acbfCache.get(mangaId, chapterId, archiveFile, xml)?.let { return@encodeOnce it.toPanelsByEntry() }

                archiveFile.archiveReader(context).use { reader ->
                    findEmbeddedAcbf(reader)?.let { return@use it.toPanelsByEntry() }
                    generateAndCache(reader, mangaId, chapterId, archiveFile, readingDirection)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Panels are an enhancement; never let failing to produce them stop a chapter opening.
            logcat(LogPriority.WARN, e) { "Panel encoding failed for chapter $chapterId" }
            emptyMap()
        }
    }

    /**
     * Runs [block] for [key], or joins the already-running job for it. The job outlives the caller
     * that started it (see [scope]), so a download-time encode and a reader-time resolve of the
     * same archive always share one pass over the pages.
     */
    private suspend fun encodeOnce(
        key: String,
        block: suspend () -> Map<String, List<PanelRect>>,
    ): Map<String, List<PanelRect>> {
        val deferred = inFlightMutex.withLock {
            inFlight[key]?.takeIf { it.isActive } ?: scope.async { block() }.also { started ->
                inFlight[key] = started
                started.invokeOnCompletion {
                    scope.launch {
                        inFlightMutex.withLock { if (inFlight[key] === started) inFlight.remove(key) }
                    }
                }
            }
        }
        return deferred.await()
    }

    private suspend fun generateAndCache(
        reader: ArchiveReader,
        mangaId: Long,
        chapterId: Long,
        archiveFile: UniFile,
        readingDirection: ReadingDirection,
    ): Map<String, List<PanelRect>> {
        val imageEntries = reader.imageEntries()
        val result = LinkedHashMap<String, List<PanelRect>>()
        val pages = ArrayList<AcbfDocument.Page>(imageEntries.size)

        for (entry in imageEntries) {
            coroutineContext.ensureActive()
            val panels = try {
                withTimeoutOrNull(PER_PAGE_DETECTION_TIMEOUT_MS) {
                    detectPanels(reader, entry.name, readingDirection)
                } ?: emptyList()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Panel detection failed for ${entry.name}" }
                emptyList()
            }

            result[entry.name] = panels
            pages.add(
                AcbfDocument.Page(
                    image = AcbfDocument.Image(href = entry.name),
                    frame = panels.map { AcbfDocument.Frame(points = it.rect.toAcbfRect().toAcbfPoints()) },
                ),
            )
        }

        // Only cache a document that actually found something. An all-empty result means every page
        // failed or was inconclusive — often a transient, fixable cause (OpenCV/TFLite failing to
        // initialize, memory pressure). Caching it would be indistinguishable from a real "this
        // chapter has no panels" answer on the next open, permanently pinning the failure for an
        // archive whose size/mtime — and therefore cache key — never changes again.
        if (result.values.any { it.isNotEmpty() }) {
            acbfCache.put(mangaId, chapterId, archiveFile, AcbfDocument(AcbfDocument.Body(pages)), xml)
        } else {
            logcat(LogPriority.WARN) { "No panels detected on any page of $chapterId; not caching, will retry" }
        }
        return result
    }

    private suspend fun detectPanels(
        reader: ArchiveReader,
        entryName: String,
        readingDirection: ReadingDirection,
    ): List<PanelRect> {
        val pageSize = readPageSize(reader, entryName) ?: return emptyList()
        val (pageWidth, pageHeight) = pageSize
        val bitmap = decodeDownsampled(reader, entryName, pageWidth, pageHeight) ?: return emptyList()

        try {
            coroutineContext.ensureActive()
            val boxes = when (val classicalResult = classicalDetector.detect(bitmap)) {
                is DetectionResult.Confident -> classicalResult.boxes
                is DetectionResult.Inconclusive -> {
                    coroutineContext.ensureActive()
                    when (val mlResult = mlDetector.detect(bitmap)) {
                        is DetectionResult.Confident -> mlResult.boxes
                        is DetectionResult.Inconclusive -> emptyList()
                    }
                }
            }

            if (boxes.isEmpty()) return emptyList()
            // Detector output is normalized, so planning against the page's true dimensions (not the
            // downsampled bitmap's) keeps panel rects in original-image pixel space, which is what
            // ACBF frames and the reader's zoom both expect.
            return planner.plan(boxes, pageWidth, pageHeight, readingDirection)
        } finally {
            bitmap.recycle()
        }
    }

    /** Reads [entryName]'s pixel dimensions without decoding it. */
    private fun readPageSize(reader: ArchiveReader, entryName: String): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        reader.getInputStream(entryName)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    /** Decodes [entryName] subsampled so its longest edge is at most [MAX_DETECTION_EDGE_PX]. */
    private fun decodeDownsampled(
        reader: ArchiveReader,
        entryName: String,
        pageWidth: Int,
        pageHeight: Int,
    ): Bitmap? {
        // BitmapFactory rounds inSampleSize down to a power of two, so step through powers directly.
        var sampleSize = 1
        while (maxOf(pageWidth, pageHeight) / sampleSize > MAX_DETECTION_EDGE_PX) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return reader.getInputStream(entryName)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun findEmbeddedAcbf(reader: ArchiveReader): AcbfDocument? {
        return reader.useEntries { entries ->
            val acbfEntryName = entries.firstOrNull {
                it.isFile && it.name.substringAfterLast('.', "").equals(ACBF_FILE_EXTENSION, ignoreCase = true)
            }?.name ?: return@useEntries null

            try {
                reader.getInputStream(acbfEntryName)?.use { stream ->
                    AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
                        xml.decodeFromReader(AcbfDocument.serializer(), it)
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to parse embedded ACBF file $acbfEntryName" }
                null
            }
        }
    }

    private fun AcbfDocument.toPanelsByEntry(): Map<String, List<PanelRect>> {
        return body.page.associate { page ->
            page.image.href to page.frame.mapNotNull { it.bounds() }.map { PanelRect(it.toPanelBox()) }
        }
    }

    private fun PanelBox.toAcbfRect() = AcbfRect(left, top, right, bottom)

    private fun AcbfRect.toPanelBox() = PanelBox(left, top, right, bottom)
}
