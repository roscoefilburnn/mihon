package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.AcbfCache
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.archive.ArchiveEntry
import mihon.core.archive.ArchiveReader
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

private const val PER_PAGE_DETECTION_TIMEOUT_MS = 10_000L

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
 * This never reads from or mutates the user's original archive file — the ACBF document this
 * class produces for step 3 is written only to [AcbfCache], an app-private cache.
 */
class AcbfPanelResolver(
    private val acbfCache: AcbfCache = Injekt.get(),
    private val xml: XML = Injekt.get(),
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    private val classicalDetector: PanelDetector = AcbfEditorFrameDetector(),
    private val mlDetector: PanelDetector = TfliteChikaDetector(Injekt.get<Application>()),
    private val planner: PanelPlanner = ChikaPanelPlanner(),
) {

    /** Returns each image entry's planned panels, keyed by [ArchiveEntry.name]. */
    suspend fun resolve(
        reader: ArchiveReader,
        mangaId: Long,
        chapterId: Long,
        archiveFile: UniFile,
        readingDirection: ReadingDirection,
        imageEntries: List<ArchiveEntry>,
    ): Map<String, List<PanelRect>> {
        if (!readerPreferences.panelDetectionEnabled.get()) return emptyMap()

        findEmbeddedAcbf(reader, imageEntries)?.let { return it.toPanelsByEntry() }

        acbfCache.get(mangaId, chapterId, archiveFile, xml)?.let { return it.toPanelsByEntry() }

        return generateAndCache(reader, mangaId, chapterId, archiveFile, readingDirection, imageEntries)
    }

    private suspend fun generateAndCache(
        reader: ArchiveReader,
        mangaId: Long,
        chapterId: Long,
        archiveFile: UniFile,
        readingDirection: ReadingDirection,
        imageEntries: List<ArchiveEntry>,
    ): Map<String, List<PanelRect>> {
        val result = LinkedHashMap<String, List<PanelRect>>()
        val pages = ArrayList<AcbfDocument.Page>(imageEntries.size)

        for (entry in imageEntries) {
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

        acbfCache.put(mangaId, chapterId, archiveFile, AcbfDocument(AcbfDocument.Body(pages)), xml)
        return result
    }

    private suspend fun detectPanels(
        reader: ArchiveReader,
        entryName: String,
        readingDirection: ReadingDirection,
    ): List<PanelRect> {
        val bitmap = reader.getInputStream(entryName)?.use { BitmapFactory.decodeStream(it) }
            ?: return emptyList()

        try {
            val boxes = when (val classicalResult = classicalDetector.detect(bitmap)) {
                is DetectionResult.Confident -> classicalResult.boxes
                is DetectionResult.Inconclusive -> {
                    when (val mlResult = mlDetector.detect(bitmap)) {
                        is DetectionResult.Confident -> mlResult.boxes
                        is DetectionResult.Inconclusive -> emptyList()
                    }
                }
            }

            if (boxes.isEmpty()) return emptyList()
            return planner.plan(boxes, bitmap.width, bitmap.height, readingDirection)
        } finally {
            bitmap.recycle()
        }
    }

    private fun findEmbeddedAcbf(reader: ArchiveReader, imageEntries: List<ArchiveEntry>): AcbfDocument? {
        // .acbf metadata files sit alongside page images in the archive, not inside imageEntries
        // (which is pre-filtered to actual raster images), so entries are re-scanned here.
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
