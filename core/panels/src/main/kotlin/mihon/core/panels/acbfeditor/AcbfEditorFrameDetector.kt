/*
 * Ported from ACBF Editor 3.0 (https://github.com/ACBF-Advanced-Comic-Book-Format),
 * src/frames_editor.py: FramesEditorDialog.frames_detection() and its
 * centroid_for_polygon()/area_for_polygon()/round_to() helpers.
 *
 * Original work Copyright (C) 2011-2024 Robert Kubik
 * (https://github.com/ACBF-Advanced-Comic-Book-Format), licensed under the GNU General
 * Public License, version 3 (see /LICENSE-GPL-3.0.txt at the repository root). GPLv3 is
 * copyleft in a way MPL 2.0 is not: incorporating this code into an Apache-2.0 app is not a
 * "Larger Work" combination the way the batunii/chika port is (see
 * mihon.core.panels.chika's NOTICE) — if this build is ever distributed, the GPLv3
 * implications of including this file need to be resolved first (e.g. isolating it behind a
 * process boundary, replacing it, or relicensing the distributed work). Ported here on the
 * basis that this build is for personal use and is not being distributed.
 */
package mihon.core.panels.acbfeditor

import android.graphics.Bitmap
import mihon.core.panels.DetectedBox
import mihon.core.panels.DetectionResult
import mihon.core.panels.PanelBox
import mihon.core.panels.PanelDetector
import mihon.core.panels.PanelKind
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Detects comic panel frames by finding contours around edges (typically the drawn panel
 * border lines), the same approach ACBF Editor's "Detect Frames" feature uses. This is this
 * module's classical (non-ML) detector, tried before the ML fallback. Two passes, matching the
 * original exactly:
 *
 * 1. Canny edge detection on a bilateral-filtered, bordered grayscale page, closed with a small
 *    morphological kernel, then external contours filtered to plausible panel-sized
 *    (3%-95% of page area), roughly-rectangular (4-7 approximated vertices) shapes.
 * 2. If those frames cover less than 90% of their overall bounding area, a second pass masks
 *    out the already-found frames and the outer margin, then re-detects contours in what's left
 *    — catching panels the first pass missed (e.g. borderless against a busy background).
 *
 * Reports [DetectionResult.Inconclusive] when zero frames are found in the first pass, matching
 * ACBF Editor's own "Failed to detect frames" outcome — the natural signal for this module's
 * detector-fallback chain to hand off to the ML detector instead.
 */
