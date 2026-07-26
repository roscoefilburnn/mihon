package mihon.core.panels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

private const val MODEL_ASSET_PATH = "models/chika_manga109.tflite"
private const val MODEL_INPUT_SIZE = 640

/**
 * ML fallback detector: a YOLO-family TensorFlow Lite model (trained on Manga109-s), ported
 * conceptually from batunii/chika, for pages the classical [WhitespaceGutterPanelDetector]
 * can't confidently segment (bleed panels, splash pages, borderless/textured art).
 *
 * **Not wired up for real inference yet.** The asset-loading and 640x640 letterboxing pipeline
 * below is real, but [decodeOutputs] — turning the model's raw output tensor into
 * [DetectedBox]es — depends on that specific model's exact output tensor shape and box-encoding
 * convention (anchor layout, class ordering, NMS threshold, etc.), which were not available
 * without the actual `.tflite` file and its accompanying spec in hand. Wiring this up for real
 * is tracked separately; until then this always reports [DetectionResult.Inconclusive], which
 * safely no-ops in the resolver pipeline rather than fabricating boxes from guessed output
 * semantics.
 */
class TfliteChikaDetector(private val context: Context) : PanelDetector, Closeable {

    private val interpreter: Interpreter? by lazy { loadInterpreter() }

    override suspend fun detect(bitmap: Bitmap): DetectionResult {
        val model = interpreter
            ?: return DetectionResult.Inconclusive("chika_manga109.tflite model asset not bundled")

        val (letterboxed, _) = letterbox(bitmap, MODEL_INPUT_SIZE)
        return decodeOutputs(model, letterboxed)
    }

    private fun loadInterpreter(): Interpreter? {
        return try {
            context.assets.openFd(MODEL_ASSET_PATH).use { fd ->
                val buffer: MappedByteBuffer = FileInputStreamCompat.mapEntireFile(fd)
                Interpreter(buffer)
            }
        } catch (_: java.io.FileNotFoundException) {
            // Model asset isn't bundled yet — see class doc.
            null
        }
    }

    /** Letterboxes [bitmap] into a square [targetSize]x[targetSize] canvas, returning the scale
     * factor that was applied (needed to map detections back to page-normalized coordinates). */
    private fun letterbox(bitmap: Bitmap, targetSize: Int): Pair<Bitmap, Float> {
        val scale = targetSize.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaledWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        canvas.drawColor(android.graphics.Color.WHITE)

        val matrix = Matrix().apply { setScale(scale, scale) }
        val scaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val offsetX = (targetSize - scaledWidth) / 2f
        val offsetY = (targetSize - scaledHeight) / 2f
        canvas.drawBitmap(scaledBitmap, offsetX, offsetY, null)
        if (scaledBitmap !== bitmap) scaledBitmap.recycle()

        return output to scale
    }

    @Suppress("UNUSED_PARAMETER")
    private fun decodeOutputs(model: Interpreter, letterboxedInput: Bitmap): DetectionResult {
        // See class doc: needs the real model's output tensor spec to implement correctly.
        return DetectionResult.Inconclusive("ML output decoding not yet implemented")
    }

    override fun close() {
        interpreter?.close()
    }
}

private object FileInputStreamCompat {
    fun mapEntireFile(fd: android.content.res.AssetFileDescriptor): MappedByteBuffer {
        fd.createInputStream().use { input ->
            return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
}
