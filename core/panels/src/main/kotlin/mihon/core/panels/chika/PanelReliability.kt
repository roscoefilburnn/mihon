/*
 * Ported from batunii/chika (https://github.com/batunii/chika),
 * shared/src/commonMain/kotlin/com/chakra/comicreader/detection/PanelReliability.kt.
 *
 * Original work licensed under the Mozilla Public License, v. 2.0
 * (https://mozilla.org/MPL/2.0/). This file is a Modification of that Covered
 * Software and remains licensed under MPL 2.0 (see /LICENSE-MPL-2.0.txt),
 * distributed as part of this Larger Work per MPL 2.0 section 3.3.
 */
package mihon.core.panels.chika

/**
 * Decides whether a page's detected panels are trustworthy enough to drive panel-by-panel auto-pan,
 * or whether the reader should fall back to smooth whole-page reading.
 *
 * The key signal: in a real comic layout panels *tile* the page — they sit side by side and barely
 * overlap. When the detector is confused (borderless/halftone art, dense action spreads) it emits
 * boxes that overlap each other heavily and don't correspond to real panels. So we measure how much
 * the panels overlap one another; past a threshold the layout is "unreliable" and the reader shows
 * the whole page (where the user can pinch-zoom and pan) instead of jerking through bad regions.
 */
object PanelReliability {

    /** Max tolerated overlap (sum of pairwise intersections ÷ total panel area). Calibrated on
     *  Dandadan: clean bordered pages score ≈0.000, confused borderless pages score ≥0.058, so 0.04
     *  separates them with margin (and biases toward whole-page fallback, the safe failure mode). */
    private const val MAX_OVERLAP_RATIO = 0.04f
    /** A single box covering more than this fraction of the page (with others present) is noise. */
    private const val DOMINATING_PANEL = 0.92f

    fun isReliable(panels: List<Panel>): Boolean {
        if (panels.size < 2) return false // nothing meaningful to step through
        if (panels.any { it.area >= DOMINATING_PANEL }) return false // whole-page box + noise
        return overlapScore(panels) <= MAX_OVERLAP_RATIO
    }

    /** Sum of pairwise panel overlap ÷ total panel area. ≈0 for a clean tiling, higher when boxes
     *  pile on top of each other (a confused detection). Exposed for tuning/diagnostics. */
    fun overlapScore(panels: List<Panel>): Float {
        if (panels.size < 2) return 0f
        var totalArea = 0f
        for (p in panels) totalArea += p.area
        if (totalArea <= 0f) return 0f
        var overlap = 0f
        for (i in panels.indices) {
            for (j in i + 1 until panels.size) {
                overlap += intersectionArea(panels[i], panels[j])
            }
        }
        return overlap / totalArea
    }

    private fun intersectionArea(a: Panel, b: Panel): Float {
        val w = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val h = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        return w * h
    }
}
