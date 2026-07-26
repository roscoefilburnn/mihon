package mihon.core.panels

import android.graphics.RectF

/**
 * A planned panel, in the source page's actual pixel coordinate space (not normalized) — ready
 * to be written directly as an ACBF `<frame points="...">` (see
 * `tachiyomi.core.metadata.acbf.RectF.toAcbfPoints`) or consumed by the reader's zoom/pan.
 */
data class PanelRect(
    val rect: RectF,
)
