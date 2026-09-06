package com.iykyk.collageapp.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Aligns a detected face to a canonical 112x112 pose using an eye-based
 * similarity transform (rotate + scale + translate) before it goes into the
 * embedding model. MobileFaceNet/ArcFace-style models are trained on ALIGNED
 * faces with eyes at fixed positions -- feeding them a raw, unaligned crop
 * measurably hurts how well same-person vs different-person embeddings
 * separate.
 *
 * If eye landmarks aren't available, logs the reason and returns null,
 * allowing the caller to fall back to unaligned crop (better than crashing).
 */
object FaceAligner {

    private const val OUT_SIZE = 112
    private const val TAG = "FaceAligner"

    // Standard ArcFace 112x112 reference eye positions.
    private const val TARGET_LEFT_EYE_X = 38.2946f
    private const val TARGET_LEFT_EYE_Y = 51.6963f
    private const val TARGET_RIGHT_EYE_X = 73.5318f
    private const val TARGET_RIGHT_EYE_Y = 51.5014f

    /** Returns a 112x112 aligned face bitmap, or null if alignment fails (falls back to unaligned crop). */
    fun align(source: Bitmap, face: Face): Bitmap? {
        try {
            val leftEyeLm = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEyeLm = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

            // If either eye is missing, log and return null for fallback.
            if (leftEyeLm == null || rightEyeLm == null) {
                Log.d(TAG, "Eye landmarks missing (left=${leftEyeLm != null}, right=${rightEyeLm != null}); using fallback unaligned crop")
                return null
            }

            // ML Kit's LEFT_EYE is the subject's own left eye, which appears on
            // the image's right side. Normalize so "leftX/leftY" is whichever
            // point is actually on the left in image coordinates, matching the
            // ArcFace reference template's convention.
            val leftX: Float
            val leftY: Float
            val rightX: Float
            val rightY: Float

            if (leftEyeLm.x <= rightEyeLm.x) {
                leftX = leftEyeLm.x
                leftY = leftEyeLm.y
                rightX = rightEyeLm.x
                rightY = rightEyeLm.y
            } else {
                leftX = rightEyeLm.x
                leftY = rightEyeLm.y
                rightX = leftEyeLm.x
                rightY = leftEyeLm.y
            }

            val srcDx = rightX - leftX
            val srcDy = rightY - leftY
            val srcDist = sqrt(srcDx * srcDx + srcDy * srcDy)

            // Sanity check: if eyes are too close together, something's wrong.
            if (srcDist < 1e-3f) {
                Log.d(TAG, "Eye distance too small ($srcDist); using fallback unaligned crop")
                return null
            }

            val targetDx = TARGET_RIGHT_EYE_X - TARGET_LEFT_EYE_X
            val targetDy = TARGET_RIGHT_EYE_Y - TARGET_LEFT_EYE_Y
            val targetDist = sqrt(targetDx * targetDx + targetDy * targetDy)

            val scale = targetDist / srcDist
            val rotationDeg = Math.toDegrees(
                (atan2(targetDy, targetDx) - atan2(srcDy, srcDx)).toDouble()
            ).toFloat()

            val matrix = Matrix().apply {
                postTranslate(-leftX, -leftY)
                postScale(scale, scale)
                postRotate(rotationDeg)
                postTranslate(TARGET_LEFT_EYE_X, TARGET_LEFT_EYE_Y)
            }

            val out = Bitmap.createBitmap(OUT_SIZE, OUT_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            Log.d(
                TAG,
                "EYES src=(" +
                        "%.1f".format(leftX) + "," +
                        "%.1f".format(leftY) + ")-(" +
                        "%.1f".format(rightX) + "," +
                        "%.1f".format(rightY) + "), " +
                        "dist=" + "%.1f".format(srcDist) +
                        ", scale=" + "%.3f".format(scale) +
                        ", rotation=" + "%.1f".format(rotationDeg) + "°"
            )

            Log.d(TAG, "Face aligned successfully (scale=%.3f, rotation=%.1f°)".format(scale, rotationDeg))
            return out

        } catch (e: Exception) {
            Log.e(TAG, "Alignment failed with exception: ${e.message}; using fallback unaligned crop")
            return null
        }
    }
}