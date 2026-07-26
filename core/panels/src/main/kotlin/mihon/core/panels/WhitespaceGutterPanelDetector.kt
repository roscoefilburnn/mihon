package mihon.core.panels

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Classical, model-free panel detector: recursively splits the page along horizontal/vertical
 * whitespace "gutters" (the XY-cut algorithm used for document/layout segmentation), the same
 * general class of technique ACBF Editor's own frame-detection tool is described as using
 * (gutter/whitespace-based, not ML). ACBF Editor's actual algorithm isn't published in
 * accessible source, so this is a from-scratch implementation of that class of technique, not a
 * port of it.
 *
 * Runs in milliseconds on a downscaled page, no bundled model or native dependency. Handles
 * traditional grid-paneled manga with clean white gutters well; [detect] deliberately returns
 * [DetectionResult.Inconclusive] rather than a guess when the page doesn't look cleanly
 * segmentable, so the caller can fall back to an ML detector for harder pages.
 *
 * Known limitation: a page with no visible gutters at all (bleed panels, borderless art) is
 * indistinguishable, by gutter analysis alone, from a genuine one-panel splash page — both
 * produce a single whole-page region. This detector reports that case as *Confident* (a
 * single-panel result is a legitimate, common outcome), not Inconclusive, which means bled
 * multi-panel pages will not be handed off to the ML fallback. This is an accepted trade-off of
 * gutter-based segmentation, not an oversight.
 */
class WhitespaceGutterPanelDetector(
    private val whiteLuminanceThreshold: Int = 235,
    private val minGutterFraction: Float = 0.015f,
    private val maxAnalysisDimension: Int = 400,
    private val maxPlausiblePanels: Int = 12,
    private val maxRecursionDepth: Int = 6,
) : PanelDetector {

    override suspend fun detect(bitmap: Bitmap): DetectionResult {
        val analysisBitmap = downscale(bitmap)
        val width = analysisBitmap.width
        val height = analysisBitmap.height
        val ink = computeInkMask(analysisBitmap)

        if (analysisBitmap !== bitmap) {
            analysisBitmap.recycle()
        }

        if (ink.none { it }) {
            return DetectionResult.Inconclusive("no ink detected on page")
        }

        val regions = mutableListOf<RectF>()
        xyCut(ink, width, height, 0, 0, width, height, regions, depth = 0)

        if (regions.isEmpty()) {
            return DetectionResult.Inconclusive("XY-cut produced no regions")
        }
        if (regions.size > maxPlausiblePanels) {
            return DetectionResult.Inconclusive(
                "XY-cut produced ${regions.size} regions, exceeding the sanity limit of " +
                    "$maxPlausiblePanels — likely screentone/texture noise rather than real gutters",
            )
        }

        return DetectionResult.Confident(
            regions.map { DetectedBox(rect = it, kind = PanelKind.PANEL, confidence = 1f) },
        )
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val largestDimension = maxOf(bitmap.width, bitmap.height)
        if (largestDimension <= maxAnalysisDimension) return bitmap
        val scale = maxAnalysisDimension.toFloat() / largestDimension
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun computeInkMask(bitmap: Bitmap): BooleanArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val mask = BooleanArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luminance = (r * 299 + g * 587 + b * 114) / 1000
            mask[i] = luminance < whiteLuminanceThreshold
        }
        return mask
    }

    /** Recursive XY-cut: alternately looks for a full-span horizontal gutter, then vertical. */
    private fun xyCut(
        ink: BooleanArray,
        fullWidth: Int,
        fullHeight: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        out: MutableList<RectF>,
        depth: Int,
    ) {
        if (x1 - x0 <= 0 || y1 - y0 <= 0) return

        if (depth >= maxRecursionDepth) {
            out.add(normalizedRect(x0, y0, x1, y1, fullWidth, fullHeight))
            return
        }

        val minGutter = (minGutterFraction * maxOf(fullWidth, fullHeight)).toInt().coerceAtLeast(2)

        val rowGutters = findGutterBands(ink, fullWidth, x0, y0, x1, y1, horizontal = true, minGutter)
        if (rowGutters.isNotEmpty()) {
            var cursor = y0
            for ((start, end) in rowGutters) {
                if (start > cursor) xyCut(ink, fullWidth, fullHeight, x0, cursor, x1, start, out, depth + 1)
                cursor = end
            }
            if (cursor < y1) xyCut(ink, fullWidth, fullHeight, x0, cursor, x1, y1, out, depth + 1)
            return
        }

        val colGutters = findGutterBands(ink, fullWidth, x0, y0, x1, y1, horizontal = false, minGutter)
        if (colGutters.isNotEmpty()) {
            var cursor = x0
            for ((start, end) in colGutters) {
                if (start > cursor) xyCut(ink, fullWidth, fullHeight, cursor, y0, start, y1, out, depth + 1)
                cursor = end
            }
            if (cursor < x1) xyCut(ink, fullWidth, fullHeight, cursor, y0, x1, y1, out, depth + 1)
            return
        }

        out.add(normalizedRect(x0, y0, x1, y1, fullWidth, fullHeight))
    }

    /** Contiguous bands, at least [minGutter] wide, that are entirely ink-free across the span. */
    private fun findGutterBands(
        ink: BooleanArray,
        fullWidth: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        horizontal: Boolean,
        minGutter: Int,
    ): List<Pair<Int, Int>> {
        val bands = mutableListOf<Pair<Int, Int>>()
        var bandStart = -1

        val outerRange = if (horizontal) y0 until y1 else x0 until x1
        for (pos in outerRange) {
            val spanHasInk = if (horizontal) {
                (x0 until x1).any { x -> ink[pos * fullWidth + x] }
            } else {
                (y0 until y1).any { y -> ink[y * fullWidth + pos] }
            }

            if (!spanHasInk) {
                if (bandStart == -1) bandStart = pos
            } else if (bandStart != -1) {
                if (pos - bandStart >= minGutter) bands.add(bandStart to pos)
                bandStart = -1
            }
        }
        val end = if (horizontal) y1 else x1
        if (bandStart != -1 && end - bandStart >= minGutter) bands.add(bandStart to end)

        return bands
    }

    private fun normalizedRect(x0: Int, y0: Int, x1: Int, y1: Int, fullWidth: Int, fullHeight: Int): RectF {
        return RectF(
            x0.toFloat() / fullWidth,
            y0.toFloat() / fullHeight,
            x1.toFloat() / fullWidth,
            y1.toFloat() / fullHeight,
        )
    }
}
