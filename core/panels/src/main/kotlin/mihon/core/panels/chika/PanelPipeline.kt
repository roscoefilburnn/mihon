/*
 * Ported from batunii/chika (https://github.com/batunii/chika),
 * shared/src/commonMain/kotlin/com/chakra/comicreader/detection/PanelPipeline.kt.
 *
 * Original work licensed under the Mozilla Public License, v. 2.0
 * (https://mozilla.org/MPL/2.0/). This file is a Modification of that Covered
 * Software and remains licensed under MPL 2.0 (see /LICENSE-MPL-2.0.txt),
 * distributed as part of this Larger Work per MPL 2.0 section 3.3.
 */
package mihon.core.panels.chika

/**
 * The full shared post-detection pipeline in one call: raw detected boxes go in, final zoom
 * regions in reading order come out. Callers should use this rather than composing [PanelOrdering]
 * and [PanelPlanner] themselves so both stay in sync.
 */
object PanelPipeline {
    /**
     * Each framed panel is grown by this fraction of its own size on every side, so the reader shows
     * a little context around it instead of hugging the detected box edge-to-edge. Detected boxes run
     * tight and clip overflowing speech bubbles / furigana / SFX; this breathing room brings them back
     * into frame. At the reader's `fill = 0.98` framing, ~5.7% per side leaves the *original* panel
     * filling ~88% of the screen (a ~12% gap). Only real panels are padded — the whole-page intro/outro
     * slots the reader synthesizes aren't in this list, so they still fill the screen.
     */
    private const val PANEL_MARGIN = 0.057f

    fun zoomRegions(
        panels: List<Panel>,
        bubbles: List<Panel>,
        pageW: Int,
        pageH: Int,
        rightToLeft: Boolean,
    ): List<Panel> {
        // Add any large, roughly-rectangular region the model left uncovered as a panel, so missed
        // panels get numbered too — then order and plan as usual.
        // Manga (read right-to-left) is paced panel-by-panel, so it uses a profile that merges far
        // less aggressively; Western LTR comics keep the default grid-friendly merging.
        val config = if (rightToLeft) PanelPlanner.Config.MANGA else PanelPlanner.Config()
        val filled = PanelGapFiller.fill(panels)
        val ordered = PanelOrdering.order(filled, rightToLeft)
        val planned = PanelPlanner.plan(ordered, bubbles, pageW, pageH, rightToLeft, config)
        if (planned.size >= 2) return padIfReliable(planned)
        // The model found nothing usable (or it collapsed to a single region): rather than show the
        // page as one panel, treat the whole page as a panel and let the ratio splitter break it up.
        return padIfReliable(PanelPlanner.plan(listOf(Panel.FULL_PAGE), bubbles, pageW, pageH, rightToLeft, config))
    }

    /**
     * Applies [PanelReliability] to the *unpadded* plan, then pads what survives.
     *
     * Order matters: PanelReliability's overlap threshold is calibrated against panels as detected,
     * where a clean tiling scores ≈0. [PANEL_MARGIN] grows every panel into its neighbours, and
     * manga gutters run 1-2% of page width — far narrower than the 5.7% padding — so measuring
     * overlap after padding invents overlap that the layout never had and rejects ordinary pages as
     * "confused".
     */
    private fun padIfReliable(panels: List<Panel>): List<Panel> {
        if (!PanelReliability.isReliable(panels)) return emptyList()
        return pad(panels)
    }

    /** Grows each panel by [PANEL_MARGIN] of its own size per side, clamped to the page. */
    private fun pad(panels: List<Panel>): List<Panel> = panels.map { p ->
        val dx = p.width * PANEL_MARGIN
        val dy = p.height * PANEL_MARGIN
        Panel(
            (p.left - dx).coerceAtLeast(0f),
            (p.top - dy).coerceAtLeast(0f),
            (p.right + dx).coerceAtMost(1f),
            (p.bottom + dy).coerceAtMost(1f),
        )
    }
}
