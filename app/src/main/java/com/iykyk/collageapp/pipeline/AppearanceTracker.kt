package com.iykyk.collageapp.pipeline

import android.graphics.Rect

/**
 * Builds continuous appearance tracks from face detections.
 *
 * A Track represents one continuous visible segment.
 *
 * Identity recognition is handled later by Clusterer.
 */
class AppearanceTracker(
    private val iouThreshold: Float = 0.20f,
    private val embeddingThreshold: Float = 0.40f,

    // At 8 FPS, 4 frames = about 0.5 seconds.
    // Allows short detection gaps without immediately ending
    // a continuous appearance.
    private val maxGapFrames: Int = 4
) {

    private class ActiveTrack(
        val track: Track,
        var lastSeenFrame: Int,
        var lastBox: Rect
    )

    private data class Match(
        val trackIndex: Int,
        val observationIndex: Int,
        val score: Float
    )

    private val active = mutableListOf<ActiveTrack>()
    private val finished = mutableListOf<Track>()

    private var nextId = 0

    fun addFrameObservations(
        frameIndex: Int,
        observations: List<FaceObservation>
    ) {

        /*
         * No detections:
         *
         * Keep existing appearances alive for the allowed
         * detection gap.
         */
        if (observations.isEmpty()) {
            closeExpiredTracks(frameIndex)
            return
        }

        val matches = mutableListOf<Match>()

        /*
         * Compare active appearances with current detections.
         */
        for (trackIndex in active.indices) {

            val activeTrack = active[trackIndex]

            val gap =
                frameIndex - activeTrack.lastSeenFrame

            if (gap > maxGapFrames) {
                continue
            }

            for (observationIndex in observations.indices) {

                val observation =
                    observations[observationIndex]

                val overlap =
                    iou(
                        activeTrack.lastBox,
                        observation.box
                    )

                val embeddingSimilarity =
                    FaceEmbedder.cosineSimilarity(
                        activeTrack.track.meanEmbedding(),
                        observation.embedding
                    )

                /*
                 * If the face boxes overlap, spatial continuity
                 * is strong enough to allow a moderate embedding
                 * similarity.
                 *
                 * If the boxes do not overlap, require stronger
                 * identity similarity to bridge the movement.
                 */
                val validMatch =
                    if (overlap >= iouThreshold) {
                        embeddingSimilarity >= 0.35f
                    } else {
                        embeddingSimilarity >= embeddingThreshold
                    }

                if (!validMatch) {
                    continue
                }

                val matchScore =
                    if (overlap >= iouThreshold) {
                        (overlap * 0.60f) +
                                (embeddingSimilarity * 0.40f)
                    } else {
                        embeddingSimilarity
                    }

                matches.add(
                    Match(
                        trackIndex = trackIndex,
                        observationIndex = observationIndex,
                        score = matchScore
                    )
                )
            }
        }

        /*
         * Resolve matches globally.
         */
        matches.sortByDescending { it.score }

        val matchedTracks =
            mutableSetOf<Int>()

        val matchedObservations =
            mutableSetOf<Int>()

        for (match in matches) {

            if (match.trackIndex in matchedTracks) {
                continue
            }

            if (match.observationIndex in matchedObservations) {
                continue
            }

            val activeTrack =
                active[match.trackIndex]

            val observation =
                observations[match.observationIndex]

            activeTrack.track.observations.add(
                observation
            )

            activeTrack.lastBox =
                observation.box

            activeTrack.lastSeenFrame =
                frameIndex

            matchedTracks.add(
                match.trackIndex
            )

            matchedObservations.add(
                match.observationIndex
            )
        }

        /*
         * Every unmatched detection starts a new appearance.
         *
         * Extremely weak detections are ignored so that a brief
         * blurred/poor-quality frame does not create an appearance
         * by itself.
         */
        for (observationIndex in observations.indices) {

            if (
                observationIndex in matchedObservations
            ) {
                continue
            }

            val observation =
                observations[observationIndex]

            if (!isClearlyVisible(observation)) {
                continue
            }

            val track =
                Track(
                    id = nextId++,
                    observations =
                        mutableListOf(observation)
                )

            active.add(
                ActiveTrack(
                    track = track,
                    lastSeenFrame = frameIndex,
                    lastBox = observation.box
                )
            )
        }

        /*
         * End appearances that have disappeared for too long.
         */
        closeExpiredTracks(frameIndex)
    }

    /**
     * Determines whether a new detection is strong enough
     * to start a real appearance.
     */
    private fun isClearlyVisible(
        observation: FaceObservation
    ): Boolean {

        val faceArea =
            observation.box.width().toFloat() *
                    observation.box.height().toFloat()

        val frameArea =
            observation.frameWidth.toFloat() *
                    observation.frameHeight.toFloat()

        if (frameArea <= 0f) {
            return false
        }

        val faceRatio =
            faceArea / frameArea

        /*
         * The same minimum face size used by the final track
         * filtering is used here.
         */
        if (faceRatio < 0.04f) {
            return false
        }

        /*
         * A slightly relaxed quality threshold is used for
         * starting an appearance. The final filtering below
         * remains stricter.
         */
        return observation.qualityScore >= 0.50f
    }

    private fun closeExpiredTracks(
        frameIndex: Int
    ) {

        val stillActive =
            mutableListOf<ActiveTrack>()

        for (activeTrack in active) {

            if (
                frameIndex -
                activeTrack.lastSeenFrame >
                maxGapFrames
            ) {

                finished.add(
                    activeTrack.track
                )

            } else {

                stillActive.add(
                    activeTrack
                )
            }
        }

        active.clear()
        active.addAll(
            stillActive
        )
    }

    /**
     * Finish processing and return valid appearance tracks.
     */
    fun finish(): List<Track> {

        finished.addAll(
            active.map { it.track }
        )

        active.clear()

        return finished.filter { track ->

            if (track.observations.isEmpty()) {
                return@filter false
            }

            val bestObservation =
                track.bestObservation()

            val faceArea =
                bestObservation.box.width().toFloat() *
                        bestObservation.box.height().toFloat()

            val frameArea =
                bestObservation.frameWidth.toFloat() *
                        bestObservation.frameHeight.toFloat()

            val faceRatio =
                if (frameArea > 0f) {
                    faceArea / frameArea
                } else {
                    0f
                }

            if (track.observations.size >= 2) {

                bestObservation.qualityScore >= 0.55f &&
                        faceRatio >= 0.04f

            } else {

                bestObservation.qualityScore >= 0.60f &&
                        faceRatio >= 0.04f
            }
        }
    }

    private fun iou(
        a: Rect,
        b: Rect
    ): Float {

        val interLeft =
            maxOf(a.left, b.left)

        val interTop =
            maxOf(a.top, b.top)

        val interRight =
            minOf(a.right, b.right)

        val interBottom =
            minOf(a.bottom, b.bottom)

        if (
            interRight <= interLeft ||
            interBottom <= interTop
        ) {
            return 0f
        }

        val intersectionArea =
            (interRight - interLeft).toFloat() *
                    (interBottom - interTop).toFloat()

        val unionArea =
            a.width() * a.height() +
                    b.width() * b.height() -
                    intersectionArea

        return if (unionArea > 0f) {
            intersectionArea / unionArea
        } else {
            0f
        }
    }
}