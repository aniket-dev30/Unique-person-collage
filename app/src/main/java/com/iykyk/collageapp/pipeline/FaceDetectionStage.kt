package com.iykyk.collageapp.pipeline

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Wraps ML Kit's on-device face detector. Detection is Stage 1 of the required
 * 3-stage pipeline (detection -> embedding -> clustering).
 *
 * We deliberately do NOT use ML Kit's built-in cross-frame trackingId as the
 * identity signal -- the assignment requires an explicit embedding + clustering
 * step, so trackingId is left disabled and identity is decided later purely by
 * the embedding model + clustering stage.
 */
class FaceDetectionStage {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // smiling / eyes-open
            .setMinFaceSize(0.10f)
            .build()
    )

    /** Blocking call -- must be invoked from a background thread/coroutine. */
    fun detect(bitmap: Bitmap): List<Face> {
        val input = InputImage.fromBitmap(bitmap, 0)
        return try {
            Tasks.await(detector.process(input))
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun close() = detector.close()

    companion object {
        /** Clamp ML Kit's box (which can extend past the bitmap) to valid bitmap bounds. */
        fun clampBox(box: Rect, width: Int, height: Int): Rect = Rect(
            box.left.coerceIn(0, width - 1),
            box.top.coerceIn(0, height - 1),
            box.right.coerceIn(1, width),
            box.bottom.coerceIn(1, height)
        )
    }
}
