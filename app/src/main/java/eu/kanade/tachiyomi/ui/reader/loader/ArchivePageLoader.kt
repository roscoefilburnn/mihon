package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.ArchiveReader
import mihon.core.panels.ReadingDirection
import tachiyomi.core.common.util.system.ImageUtil
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
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(
    private val reader: ArchiveReader,
    private val panelContext: PanelResolutionContext,
    private val acbfPanelResolver: AcbfPanelResolver = Injekt.get(),
) : PageLoader() {
    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val imageEntries = reader.useEntries { entries ->
            entries
                .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                .toList()
        }

        val panelsByEntry = acbfPanelResolver.resolve(
            reader = reader,
            mangaId = panelContext.mangaId,
            chapterId = panelContext.chapterId,
            archiveFile = panelContext.archiveFile,
            readingDirection = panelContext.readingDirection,
            imageEntries = imageEntries,
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
