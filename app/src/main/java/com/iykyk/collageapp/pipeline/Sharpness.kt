package com.iykyk.collageapp.pipeline

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Cheap, dependency-free sharpness estimate: variance of the Laplacian of a
 * downsized grayscale version of the crop. Higher = crisper / less motion blur.
 * No OpenCV needed -- this is a small manual convolution over a small crop
 * (we downsize first), so it's fast enough to run per detected face per frame.
 */
object Sharpness {

    fun laplacianVariance(bitmap: Bitmap, workingSize: Int = 96): Float {
        val w = min(workingSize, bitmap.width)
        val h = min(workingSize, bitmap.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        var sum = 0f
        var sumSq = 0f
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val lap = -4f * gray[idx] +
                    gray[idx - 1] + gray[idx + 1] +
                    gray[idx - w] + gray[idx + w]
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        return max(0f, (sumSq / count) - mean * mean)
    }

    /** Maps a raw variance value into a rough 0..1 score. Threshold picked empirically for portrait video. */
    fun normalizedScore(variance: Float): Float = (variance / 400f).coerceIn(0f, 1f)
}
