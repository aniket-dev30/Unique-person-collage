package com.iykyk.collageapp.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

/**
 * Scores a face observation for representative-shot quality.
 *
 * Factors:
 * - Frontality
 * - Sharpness
 * - Eyes open
 * - Smile
 * - Not clipped by the frame edge
 */
object QualityScorer {

    private const val W_FRONTAL = 0.30f
    private const val W_SHARP = 0.25f
    private const val W_EYES = 0.25f
    private const val W_SMILE = 0.20f

    private const val CLIPPED_PENALTY = 0.5f
    private const val EDGE_MARGIN_FRAC = 0.02f

    fun score(
        face: Face,
        frameWidth: Int,
        frameHeight: Int,
        sharpnessVariance: Float
    ): Float {

        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX

        val frontality =
            1f - (
                    ((abs(yaw) + abs(pitch)) / 2f) / 45f
                    ).coerceIn(0f, 1f)

        val leftEye =
            face.leftEyeOpenProbability ?: 0.5f

        val rightEye =
            face.rightEyeOpenProbability ?: 0.5f

        val eyesOpen =
            (leftEye + rightEye) / 2f

        val smile =
            face.smilingProbability ?: 0.5f

        val sharpness =
            Sharpness.normalizedScore(sharpnessVariance)

        var total =
            W_FRONTAL * frontality +
                    W_SHARP * sharpness +
                    W_EYES * eyesOpen +
                    W_SMILE * smile

        if (
            isClipped(
                face.boundingBox,
                frameWidth,
                frameHeight
            )
        ) {
            total *= CLIPPED_PENALTY
        }

        return total.coerceIn(0f, 1f)
    }

    private fun isClipped(
        box: Rect,
        frameWidth: Int,
        frameHeight: Int
    ): Boolean {

        val marginX =
            (frameWidth * EDGE_MARGIN_FRAC).toInt()

        val marginY =
            (frameHeight * EDGE_MARGIN_FRAC).toInt()

        return box.left <= marginX ||
                box.top <= marginY ||
                box.right >= frameWidth - marginX ||
                box.bottom >= frameHeight - marginY
    }

    /**
     * Creates a generous display crop around the target face.
     *
     * If another detected face is nearby, the crop is reduced in that
     * direction so the neighbouring face is not unnecessarily included.
     *
     * The crop is still guaranteed to contain the target face.
     */
    fun generousCrop(
        source: Bitmap,
        box: Rect,
        otherFaceBoxes: List<Rect> = emptyList(),
        expansion: Float = 1.6f
    ): Bitmap {

        val cx = box.centerX()
        val cy = box.centerY()

        val side =
            (
                    maxOf(box.width(), box.height()) * expansion
                    )
                .toInt()
                .coerceAtLeast(
                    maxOf(box.width(), box.height())
                )

        val half = side / 2

        var left = cx - half
        var top = cy - half
        var right = cx + half
        var bottom = cy + half

        /*
         * Keep the initial crop inside the source bitmap.
         */
        left = left.coerceIn(
            0,
            source.width - 1
        )

        top = top.coerceIn(
            0,
            source.height - 1
        )

        right = right.coerceIn(
            left + 1,
            source.width
        )

        bottom = bottom.coerceIn(
            top + 1,
            source.height
        )

        /*
         * Adjust the crop when another detected face is nearby.
         */
        for (otherBox in otherFaceBoxes) {

            val currentCrop =
                Rect(
                    left,
                    top,
                    right,
                    bottom
                )

            if (!Rect.intersects(currentCrop, otherBox)) {
                continue
            }

            /*
             * Don't try to remove a face that is actually the target.
             */
            if (otherBox == box) {
                continue
            }

            val otherCenterX =
                otherBox.centerX()

            val otherCenterY =
                otherBox.centerY()

            val dx =
                otherCenterX - cx

            val dy =
                otherCenterY - cy

            /*
             * Horizontal neighbour.
             */
            if (
                abs(dx) > abs(dy) &&
                otherBox.height() >
                box.height() * 0.35f
            ) {

                if (dx > 0) {

                    /*
                     * Other face is on the right.
                     */
                    right = minOf(
                        right,
                        otherBox.left
                    )

                } else {

                    /*
                     * Other face is on the left.
                     */
                    left = maxOf(
                        left,
                        otherBox.right
                    )
                }
            }

            /*
             * Vertical neighbour.
             */
            else if (
                otherBox.width() >
                box.width() * 0.35f
            ) {

                if (dy > 0) {

                    /*
                     * Other face is below.
                     */
                    bottom = minOf(
                        bottom,
                        otherBox.top
                    )

                } else {

                    /*
                     * Other face is above.
                     */
                    top = maxOf(
                        top,
                        otherBox.bottom
                    )
                }
            }
        }

        /*
         * The target face must ALWAYS remain completely inside
         * the crop.
         */
        left = minOf(
            left,
            box.left
        )

        top = minOf(
            top,
            box.top
        )

        right = maxOf(
            right,
            box.right
        )

        bottom = maxOf(
            bottom,
            box.bottom
        )

        /*
         * Final safety clamp.
         */
        left = left.coerceIn(
            0,
            source.width - 1
        )

        top = top.coerceIn(
            0,
            source.height - 1
        )

        right = right.coerceIn(
            left + 1,
            source.width
        )

        bottom = bottom.coerceIn(
            top + 1,
            source.height
        )

        return Bitmap.createBitmap(
            source,
            left,
            top,
            right - left,
            bottom - top
        )
    }

    /**
     * Tight crop used only for embedding.
     * This is NOT used for the final collage.
     */
    fun tightCropForEmbedding(
        source: Bitmap,
        box: Rect,
        margin: Float = 0.25f
    ): Bitmap {

        val mx =
            (box.width() * margin).toInt()

        val my =
            (box.height() * margin).toInt()

        val left =
            (box.left - mx).coerceIn(
                0,
                source.width - 1
            )

        val top =
            (box.top - my).coerceIn(
                0,
                source.height - 1
            )

        val right =
            (box.right + mx).coerceIn(
                left + 1,
                source.width
            )

        val bottom =
            (box.bottom + my).coerceIn(
                top + 1,
                source.height
            )

        return Bitmap.createBitmap(
            source,
            left,
            top,
            right - left,
            bottom - top
        )
    }
}