package mihon.core.panels

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a detector's raw panel/balloon boxes into a clean, ordered panel list: merges
 * small/adjacent panel fragments, splits panels that are implausibly large relative to the rest
 * of the page around gaps between balloon clusters, and sorts the result into reading order.
 *
 * This is a from-scratch reimplementation of the *behavior* batunii/chika's planner is
 * documented to have (merge small/adjacent boxes, split oversized ones around bubble groups,
 * sort by reading direction) — not a code port. Chika's actual planner source was not available
 * to copy from; only its public description was.
 */
class ChikaPanelPlanner(
    private val adjacencyGapFraction: Float = 0.02f,
    private val oversizeAreaMultiple: Float = 2.5f,
) : PanelPlanner {

    override fun plan(
        boxes: List<DetectedBox>,
        pageWidth: Int,
        pageHeight: Int,
        readingDirection: ReadingDirection,
    ): List<PanelRect> {
        val panels = boxes.filter { it.kind == PanelKind.PANEL }.map { it.rect }
        val balloons = boxes.filter { it.kind == PanelKind.BALLOON }.map { it.rect }

        if (panels.isEmpty()) return emptyList()

        val merged = mergeAdjacent(panels)
        val split = splitOversized(merged, balloons)
        val ordered = sortReadingOrder(split, readingDirection)

        return ordered.map { normalized ->
            PanelRect(
                rect = RectF(
                    normalized.left * pageWidth,
                    normalized.top * pageHeight,
                    normalized.right * pageWidth,
                    normalized.bottom * pageHeight,
                ),
            )
        }
    }

    /** Repeatedly unions pairs of panels that touch/overlap or sit within a small gutter gap. */
    private fun mergeAdjacent(panels: List<RectF>): List<RectF> {
        var current = panels.toMutableList()
        var mergedAny = true
        while (mergedAny && current.size > 1) {
            mergedAny = false
            outer@ for (i in current.indices) {
                for (j in current.indices) {
                    if (i == j) continue
                    if (isAdjacent(current[i], current[j])) {
                        val union = RectF(current[i])
                        union.union(current[j])
                        current = (current.filterIndexed { idx, _ -> idx != i && idx != j } + union)
                            .toMutableList()
                        mergedAny = true
                        break@outer
                    }
                }
            }
        }
        return current
    }

    private fun isAdjacent(a: RectF, b: RectF): Boolean {
        val gap = adjacencyGapFraction
        val expandedA = RectF(a.left - gap, a.top - gap, a.right + gap, a.bottom + gap)
        return RectF.intersects(expandedA, b)
    }

    /**
     * If a panel's area is much larger than the median panel area and contains two or more
     * balloon clusters separated by a clear gap, split it at the widest such gap. Otherwise
     * leave it alone — most oversized panels are legitimately large splash/establishing shots.
     */
    private fun splitOversized(panels: List<RectF>, balloons: List<RectF>): List<RectF> {
        if (panels.size < 2) return panels

        val areas = panels.map { it.width() * it.height() }.sorted()
        val medianArea = areas[areas.size / 2]
        if (medianArea <= 0f) return panels

        val result = mutableListOf<RectF>()
        for (panel in panels) {
            val area = panel.width() * panel.height()
            val containedBalloons = balloons.filter { panel.contains(it.centerX(), it.centerY()) }

            if (area <= medianArea * oversizeAreaMultiple || containedBalloons.size < 2) {
                result.add(panel)
                continue
            }

            val splitAt = findWidestGap(panel, containedBalloons)
            if (splitAt == null) {
                result.add(panel)
                continue
            }

            val (axisIsVertical, cut) = splitAt
            if (axisIsVertical) {
                result.add(RectF(panel.left, panel.top, cut, panel.bottom))
                result.add(RectF(cut, panel.top, panel.right, panel.bottom))
            } else {
                result.add(RectF(panel.left, panel.top, panel.right, cut))
                result.add(RectF(panel.left, cut, panel.right, panel.bottom))
            }
        }
        return result
    }

    /**
     * Finds the widest gap between balloon clusters along whichever axis (horizontal split line
     * = vertical cut, or vice versa) the panel is more elongated on, returning
     * `(axisIsVertical, cutPosition)` or null if the balloons don't separate into distinct
     * clusters with room between them.
     */
    private fun findWidestGap(panel: RectF, balloons: List<RectF>): Pair<Boolean, Float>? {
        val preferVerticalCut = panel.width() >= panel.height()

        fun widestGapAlong(centers: List<Float>, low: Float, high: Float): Float? {
            val sorted = centers.sorted()
            var bestGap = 0f
            var bestPos: Float? = null
            for (i in 0 until sorted.size - 1) {
                val gap = sorted[i + 1] - sorted[i]
                if (gap > bestGap) {
                    bestGap = gap
                    bestPos = (sorted[i] + sorted[i + 1]) / 2f
                }
            }
            val span = high - low
            return bestPos?.takeIf { bestGap > span * 0.15f }
        }

        val verticalCut = widestGapAlong(balloons.map { it.centerX() }, panel.left, panel.right)
        val horizontalCut = widestGapAlong(balloons.map { it.centerY() }, panel.top, panel.bottom)

        return when {
            preferVerticalCut && verticalCut != null -> true to verticalCut
            !preferVerticalCut && horizontalCut != null -> false to horizontalCut
            verticalCut != null -> true to verticalCut
            horizontalCut != null -> false to horizontalCut
            else -> null
        }
    }

    /** Groups panels into top-to-bottom rows by vertical-center overlap, then orders each row. */
    private fun sortReadingOrder(panels: List<RectF>, direction: ReadingDirection): List<RectF> {
        val remaining = panels.sortedBy { it.top }.toMutableList()
        val rows = mutableListOf<MutableList<RectF>>()

        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val row = mutableListOf(seed)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val overlaps = row.any { verticalOverlapFraction(it, candidate) > 0.4f }
                if (overlaps) {
                    row.add(candidate)
                    iterator.remove()
                }
            }
            rows.add(row)
        }

        return rows.flatMap { row ->
            when (direction) {
                ReadingDirection.LEFT_TO_RIGHT -> row.sortedBy { it.left }
                ReadingDirection.RIGHT_TO_LEFT -> row.sortedByDescending { it.right }
            }
        }
    }

    private fun verticalOverlapFraction(a: RectF, b: RectF): Float {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (overlap <= 0f) return 0f
        val smaller = min(a.height(), b.height())
        return if (smaller <= 0f) 0f else overlap / smaller
    }
}
