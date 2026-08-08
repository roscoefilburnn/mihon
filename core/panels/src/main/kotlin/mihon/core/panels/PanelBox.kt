package mihon.core.panels

/**
 * A pure-Kotlin rectangle — deliberately not `android.graphics.RectF`. This module's detector
 * and planner logic needs to stay plain-JUnit-testable, and Android framework classes like
 * `RectF` don't behave correctly when constructed outside a real Android runtime (their real
 * implementation isn't available under `testDebugUnitTest`'s stub classpath). Callers running
 * on-device convert this to `RectF` at the boundary (see `ReaderPageImageView.zoomToPanel`).
 */
data class PanelBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}
