package com.iykyk.collageapp.pipeline

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * One face detection in one sampled frame, with the attributes needed for
 * both tracking (box) and quality scoring (pose / eyes / smile / sharpness).
 */
data class FaceObservation(
    val frameIndex: Int,
    val timestampMs: Long,
    val box: Rect,// face box in the *sampled frame's* coordinate space
    val otherFaceBoxes: List<Rect> = emptyList(),
    val frameWidth: Int,
    val frameHeight: Int,
    val yaw: Float,              // headEulerAngleY
    val pitch: Float,            // headEulerAngleX
    val roll: Float,             // headEulerAngleZ
    val leftEyeOpen: Float?,     // null if classification unavailable
    val rightEyeOpen: Float?,
    val smiling: Float?,
    val qualityScore: Float,     // computed by QualityScorer
    val embedding: FloatArray,   // L2-normalized embedding for this observation
    val displayCrop: Bitmap      // generous crop (NOT tight to bbox) used for the collage tile
)

/**
 * A "track" = one continuous appearance of a face, built by linking
 * FaceObservations across consecutive sampled frames via IoU.
 * This is the unit that appearance-counting is defined over.
 */
data class Track(
    val id: Int,
    val observations: MutableList<FaceObservation> = mutableListOf()
) {
    /** Mean of L2-normalized per-frame embeddings, re-normalized. Represents this track/appearance. */
    fun meanEmbedding(): FloatArray {
        if (observations.isEmpty()) return FloatArray(0)

        val dimension = observations.first().embedding.size
        val sum = FloatArray(dimension)

        var totalWeight = 0f

        for (observation in observations) {
            // Better observations have more influence on the identity.
            val weight = observation.qualityScore.coerceIn(0.1f, 1f)

            for (i in 0 until dimension) {
                sum[i] += observation.embedding[i] * weight
            }

            totalWeight += weight
        }

        if (totalWeight <= 0f) {
            return observations.first().embedding
        }

        for (i in sum.indices) {
            sum[i] /= totalWeight
        }

        // L2 normalize
        var norm = 0f

        for (value in sum) {
            norm += value * value
        }

        norm = kotlin.math.sqrt(norm).coerceAtLeast(1e-6f)

        for (i in sum.indices) {
            sum[i] /= norm
        }

        return sum
    }

    /** The single best-quality observation in this track (candidate representative shot). */
    fun bestObservation(): FaceObservation {
        return observations.maxBy { observation ->

            var maxOverlap = 0f

            for (otherBox in observation.otherFaceBoxes) {
                val overlap = calculateIoU(
                    observation.box,
                    otherBox
                )

                maxOverlap = maxOf(maxOverlap, overlap)
            }

            observation.qualityScore - (maxOverlap * 0.30f)
        }
    }

    private fun calculateIoU(
        a: Rect,
        b: Rect
    ): Float {

        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)

        if (right <= left || bottom <= top) {
            return 0f
        }

        val intersection =
            (right - left).toFloat() *
                    (bottom - top).toFloat()

        val union =
            a.width() * a.height() +
                    b.width() * b.height() -
                    intersection

        return if (union > 0f) {
            intersection / union
        } else {
            0f
        }
    }
}
/** Final result: one distinct person detected in the video. */
data class PersonResult(
    val personIndex: Int,
    val appearanceCount: Int,
    val representative: Bitmap,
    val representativeScore: Float,
    val trackIds: List<Int>
)

/** Progress reported up to the UI while a video is processed. */
data class PipelineProgress(
    val phase: Phase,
    val current: Int,
    val total: Int
) {
    enum class Phase { EXTRACTING, DETECTING, CLUSTERING, RENDERING, DONE }
}
