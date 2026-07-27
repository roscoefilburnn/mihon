package mihon.core.panels

/**
 * A raw detection from a [PanelDetector], before merge/split/reading-order planning.
 *
 * [rect] is normalized to the page (`0f..1f` on both axes) so it's independent of whatever
 * resolution the detector happened to run inference at.
 */
data class DetectedBox(
    val rect: PanelBox,
    val kind: PanelKind,
    val confidence: Float,
)
