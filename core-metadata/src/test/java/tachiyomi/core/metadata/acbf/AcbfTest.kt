package tachiyomi.core.metadata.acbf

import io.kotest.matchers.shouldBe
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.jupiter.api.Test

class AcbfTest {

    private val xml = XML {
        defaultPolicy {
            ignoreUnknownChildren()
        }
        autoPolymorphic = true
    }

    @Test
    fun `round-trips a generated document`() {
        val document = AcbfDocument(
            body = AcbfDocument.Body(
                page = listOf(
                    AcbfDocument.Page(
                        image = AcbfDocument.Image(href = "page001.jpg"),
                        frame = listOf(
                            AcbfDocument.Frame(points = "10,75 650,137 650,562 10,562"),
                        ),
                    ),
                ),
            ),
        )

        val encoded = xml.encodeToString(AcbfDocument.serializer(), document)
        val decoded = xml.decodeFromString(AcbfDocument.serializer(), encoded)

        decoded shouldBe document
    }

    @Test
    fun `parses a page with no frames and unknown sibling sections`() {
        val source = """
            <ACBF xmlns="http://www.acbf.info/xml/acbf/1.1">
              <meta-data>
                <book-info><author><first-name>Someone</first-name></author></book-info>
              </meta-data>
              <body>
                <page>
                  <image href="page001.jpg"/>
                </page>
              </body>
            </ACBF>
        """.trimIndent()

        val decoded = xml.decodeFromString(AcbfDocument.serializer(), source)

        decoded.body.page.size shouldBe 1
        decoded.body.page[0].image.href shouldBe "page001.jpg"
        decoded.body.page[0].frame.size shouldBe 0
    }

    @Test
    fun `parses real embedded frame points with a bounding box`() {
        val frame = AcbfDocument.Frame(points = "10,75 650,137 650,562 10,562")

        val rect = frame.bounds()!!

        rect.left shouldBe 10f
        rect.top shouldBe 75f
        rect.right shouldBe 650f
        rect.bottom shouldBe 562f
    }

    @Test
    fun `bounds returns null for malformed points`() {
        AcbfDocument.Frame(points = "").bounds() shouldBe null
        AcbfDocument.Frame(points = "not-a-point").bounds() shouldBe null
    }
}
