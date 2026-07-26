package mihon.core.panels

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import mihon.core.panels.chika.Letterbox
import mihon.core.panels.chika.YoloPanelDecoder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
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
 * The bundled model asset (`assets/models/panel_detector.tflite`) is chika's own
 * `manga_panel_detector_int8.tflite`, copied unmodified. Its own embedded metadata declares it
 * licensed under Ultralytics' AGPL-3.0 terms (https://ultralytics.com/license) — a different,
 * stricter license than chika's own MPL-2.0 code. **It is bundled here on the basis that this
 * build is for personal use and is not being distributed** — see `core/panels/NOTICE.md` before
 * ever shipping a release build or public fork with this asset included.
 *
 * The model is int8-quantized ("int8": true in its metadata); input/output tensors are
 * quantized/dequantized per their own TFLite-reported scale and zero-point below, so this isn't
 * hardcoded to one specific quantization scheme.
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
            val inputTensor = model.getInputTensor(0)
            val outputTensor = model.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val outputSize = outputShape.fold(1) { acc, d -> acc * d }

            val inputBuffer = buildInputBuffer(inputBitmap, inputSize, inputTensor)
            val outputBuffer = ByteBuffer.allocateDirect(outputSize * outputTensor.dataType().byteSize())
                .order(ByteOrder.nativeOrder())

            model.run(inputBuffer, outputBuffer)

            val raw = readOutput(outputBuffer, outputSize, outputTensor)
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

    /**
     * RGB pixels normalized to `[0,1]` (Ultralytics' standard preprocessing), written as
     * FLOAT32 or quantized to the input tensor's own INT8/UINT8 type + scale/zero-point.
     */
    private fun buildInputBuffer(bitmap: Bitmap, inputSize: Int, inputTensor: Tensor): ByteBuffer {
        val dataType = inputTensor.dataType()
        val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * dataType.byteSize())
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val quant = inputTensor.quantizationParams()
        val scale = quant.scale
        val zeroPoint = quant.zeroPoint

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            when (dataType) {
                DataType.FLOAT32 -> {
                    buffer.putFloat(r)
                    buffer.putFloat(g)
                    buffer.putFloat(b)
                }
                DataType.UINT8 -> {
                    buffer.put(quantize(r, scale, zeroPoint).coerceIn(0, 255).toByte())
                    buffer.put(quantize(g, scale, zeroPoint).coerceIn(0, 255).toByte())
                    buffer.put(quantize(b, scale, zeroPoint).coerceIn(0, 255).toByte())
                }
                DataType.INT8 -> {
                    buffer.put(quantize(r, scale, zeroPoint).coerceIn(-128, 127).toByte())
                    buffer.put(quantize(g, scale, zeroPoint).coerceIn(-128, 127).toByte())
                    buffer.put(quantize(b, scale, zeroPoint).coerceIn(-128, 127).toByte())
                }
                else -> error("Unsupported input tensor type: $dataType")
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun quantize(realValue: Float, scale: Float, zeroPoint: Int): Int {
        if (scale == 0f) return zeroPoint
        return Math.round(realValue / scale) + zeroPoint
    }

    private fun dequantize(quantizedValue: Int, scale: Float, zeroPoint: Int): Float {
        return (quantizedValue - zeroPoint) * scale
    }

    /** Reads the output tensor, dequantizing to real-valued floats if it's INT8/UINT8. */
    private fun readOutput(buffer: ByteBuffer, size: Int, outputTensor: Tensor): FloatArray {
        buffer.rewind()
        val dataType = outputTensor.dataType()
        val result = FloatArray(size)
        when (dataType) {
            DataType.FLOAT32 -> buffer.asFloatBuffer().get(result)
            DataType.UINT8, DataType.INT8 -> {
                val quant = outputTensor.quantizationParams()
                for (i in 0 until size) {
                    val raw = if (dataType == DataType.UINT8) buffer.get().toInt() and 0xFF else buffer.get().toInt()
                    result[i] = dequantize(raw, quant.scale, quant.zeroPoint)
                }
            }
            else -> error("Unsupported output tensor type: $dataType")
        }
        return result
    }

    override fun close() {
        interpreter?.close()
    }
}
