package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.ArchiveEntry
import mihon.core.archive.ArchiveReader
import mihon.core.panels.ReadingDirection
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Identifies the manga/chapter/archive an [ArchivePageLoader] is loading, so [AcbfPanelResolver]
 * can resolve (and cache) panel data per chapter.
 */
internal data class PanelResolutionContext(
    val mangaId: Long,
    val chapterId: Long,
    val archiveFile: UniFile,
    val readingDirection: ReadingDirection,
)

/**
 * The archive's page images, in reading order. Shared by [ArchivePageLoader] and
 * [AcbfPanelResolver] so the panel data a chapter is encoded with is keyed to exactly the entries
 * the reader will page through.
 */
internal fun ArchiveReader.imageEntries(): List<ArchiveEntry> = useEntries { entries ->
    entries
        .filter { it.isFile && ImageUtil.isImage(it.name) { getInputStream(it.name)!! } }
        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
        .toList()
}

/**
 * The direction panels are read in within a row. Only affects panel *order*, not detection, so a
 * manga left on the global default (rather than an explicit per-manga override) falling back to
 * left-to-right just means row order, not whether panels are found.
 */
internal fun Manga.panelReadingDirection(): ReadingDirection {
    return if ((viewerFlags.toInt() and ReadingMode.MASK) == ReadingMode.RIGHT_TO_LEFT.flagValue) {
        ReadingDirection.RIGHT_TO_LEFT
    } else {
        ReadingDirection.LEFT_TO_RIGHT
    }
}

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(
    private val reader: ArchiveReader,
    private val panelContext: PanelResolutionContext,
    private val acbfPanelResolver: AcbfPanelResolver = Injekt.get(),
) : PageLoader() {
    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val imageEntries = reader.imageEntries()

        // Suspends until this chapter's panel encoding is done — normally already finished at
        // download time, so this returns from cache immediately.
        val panelsByEntry = acbfPanelResolver.resolve(
            mangaId = panelContext.mangaId,
            chapterId = panelContext.chapterId,
            archiveFile = panelContext.archiveFile,
            readingDirection = panelContext.readingDirection,
        )

        return imageEntries.mapIndexed { i, entry ->
            ReaderPage(i).apply {
                stream = { reader.getInputStream(entry.name)!! }
                status = Page.State.Ready
                panels = panelsByEntry[entry.name]
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }
}
