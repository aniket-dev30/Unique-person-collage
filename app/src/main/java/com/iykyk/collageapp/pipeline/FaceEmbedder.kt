package com.iykyk.collageapp.pipeline

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import android.util.Log
/**
 * Stage 2 of the pipeline: turns a face crop into a fixed-length embedding vector
 * using an on-device MobileFaceNet TFLite model.
 *
 * Model contract expected (document any deviation in README if you swap models):
 *  - input:  112x112x3, RGB, float32, normalized to [-1, 1]  ((pixel/127.5) - 1)
 *  - output: 1 x EMBEDDING_DIM float32 vector (not necessarily pre-normalized)
 *
 * Place the .tflite file at app/src/main/assets/mobilefacenet.tflite
 * (see README for where to obtain a MobileFaceNet TFLite export).
 */
class FaceEmbedder(context: Context, modelAssetName: String = "mobilefacenet.tflite") {

    private val interpreter: Interpreter
    private val inputSize = 112
    private val embeddingDim: Int

    init {
        val model = loadModelFile(context, modelAssetName)
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        Log.d("MODEL_CHECK", "INPUT shape=${interpreter.getInputTensor(0).shape().contentToString()}")
        Log.d("MODEL_CHECK", "INPUT type=${interpreter.getInputTensor(0).dataType()}")

        Log.d(
            "MODEL_CHECK",
            "OUTPUT shape=${interpreter.getOutputTensor(0).shape().contentToString()}"
        )
        Log.d(
            "MODEL_CHECK",
            "OUTPUT type=${interpreter.getOutputTensor(0).dataType()}"
        )
        embeddingDim = interpreter.getOutputTensor(0).shape().last()
    }

    private fun loadModelFile(context: Context, assetName: String): ByteBuffer {
        val afd = context.assets.openFd(assetName)
        val inputStream = afd.createInputStream()
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    /** Crop should already be roughly face-centered; this resizes to the model's input size. */
    fun embed(faceCrop: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(faceCrop, inputSize, inputSize, true)
        val input = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF)
            val g = ((p shr 8) and 0xFF)
            val b = (p and 0xFF)
            input.putFloat((r / 127.5f) - 1f)
            input.putFloat((g / 127.5f) - 1f)
            input.putFloat((b / 127.5f) - 1f)
        }
        if (resized !== faceCrop) resized.recycle()

        input.rewind()

        val output = Array(1) { FloatArray(embeddingDim) }
        interpreter.run(input, output)
        val raw = output[0]

        var norm = 0f
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE

        for (v in raw) {
            norm += v * v
            if (v < min) min = v
            if (v > max) max = v
        }

        norm = kotlin.math.sqrt(norm)

        Log.d(
            "EMBED_CHECK",
            "raw embedding: norm=%.4f min=%.4f max=%.4f"
                .format(norm, min, max)
        )

        return l2Normalize(output[0])
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm).coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / norm }
    }

    fun close() = interpreter.close()

    companion object {
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot // both are already L2-normalized, so dot product == cosine similarity
        }
    }
}
