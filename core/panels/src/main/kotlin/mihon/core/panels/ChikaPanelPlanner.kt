package mihon.core.panels

import mihon.core.panels.chika.Panel
import mihon.core.panels.chika.PanelPipeline
import mihon.core.panels.chika.PanelReliability

/**
 * Adapts this module's detector-agnostic [DetectedBox] list onto batunii/chika's actual
 * post-detection pipeline (merge small/adjacent panels, divide oversized ones around bubble
 * gaps, sort into reading order, then a reliability check) — see the ported files under
 * [mihon.core.panels.chika] for the real algorithm and its MPL-2.0 attribution.
 *
 * Unreliable results (see [PanelReliability]) — an overlap pattern typical of a confused
 * detection on borderless/halftone art rather than a real panel layout — are reported as an
 * empty plan, matching this module's convention of falling back to full-page display rather
 * than showing jerky/wrong panel navigation.
 */
class ChikaPanelPlanner : PanelPlanner {

    override fun plan(
        boxes: List<DetectedBox>,
        pageWidth: Int,
        pageHeight: Int,
        readingDirection: ReadingDirection,
    ): List<PanelRect> {
        val panels = boxes.filter { it.kind == PanelKind.PANEL }.map { it.rect.toChikaPanel() }
        val bubbles = boxes.filter { it.kind == PanelKind.BALLOON }.map { it.rect.toChikaPanel() }
        if (panels.isEmpty()) return emptyList()

        val rightToLeft = readingDirection == ReadingDirection.RIGHT_TO_LEFT
        val zoomRegions = PanelPipeline.zoomRegions(panels, bubbles, pageWidth, pageHeight, rightToLeft)

        if (!PanelReliability.isReliable(zoomRegions)) return emptyList()

        return zoomRegions.map { panel ->
            PanelRect(
                rect = PanelBox(
                    panel.left * pageWidth,
                    panel.top * pageHeight,
                    panel.right * pageWidth,
                    panel.bottom * pageHeight,
                ),
            )
        }
    }

    private fun PanelBox.toChikaPanel(): Panel = Panel(left, top, right, bottom)
}
