package com.iykyk.collageapp.pipeline

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates the complete per-video processing pipeline.
 *
 * Pipeline:
 *
 * Video
 *   ↓
 * Frame extraction
 *   ↓
 * Face detection
 *   ↓
 * Face alignment
 *   ↓
 * Face embedding
 *   ↓
 * Quality scoring
 *   ↓
 * Continuous appearance tracking
 *   ↓
 * Identity clustering
 *   ↓
 * Best representative selection
 *   ↓
 * PersonResult list
 */
class VideoPipeline(
    private val context: Context
) {

    private val _progress =
        MutableStateFlow(
            PipelineProgress(
                PipelineProgress.Phase.EXTRACTING,
                0,
                1
            )
        )

    val progress: StateFlow<PipelineProgress> =
        _progress

    private val detector =
        FaceDetectionStage()

    private val embedder =
        FaceEmbedder(context)

    fun process(
        uri: Uri
    ): List<PersonResult> {

        val extractor =
            FrameExtractor(context)

        val tracker =
            AppearanceTracker()

        extractor.extract(
            uri = uri,

            /*
             * Keep 8 FPS.
             */
            samplesPerSecond = 8,

            onFrame = { sample ->

                /*
                 * Detect all faces in this sampled frame.
                 */
                val faces =
                    detector.detect(sample.bitmap)

                val observations =
                    faces.mapNotNull { face ->

                        /*
                         * Keep the face box inside the bitmap.
                         */
                        val box =
                            FaceDetectionStage.clampBox(
                                face.boundingBox,
                                sample.bitmap.width,
                                sample.bitmap.height
                            )

                        /*
                         * Ignore extremely small detections.
                         */
                        if (
                            box.width() < 20 ||
                            box.height() < 20
                        ) {
                            return@mapNotNull null
                        }

                        /*
                         * Save the other detected faces from this
                         * frame. This information is used later when
                         * selecting the representative image.
                         */
                        val otherFaceBoxes =
                            faces
                                .filter { it !== face }
                                .map {
                                    FaceDetectionStage.clampBox(
                                        it.boundingBox,
                                        sample.bitmap.width,
                                        sample.bitmap.height
                                    )
                                }

                        /*
                         * Align face before generating embedding.
                         */
                        val aligned =
                            FaceAligner.align(
                                sample.bitmap,
                                face
                            )

                        val embedding =
                            if (aligned != null) {

                                val result =
                                    embedder.embed(
                                        aligned
                                    )

                                aligned.recycle()

                                result

                            } else {

                                /*
                                 * Fallback when eye landmarks
                                 * are unavailable.
                                 */
                                val fallbackCrop =
                                    QualityScorer.tightCropForEmbedding(
                                        sample.bitmap,
                                        box
                                    )

                                val result =
                                    embedder.embed(
                                        fallbackCrop
                                    )

                                fallbackCrop.recycle()

                                result
                            }

                        /*
                         * Tight crop only for sharpness calculation.
                         */
                        val sharpnessCrop =
                            QualityScorer.tightCropForEmbedding(
                                sample.bitmap,
                                box,
                                margin = 0f
                            )

                        val sharpVariance =
                            Sharpness.laplacianVariance(
                                sharpnessCrop
                            )

                        sharpnessCrop.recycle()

                        /*
                         * Calculate normal representative quality.
                         */
                        val score =
                            QualityScorer.score(
                                face,
                                sample.bitmap.width,
                                sample.bitmap.height,
                                sharpVariance
                            )

                        /*
                         * Create the display crop.
                         *
                         * We pass the other detected faces so
                         * QualityScorer can avoid unnecessary
                         * neighbouring faces.
                         */
                        val displayCrop =
                            QualityScorer.generousCrop(
                                source = sample.bitmap,
                                box = box,
                                otherFaceBoxes = otherFaceBoxes
                            )

                        FaceObservation(
                            frameIndex = sample.index,
                            timestampMs = sample.timestampMs,
                            box = box,
                            otherFaceBoxes = otherFaceBoxes,
                            frameWidth = sample.bitmap.width,
                            frameHeight = sample.bitmap.height,
                            yaw = face.headEulerAngleY,
                            pitch = face.headEulerAngleX,
                            roll = face.headEulerAngleZ,
                            leftEyeOpen =
                                face.leftEyeOpenProbability,
                            rightEyeOpen =
                                face.rightEyeOpenProbability,
                            smiling =
                                face.smilingProbability,
                            qualityScore = score,
                            embedding = embedding,
                            displayCrop = displayCrop
                        )
                    }

                /*
                 * Add observations to the appearance tracker.
                 */
                tracker.addFrameObservations(
                    sample.index,
                    observations
                )

                sample.bitmap.recycle()
            },

            onProgress = { current, total ->

                _progress.value =
                    PipelineProgress(
                        PipelineProgress.Phase.DETECTING,
                        current,
                        total
                    )
            }
        )

        /*
         * Finish continuous appearance tracks.
         */
        val tracks =
            tracker.finish()

        _progress.value =
            PipelineProgress(
                PipelineProgress.Phase.CLUSTERING,
                0,
                1
            )

        /*
         * Cluster continuous appearances into unique people.
         */
        val clusters =
            Clusterer().cluster(tracks)

        _progress.value =
            PipelineProgress(
                PipelineProgress.Phase.RENDERING,
                0,
                clusters.size
            )

        /*
         * Create one result for every person.
         */
        val results =
            clusters.mapIndexed { index, clusterTracks ->

                /*
                 * Look at every observation belonging to this person.
                 *
                 * We intentionally do NOT simply choose the highest
                 * qualityScore.
                 *
                 * A solo frame gets a strong bonus because the
                 * assignment asks for a representative image of
                 * the individual person.
                 */
                val best =
                    clusterTracks
                        .flatMap { it.observations }
                        .maxBy { observation ->

                            /*
                             * Check whether another detected face
                             * is present in this frame.
                             */
                            val hasOtherFace =
                                observation.otherFaceBoxes.isNotEmpty()

                            /*
                             * Base quality.
                             */
                            var representativeScore =
                                observation.qualityScore

                            /*
                             * Strongly prefer a frame where this
                             * person is the only detected face.
                             *
                             * The bonus is intentionally large enough
                             * to prefer a good solo frame over a
                             * slightly better-looking shared frame.
                             */
                            if (!hasOtherFace) {
                                representativeScore += 0.20f
                            } else {
                                representativeScore -= 0.05f
                            }

                            /*
                             * Slight preference for larger faces.
                             */
                            val faceArea =
                                observation.box.width().toFloat() *
                                        observation.box.height().toFloat()

                            val frameArea =
                                observation.frameWidth.toFloat() *
                                        observation.frameHeight.toFloat()

                            val faceRatio =
                                if (frameArea > 0f) {
                                    faceArea / frameArea
                                } else {
                                    0f
                                }

                            representativeScore +=
                                faceRatio * 0.20f

                            representativeScore
                        }

                _progress.value =
                    PipelineProgress(
                        PipelineProgress.Phase.RENDERING,
                        index + 1,
                        clusters.size
                    )

                PersonResult(
                    personIndex = index,
                    appearanceCount = countContinuousAppearances(clusterTracks),
                    representative =
                        best.displayCrop,
                    representativeScore =
                        best.qualityScore,
                    trackIds =
                        clusterTracks.map { it.id }
                )
            }

        _progress.value =
            PipelineProgress(
                PipelineProgress.Phase.DONE,
                1,
                1
            )

        return results
    }
    private fun countContinuousAppearances(
        clusterTracks: List<Track>
    ): Int {

        if (clusterTracks.isEmpty()) {
            return 0
        }

        /*
         * Each Track is initially a continuous appearance.
         *
         * Sometimes the tracker can split one real appearance into
         * multiple tracks because of a temporary detection/matching
         * failure. We merge only tracks that are:
         *
         * 1. close together in time
         * 2. strongly similar in identity
         *
         * This is done AFTER identity clustering, so it cannot merge
         * two different people.
         */

        val sortedTracks =
            clusterTracks.sortedBy { track ->
                track.observations.minOf { it.timestampMs }
            }

        var appearanceCount = 0

        var previousTrack: Track? = null

        for (track in sortedTracks) {

            if (track.observations.isEmpty()) {
                continue
            }

            if (previousTrack == null) {

                appearanceCount++
                previousTrack = track
                continue
            }

            val previousEnd =
                previousTrack.observations
                    .maxOf { it.timestampMs }

            val currentStart =
                track.observations
                    .minOf { it.timestampMs }

            val timeGap =
                currentStart - previousEnd

            val embeddingSimilarity =
                FaceEmbedder.cosineSimilarity(
                    previousTrack.meanEmbedding(),
                    track.meanEmbedding()
                )

            /*
             * At 8 FPS, observations are roughly 125 ms apart.
             *
             * A gap of up to 250 ms can be caused by a short
             * detection failure. Require strong identity similarity
             * before treating it as the same continuous appearance.
             */
            val sameAppearance =
                timeGap <= 250L &&
                        embeddingSimilarity >= 0.65f

            if (!sameAppearance) {
                appearanceCount++
            }

            previousTrack = track
        }

        return appearanceCount
    }
    /**
     * Calculates Intersection over Union (IoU) between
     * two bounding boxes.
     */
    private fun calculateIoU(
        a: Rect,
        b: Rect
    ): Float {

        val left =
            maxOf(
                a.left,
                b.left
            )

        val top =
            maxOf(
                a.top,
                b.top
            )

        val right =
            minOf(
                a.right,
                b.right
            )

        val bottom =
            minOf(
                a.bottom,
                b.bottom
            )

        if (
            right <= left ||
            bottom <= top
        ) {
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

    fun close() {
        detector.close()
        embedder.close()
    }
}