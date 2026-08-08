package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import mihon.core.panels.PanelRect
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    /**
     * Panels for this page, in reading order, resolved from embedded/cached/detected ACBF data.
     * Null if panel detection is disabled or hasn't produced a result for this page; readers
     * should treat null the same as an empty list (fall back to full-page display).
     */
    var panels: List<PanelRect>? = null
}