class AcbfEditorFrameDetector(
    private val cannyLow: Double = 10.0,
    private val cannyHigh: Double = 500.0,
    private val minAreaFraction: Double = 0.03,
    private val maxAreaFraction: Double = 0.95,
    private val approxEpsilonFraction: Double = 0.01,
    private val borderFraction: Double = 0.008,
    private val minBorder: Int = 2,
    private val imageBorder: Int = 6,
    private val imageBorderFillValue: Double = 250.0,
    private val coverageRatioThreshold: Double = 0.9,
    private val rowBucketSize: Double = 15.0,
) : PanelDetector {

    companion object {
        // OpenCV for Android ships its native library inside this AAR but doesn't load it
        // automatically — the first touch of any org.opencv.* class before this runs crashes with
        // UnsatisfiedLinkError. Loaded once, lazily, so pages never pay this cost unless panel
        // detection actually runs.
        private val nativeLibraryLoaded: Boolean by lazy { OpenCVLoader.initLocal() }
    }

    override suspend fun detect(bitmap: Bitmap): DetectionResult {
        if (!nativeLibraryLoaded) return DetectionResult.Inconclusive("OpenCV native library failed to load")

        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val width = rgba.cols()
        val height = rgba.rows()

        val border = max(minBorder, (borderFraction * min(height, width)).roundToInt())

        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()

        val filtered = Mat()
        Imgproc.bilateralFilter(gray, filtered, 2, 10.0, 120.0)
        gray.release()

        val bordered = Mat()
        Core.copyMakeBorder(
            filtered,
            bordered,
            imageBorder,
            imageBorder,
            imageBorder,
            imageBorder,
            Core.BORDER_CONSTANT,
            Scalar(imageBorderFillValue),
        )
        filtered.release()

        val mask = Mat.zeros(height, width, CvType.CV_8UC1)

        val firstPass = findFrameCandidates(bordered, width, height, border, mask, applyBorderOffset = true)
        bordered.release()

        if (firstPass.isEmpty()) {
            mask.release()
            return DetectionResult.Inconclusive("no frames detected")
        }

        val allPoints = firstPass.flatMap { it.points }
        val boundsMinX = (allPoints.minOf { it.x } - border).coerceAtLeast(0.0)
        val boundsMaxX = (allPoints.maxOf { it.x } + border).coerceAtMost(width.toDouble())
        val boundsMinY = (allPoints.minOf { it.y } - border).coerceAtLeast(0.0)
        val boundsMaxY = (allPoints.maxOf { it.y } + border).coerceAtMost(height.toDouble())
        val boundsArea = (boundsMaxX - boundsMinX) * (boundsMaxY - boundsMinY)

        val candidates = firstPass.toMutableList()

        if (boundsArea > 0 && firstPass.sumOf { it.area } / boundsArea < coverageRatioThreshold) {
            candidates += findMissedFrames(mask, width, height, border, boundsMinX, boundsMaxX, boundsMinY, boundsMaxY)
        }
        mask.release()

        if (candidates.isEmpty()) return DetectionResult.Inconclusive("no frames detected")

        val ordered = candidates.sortedWith(
            compareBy({ (it.minY / rowBucketSize).roundToInt() }, { it.minX }),
        )

        val boxes = ordered.map { candidate ->
            val left = candidate.points.minOf { it.x }.coerceIn(0.0, width.toDouble())
            val top = candidate.points.minOf { it.y }.coerceIn(0.0, height.toDouble())
            val right = candidate.points.maxOf { it.x }.coerceIn(0.0, width.toDouble())
            val bottom = candidate.points.maxOf { it.y }.coerceIn(0.0, height.toDouble())
            DetectedBox(
                rect = PanelBox(
                    (left / width).toFloat(),
                    (top / height).toFloat(),
                    (right / width).toFloat(),
                    (bottom / height).toFloat(),
                ),
                kind = PanelKind.PANEL,
                confidence = 1f,
            )
        }
        return DetectionResult.Confident(boxes)
    }

    private data class Candidate(val points: List<Point>, val area: Double, val minX: Double, val minY: Double)

    /**
     * First-pass detection: Canny + close + external contours on [source], filtered to
     * plausible panel shapes. When [applyBorderOffset] is true, points are un-offset from the
     * [imageBorder]-px padding added to [source] and pushed outward from the contour's centroid
     * by [border] px (matching the original's "enlarge rectangle" step); the second pass reuses
     * this same contour-filtering core without that offset/enlargement (see [findMissedFrames]).
     */
    private fun findFrameCandidates(
        source: Mat,
        width: Int,
        height: Int,
        border: Int,
        mask: Mat?,
        applyBorderOffset: Boolean,
    ): List<Candidate> {
        val edges = Mat()
        Imgproc.Canny(source, edges, cannyLow, cannyHigh)

        val kernelSize = max(1, border / 2)
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(kernelSize.toDouble(), kernelSize.toDouble()),
        )
        val closed = Mat()
        Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
        edges.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        closed.release()
        hierarchy.release()

        val pageArea = (width * height).toDouble()
        val results = ArrayList<Candidate>()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area <= pageArea * minAreaFraction || area >= pageArea * maxAreaFraction) continue

            val contour2f = MatOfPoint2f(*contour.toArray())
            val arcLen = Imgproc.arcLength(contour2f, true)
            val approx2f = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx2f, approxEpsilonFraction * arcLen, true)
            val approxPoints = approx2f.toArray()
            contour2f.release()
            approx2f.release()

            val vertexCount = approxPoints.size
            val plausibleShape = if (applyBorderOffset) vertexCount in 3..6 else vertexCount > 3
            if (!plausibleShape) continue

            mask?.let { Imgproc.drawContours(it, listOf(contour), 0, Scalar(255.0), -1) }

            val points = if (applyBorderOffset) {
                val moments = Imgproc.moments(contour)
                val cx = moments.m10 / moments.m00
                val cy = moments.m01 / moments.m00
                approxPoints.map { p ->
                    var x = p.x - imageBorder
                    var y = p.y - imageBorder
                    x += if (x > cx) border.toDouble() else -border.toDouble()
                    y += if (y > cy) border.toDouble() else -border.toDouble()
                    Point(x.coerceIn(0.0, width.toDouble()), y.coerceIn(0.0, height.toDouble()))
                }
            } else {
                approxPoints.toList()
            }

            results += Candidate(
                points = points,
                area = area,
                minX = points.minOf { it.x },
                minY = points.minOf { it.y },
            )
        }
        return results
    }

    /**
     * Second pass: mask out already-found frames (drawn into [mask] by the first pass) and the
     * outer margin outside the overall detected bounds, close small gaps between frames so they
     * don't read as gaps, invert, erode to drop noise, then re-detect contours in what's left —
     * regions the first pass missed.
     */
    private fun findMissedFrames(
        mask: Mat,
        width: Int,
        height: Int,
        border: Int,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
    ): List<Candidate> {
        val working = mask.clone()
        for (row in 0 until height) {
            if (row <= minY || row >= maxY) working.row(row).setTo(Scalar(255.0))
        }
        for (col in 0 until width) {
            if (col <= minX || col >= maxX) working.col(col).setTo(Scalar(255.0))
        }

        val closeKernelSize = max(1, border * 4)
        val closeKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(closeKernelSize.toDouble(), closeKernelSize.toDouble()),
        )
        Imgproc.morphologyEx(working, working, Imgproc.MORPH_CLOSE, closeKernel)

        Core.bitwise_not(working, working)

        val erodeKernelSize = max(1, border * 2)
        val erodeKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(erodeKernelSize.toDouble(), erodeKernelSize.toDouble()),
        )
        Imgproc.erode(working, working, erodeKernel)

        val edges = Mat()
        Imgproc.Canny(working, edges, cannyLow, cannyHigh)
        working.release()

        val smallKernelSize = max(1, border / 2)
        val smallKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(smallKernelSize.toDouble(), smallKernelSize.toDouble()),
        )
        val closedEdges = Mat()
        Imgproc.morphologyEx(edges, closedEdges, Imgproc.MORPH_CLOSE, smallKernel)
        edges.release()

        val missed = findFrameCandidates(closedEdges, width, height, border, mask = null, applyBorderOffset = false)
        closedEdges.release()
        return missed
    }
}
