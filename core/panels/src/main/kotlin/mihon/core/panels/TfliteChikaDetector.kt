package mihon.core.panels

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import mihon.core.panels.chika.Letterbox
import mihon.core.panels.chika.YoloPanelDecoder
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

private const val MODEL_ASSET_PATH = "models/panel_detector.tflite"

/**
 * ML fallback detector for pages the classical [WhitespaceGutterPanelDetector] can't confidently
 * segment (bleed panels, splash pages, borderless/textured art). Decodes a YOLO-family
 * panel/text-balloon detector's output tensor using [YoloPanelDecoder] — ported verbatim from
 * batunii/chika (MPL-2.0, see [mihon.core.panels.chika]) — so this works with any compatible
 * model, not one specific set of trained weights.
 *
 * **No model asset is bundled here.** batunii/chika's own bundled model
 * (`manga_panel_detector_int8.tflite`) is an Ultralytics-exported YOLO26n model whose embedded
 * metadata declares it licensed under Ultralytics' AGPL-3.0 terms
 * (https://ultralytics.com/license) — a real conflict with this Apache-2.0 project's
 * distribution that needs an explicit decision (train/obtain a differently-licensed model,
 * purchase an Ultralytics Enterprise license, or drop the ML fallback), not something to route
 * around by quietly bundling the file anyway. Until a model is supplied at [MODEL_ASSET_PATH],
 * this always reports [DetectionResult.Inconclusive], so the pipeline safely falls through to
 * "no panels for this page" rather than fabricating detections.
 *
 * The inference path below assumes a float32 input/output model (the common case for a
 * custom-trained replacement). An int8-quantized model — like chika's own, per its metadata —
 * additionally needs its input/output quantization scale and zero-point applied, which isn't
 * implemented here since it's specific to whichever model ends up bundled.
 */
class TfliteChikaDetector(private val context: Context) : PanelDetector, Closeable {

    private val decoder = YoloPanelDecoder.default()
    private val interpreter: Interpreter? by lazy { loadInterpreter() }

    override suspend fun detect(bitmap: Bitmap): DetectionResult {
        val model = interpreter
            ?: return DetectionResult.Inconclusive("no panel-detection model asset bundled")

        val inputSize = decoder.inputSize
        val letterbox = Letterbox.fit(bitmap.width, bitmap.height, inputSize)
        val inputBitmap = letterboxBitmap(bitmap, letterbox, inputSize)

        try {
            val inputBuffer = bitmapToFloatBuffer(inputBitmap, inputSize)
            val outputShape = model.getOutputTensor(0).shape()
            val outputSize = outputShape.fold(1) { acc, d -> acc * d }
            val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())

            model.run(inputBuffer, outputBuffer)

            outputBuffer.rewind()
            val raw = FloatArray(outputSize)
            outputBuffer.asFloatBuffer().get(raw)

            val result = decoder.decode(raw, outputShape, letterbox, bitmap.width, bitmap.height)
            val boxes = result.panels.map { DetectedBox(rectOf(it), PanelKind.PANEL, confidence = 1f) } +
                result.bubbles.map { DetectedBox(rectOf(it), PanelKind.BALLOON, confidence = 1f) }

            return if (boxes.isEmpty()) {
                DetectionResult.Inconclusive("model produced no boxes above threshold")
            } else {
                DetectionResult.Confident(boxes)
            }
        } finally {
            if (inputBitmap !== bitmap) inputBitmap.recycle()
        }
    }

    private fun rectOf(panel: mihon.core.panels.chika.Panel) =
        android.graphics.RectF(panel.left, panel.top, panel.right, panel.bottom)

    private fun loadInterpreter(): Interpreter? {
        return try {
            context.assets.openFd(MODEL_ASSET_PATH).use { fd ->
                Interpreter(mapEntireFile(fd))
            }
        } catch (_: FileNotFoundException) {
            // Model asset isn't bundled — see class doc.
            null
        }
    }

    private fun mapEntireFile(fd: AssetFileDescriptor): MappedByteBuffer {
        fd.createInputStream().use { input ->
            return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /** Letterboxes [bitmap] into a square [inputSize]x[inputSize] canvas per [letterbox]'s geometry. */
    private fun letterboxBitmap(bitmap: Bitmap, letterbox: Letterbox, inputSize: Int): Bitmap {
        val output = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(114, 114, 114)) // YOLO's standard grey letterbox padding

        val matrix = Matrix().apply { setScale(letterbox.scale, letterbox.scale) }
        val scaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        canvas.drawBitmap(scaledBitmap, letterbox.padX.toFloat(), letterbox.padY.toFloat(), null)
        if (scaledBitmap !== bitmap) scaledBitmap.recycle()

        return output
    }

    /** RGB, normalized to `[0,1]`, NHWC float32 — Ultralytics' default export preprocessing. */
    private fun bitmapToFloatBuffer(bitmap: Bitmap, inputSize: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    override fun close() {
        interpreter?.close()
    }
}
