package mihon.core.panels

interface PanelPlanner {
    /**
     * Turns a detector's raw [boxes] (normalized `0f..1f` coordinates) into an ordered list of
     * [PanelRect]s in real page-pixel coordinates ([pageWidth]x[pageHeight]), sorted in reading
     * order for [readingDirection].
     */
    fun plan(
        boxes: List<DetectedBox>,
        pageWidth: Int,
        pageHeight: Int,
        readingDirection: ReadingDirection,
    ): List<PanelRect>
}
