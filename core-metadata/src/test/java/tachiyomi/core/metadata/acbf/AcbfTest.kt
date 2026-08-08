package tachiyomi.core.metadata.acbf

import io.kotest.matchers.shouldBe
import nl.adaptivity.xmlutil.core.KtXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.jupiter.api.Test
import java.io.StringReader

class AcbfTest {

    private val xml = XML {
        defaultPolicy {
            ignoreUnknownChildren()
        }
        autoPolymorphic = true
    }

    /**
     * [XML.decodeFromString] resolves its [nl.adaptivity.xmlutil.XmlReader] via a
     * `ServiceLoader`-registered [nl.adaptivity.xmlutil.XmlStreamingFactory], which on this
     * module's classpath is always xmlutil's Android target factory (`AndroidStreamingFactory`,
     * backed by `org.xmlpull.v1.XmlPullParserFactory`) — real on-device, but a stub under plain
     * JUnit's `testDebugUnitTest` that throws "not mocked". [KtXmlReader] is xmlutil's own
     * pure-Kotlin reader (no platform XML parser involved), so decoding through it directly is
     * an equally faithful test of this module's (de)serialization logic without hitting that gap.
     */
    private fun XML.decodeAcbf(
        source: String,
    ) = decodeFromReader(AcbfDocument.serializer(), KtXmlReader(StringReader(source)))

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
        val decoded = xml.decodeAcbf(encoded)

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

        val decoded = xml.decodeAcbf(source)

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
