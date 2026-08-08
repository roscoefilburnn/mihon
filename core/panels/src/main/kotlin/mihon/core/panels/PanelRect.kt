package mihon.core.panels

/**
 * A planned panel, in the source page's actual pixel coordinate space (not normalized) — ready
 * to be written directly as an ACBF `<frame points="...">` (see
 * `tachiyomi.core.metadata.acbf.AcbfRect.toAcbfPoints`) or consumed by the reader's zoom/pan
 * (converted to `android.graphics.RectF` at that boundary).
 */
data class PanelRect(
    val rect: PanelBox,
)
