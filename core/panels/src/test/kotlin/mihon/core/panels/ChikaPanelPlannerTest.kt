package mihon.core.panels

import android.graphics.RectF
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChikaPanelPlannerTest {

    private val planner = ChikaPanelPlanner()

    @Test
    fun `orders a 2x2 grid left-to-right, top-to-bottom`() {
        // top-left, top-right, bottom-left, bottom-right, fed out of order.
        val boxes = listOf(
            box(0.55f, 0.05f, 0.95f, 0.45f), // top-right
            box(0.05f, 0.55f, 0.45f, 0.95f), // bottom-left
            box(0.05f, 0.05f, 0.45f, 0.45f), // top-left
            box(0.55f, 0.55f, 0.95f, 0.95f), // bottom-right
        )

        val planned = planner.plan(boxes, pageWidth = 1000, pageHeight = 1000, ReadingDirection.LEFT_TO_RIGHT)

        planned.map { it.rect.left } shouldBe listOf(50f, 550f, 50f, 550f)
        planned.map { it.rect.top } shouldBe listOf(50f, 50f, 550f, 550f)
    }

    @Test
    fun `right-to-left flips column order within each row`() {
        val boxes = listOf(
            box(0.05f, 0.05f, 0.45f, 0.45f), // left column
            box(0.55f, 0.05f, 0.95f, 0.45f), // right column
        )

        val planned = planner.plan(boxes, pageWidth = 1000, pageHeight = 1000, ReadingDirection.RIGHT_TO_LEFT)

        planned.first().rect.left shouldBe 550f
        planned.last().rect.left shouldBe 50f
    }

    @Test
    fun `merges two touching fragments into one panel`() {
        val boxes = listOf(
            box(0.05f, 0.05f, 0.45f, 0.95f),
            box(0.451f, 0.05f, 0.95f, 0.95f), // touching the first, tiny gap
        )

        val planned = planner.plan(boxes, pageWidth = 1000, pageHeight = 1000, ReadingDirection.LEFT_TO_RIGHT)

        planned.size shouldBe 1
        planned[0].rect.left shouldBe 50f
        planned[0].rect.right shouldBe 950f
    }

    @Test
    fun `ignores balloon boxes as output panels`() {
        val boxes = listOf(
            box(0.05f, 0.05f, 0.95f, 0.95f, kind = PanelKind.PANEL),
            box(0.4f, 0.4f, 0.6f, 0.6f, kind = PanelKind.BALLOON),
        )

        val planned = planner.plan(boxes, pageWidth = 1000, pageHeight = 1000, ReadingDirection.LEFT_TO_RIGHT)

        planned.size shouldBe 1
    }

    @Test
    fun `no panel boxes yields an empty plan`() {
        val planned = planner.plan(emptyList(), pageWidth = 1000, pageHeight = 1000, ReadingDirection.LEFT_TO_RIGHT)

        planned.size shouldBe 0
    }

    private fun box(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        kind: PanelKind = PanelKind.PANEL,
    ) = DetectedBox(rect = RectF(left, top, right, bottom), kind = kind, confidence = 1f)
}
