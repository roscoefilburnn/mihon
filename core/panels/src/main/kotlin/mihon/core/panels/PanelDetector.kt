package mihon.core.panels

import android.graphics.Bitmap

interface PanelDetector {
    suspend fun detect(bitmap: Bitmap): DetectionResult
}

sealed interface DetectionResult {
    /** The detector confidently segmented the page — including the valid case of one box
     * covering the whole page (e.g. a splash page). */
    data class Confident(val boxes: List<DetectedBox>) : DetectionResult

    /** The detector couldn't confidently segment this page; a different detector should try. */
    data class Inconclusive(val reason: String) : DetectionResult
}
