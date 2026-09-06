package com.iykyk.collageapp.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Pulls frames from the video at a fixed sample rate using MediaMetadataRetriever.
 * 30s clips at ~5 fps => ~150 frames, which is plenty of temporal resolution to
 * both catch every appearance and keep on-device processing time reasonable.
 *
 * Frames are decoded and handed to the caller one at a time (via [onFrame]) and
 * NOT retained afterwards -- the caller must copy out whatever it needs (crops,
 * embeddings, scores) before returning, otherwise memory blows up on longer clips.
 */
class FrameExtractor(private val context: Context) {

    data class Sample(val index: Int, val timestampMs: Long, val bitmap: Bitmap)

    fun extract(
        uri: Uri,
        samplesPerSecond: Int = 5,
        maxDimension: Int = 960,
        onFrame: (Sample) -> Unit,
        onProgress: (current: Int, total: Int) -> Unit
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val stepMs = (1000L / samplesPerSecond).coerceAtLeast(1L)
            val total = (durationMs / stepMs).toInt().coerceAtLeast(1)

            var t = 0L
            var idx = 0
            while (t < durationMs) {
                val frame = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val scaled = downscale(frame, maxDimension)
                    if (scaled !== frame) frame.recycle()
                    onFrame(Sample(idx, t, scaled))
                }
                idx++
                t += stepMs
                onProgress(idx, total)
            }
        } finally {
            retriever.release()
        }
    }

    private fun downscale(bmp: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxDimension) return bmp
        val scale = maxDimension.toFloat() / longest
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }
}
