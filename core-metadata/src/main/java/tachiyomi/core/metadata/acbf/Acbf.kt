package tachiyomi.core.metadata.acbf

import android.graphics.RectF
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

const val ACBF_FILE_EXTENSION = "acbf"

private const val ACBF_NAMESPACE = "http://www.acbf.info/xml/acbf/1.1"

/**
 * Minimal model of the Advanced Comic Book Format (ACBF) 1.1 `<body>` section, scoped to what
 * this app needs: per-page panel ("frame") geometry.
 *
 * https://github.com/ACBF-Advanced-Comic-Book-Format/ACBF, `XML Schema/acbf-1.1.xsd`
 *
 * The real schema's root `<ACBF>` element also allows `<style>`, `<meta-data>`, `<references>`
 * and `<data>` siblings of `<body>` (book-level info: title, author, embedded binary resources,
 * etc.) which are intentionally not modeled here. Parsing real embedded ACBF files still works
 * because the injected [nl.adaptivity.xmlutil.serialization.XML] instance is configured to
 * ignore unknown children; when *we* generate an ACBF document (no embedded ACBF existed), the
 * result is a body-only file used solely for this app's private panel-data cache, never written
 * back into the user's archive, so full root-level spec completeness isn't required for it.
 */
@Serializable
@XmlSerialName("ACBF", ACBF_NAMESPACE, "")
data class AcbfDocument(
    val body: Body,
) {

    @Serializable
    @XmlSerialName("body", ACBF_NAMESPACE, "")
    data class Body(
        val page: List<Page> = emptyList(),
    )

    @Serializable
    @XmlSerialName("page", ACBF_NAMESPACE, "")
    data class Page(
        val image: Image,
        val frame: List<Frame> = emptyList(),
    )

    /** `<image href="page001.jpg"/>` — background raster reference, required by the schema. */
    @Serializable
    @XmlSerialName("image", ACBF_NAMESPACE, "")
    data class Image(
        @XmlElement(false)
        val href: String,
    )

    /**
     * A single panel/frame on a page.
     *
     * [points] is the ACBF-native format: a space-delimited list of "x,y" pixel pairs, in the
     * background image's own pixel coordinate space, e.g. `"10,75 650,137 650,562 10,562"`.
     * Real, human-authored ACBF can encode arbitrary polygons (diagonal panel cuts are common in
     * manga); frames this app generates itself are always axis-aligned quads. [rectF] takes the
     * bounding box of whatever polygon is present, which is all the reader's zoom/pan needs but
     * is a deliberate simplification for non-rectangular, human-authored frames.
     */
    @Serializable
    @XmlSerialName("frame", ACBF_NAMESPACE, "")
    data class Frame(
        @XmlElement(false)
        val points: String,
        @XmlElement(false)
        val bgcolor: String? = null,
    )
}

/**
 * Parses [AcbfDocument.Frame.points] into its bounding box. Returns null if [points] doesn't
 * contain at least one well-formed "x,y" pair.
 */
fun AcbfDocument.Frame.rectF(): RectF? {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    for (pair in points.trim().split(Regex("\\s+"))) {
        if (pair.isEmpty()) continue
        val parts = pair.split(",")
        if (parts.size != 2) continue
        val x = parts[0].toFloatOrNull() ?: continue
        val y = parts[1].toFloatOrNull() ?: continue
        xs.add(x)
        ys.add(y)
    }
    if (xs.isEmpty()) return null
    return RectF(xs.min(), ys.min(), xs.max(), ys.max())
}

/** Builds the ACBF-native `points` string for an axis-aligned rectangle, corners in order. */
fun RectF.toAcbfPoints(): String {
    return "${left.toInt()},${top.toInt()} ${right.toInt()},${top.toInt()} " +
        "${right.toInt()},${bottom.toInt()} ${left.toInt()},${bottom.toInt()}"
}
