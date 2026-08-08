package mihon.core.panels

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import mihon.core.panels.chika.Panel
import mihon.core.panels.chika.PanelOrdering
import mihon.core.panels.chika.PanelPlanner
import org.junit.jupiter.api.Test

class ChikaPanelPlannerTest {

    private val planner = ChikaPanelPlanner()

    @Test
    fun `orders a clean 2x2 grid top-to-bottom, left-to-right`() {
        // Each ~0.16 of page area: not "small" (merge candidate) nor "big" (divide candidate)
        // under chika's default Config, and non-overlapping, so the pipeline should pass them
        // through as 4 reliable panels in reading order.
        val boxes = listOf(
            box(0.55f, 0.05f, 0.95f, 0.45f, PanelKind.PANEL), // top-right
            box(0.05f, 0.55f, 0.45f, 0.95f, PanelKind.PANEL), // bottom-left
            box(0.05f, 0.05f, 0.45f, 0.45f, PanelKind.PANEL), // top-left
            box(0.55f, 0.55f, 0.95f, 0.95f, PanelKind.PANEL), // bottom-right
        )

        val planned = planner.plan(boxes, pageWidth = 1000, pageHeight = 1000, ReadingDirection.LEFT_TO_RIGHT)

        planned shouldHaveSize 4
        // reading order: top-left, top-right, bottom-left, bottom-right
        val centers = planned.map { it.rect.centerX to it.rect.centerY }
        (centers[0].first < centers[1].first) shouldBe true // top-left before top-right
        (centers[0].second < centers[2].second) shouldBe true // top row before bottom row
        (centers[2].first < centers[3].first) shouldBe true // bottom-left before bottom-right
    }

    // The next two behaviors — reading-order sort and small-panel merging — belong to
    // PanelOrdering/PanelPlanner themselves and are tested against those directly. Going through
    // the full ChikaPanelPlanner (gap-filling + margin-padding + the reliability gate) needs boxes
    // that plausibly tile a whole real page; a couple of hand-picked boxes leave the rest of the
    // page "uncovered", which PanelGapFiller fills with large synthetic regions and the padding
    // step then pushes into overlap — correctly making PanelReliability reject the page (see
    // "heavily overlapping garbage boxes" below), but for the wrong reason for what these two
    // tests want to isolate.

    @Test
    fun `right-to-left flips column order within each row`() {
        val left = Panel(0.05f, 0.05f, 0.45f, 0.45f)
        val right = Panel(0.55f, 0.05f, 0.95f, 0.45f)

        val ordered = PanelOrdering.order(listOf(left, right), rightToLeft = true)

        ordered shouldBe listOf(right, left)
    }

    @Test
    fun `merges a run of small adjacent panels`() {
        // Each well under chika's 0.10 small-area threshold and touching, so they merge into one.
        val boxes = listOf(
            Panel(0.05f, 0.05f, 0.20f, 0.15f),
            Panel(0.205f, 0.05f, 0.35f, 0.15f),
        )

        val planned = PanelPlanner.plan(boxes, bubbles = emptyList(), pageW = 1000, pageH = 1000, rightToLeft = false)

        planned shouldHaveSize 1
    }

    @Test
    fun `ignores balloon boxes as output panels`() {
        val boxes = listOf(box(0.4f, 0.4f, 0.6f, 0.6f, PanelKind.BALLOON))

        val planned = planner.plan(boxes, pageWidth = 1000, pageHeight = 1000, ReadingDirection.LEFT_TO_RIGHT)

        planned shouldHaveSize 0
    }

    @Test
    fun `no panel boxes yields an empty plan`() {
        val planned = planner.plan(emptyList(), pageWidth = 1000, pageHeight = 1000, ReadingDirection.LEFT_TO_RIGHT)

        planned shouldHaveSize 0
    }

    @Test
    fun `heavily overlapping garbage boxes are treated as unreliable and dropped`() {
        // Four boxes all covering nearly the same region: high mutual overlap, the signature
        // PanelReliability treats as a confused detection rather than a real tiled layout.
        val boxes = (0 until 4).map { i ->
            val jitter = i * 0.01f
            box(0.1f + jitter, 0.1f + jitter, 0.9f + jitter, 0.9f + jitter, PanelKind.PANEL)
        }

        val planned = planner.plan(boxes, pageWidth = 1000, pageHeight = 1000, ReadingDirection.LEFT_TO_RIGHT)

        planned shouldHaveSize 0
    }

    private fun box(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        kind: PanelKind,
    ) = DetectedBox(rect = PanelBox(left, top, right, bottom), kind = kind, confidence = 1f)
}
