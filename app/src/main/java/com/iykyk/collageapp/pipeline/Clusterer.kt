package com.iykyk.collageapp.pipeline

class Clusterer(
    private val similarityThreshold: Float = 0.65f,
    private val rescueThreshold: Float = 0.52f,
    private val rescueMargin: Float = 0.03f
) {

    private data class PersonCluster(
        val tracks: MutableList<Track>
    )

    fun cluster(tracks: List<Track>): List<List<Track>> {

        if (tracks.isEmpty()) return emptyList()

        /*
         * PASS 1
         *
         * Safe baseline clustering.
         */
        val clusters = mutableListOf<PersonCluster>()

        for (track in tracks) {

            val embedding = track.meanEmbedding()

            var bestCluster: PersonCluster? = null
            var bestSimilarity = -1f

            for (cluster in clusters) {

                val similarity = cluster.tracks.maxOf { existingTrack ->
                    FaceEmbedder.cosineSimilarity(
                        embedding,
                        existingTrack.meanEmbedding()
                    )
                }

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestCluster = cluster
                }
            }

            if (
                bestCluster != null &&
                bestSimilarity >= similarityThreshold
            ) {
                bestCluster.tracks.add(track)
            } else {
                clusters.add(
                    PersonCluster(
                        tracks = mutableListOf(track)
                    )
                )
            }
        }

        /*
         * PASS 2
         *
         * Rescue singleton tracks.
         *
         * IMPORTANT:
         * Compare the singleton against individual tracks inside
         * each candidate person.
         *
         * We only use a candidate track when the two tracks do not
         * overlap in time.
         */
        val singletonClusters =
            clusters.filter { it.tracks.size == 1 }.toList()

        for (singleton in singletonClusters) {

            if (!clusters.contains(singleton)) continue

            val singletonTrack =
                singleton.tracks.first()

            val singletonEmbedding =
                singletonTrack.meanEmbedding()

            var bestCluster: PersonCluster? = null
            var bestSimilarity = -1f
            var secondBestSimilarity = -1f

            for (candidate in clusters) {

                if (candidate === singleton) continue

                /*
                 * Find the best NON-OVERLAPPING track inside this
                 * candidate person.
                 */
                val candidateSimilarities =
                    candidate.tracks
                        .filter { existingTrack ->
                            !tracksOverlapInTime(
                                singletonTrack,
                                existingTrack
                            )
                        }
                        .map { existingTrack ->
                            FaceEmbedder.cosineSimilarity(
                                singletonEmbedding,
                                existingTrack.meanEmbedding()
                            )
                        }

                if (candidateSimilarities.isEmpty()) {
                    continue
                }

                val similarity =
                    candidateSimilarities.maxOrNull() ?: continue

                if (similarity > bestSimilarity) {
                    secondBestSimilarity = bestSimilarity
                    bestSimilarity = similarity
                    bestCluster = candidate
                } else if (similarity > secondBestSimilarity) {
                    secondBestSimilarity = similarity
                }
            }

            val strongEnough =
                bestSimilarity >= rescueThreshold

            val clearlyBest =
                bestSimilarity - secondBestSimilarity >= rescueMargin

            if (
                bestCluster != null &&
                strongEnough &&
                clearlyBest
            ) {
                bestCluster.tracks.add(singletonTrack)
                clusters.remove(singleton)
            }
        }
        return clusters
            .sortedByDescending { it.tracks.size }
            .map { it.tracks }
    }

    private fun tracksOverlapInTime(
        a: Track,
        b: Track
    ): Boolean {

        if (
            a.observations.isEmpty() ||
            b.observations.isEmpty()
        ) {
            return false
        }

        val aStart =
            a.observations.minOf { it.timestampMs }

        val aEnd =
            a.observations.maxOf { it.timestampMs }

        val bStart =
            b.observations.minOf { it.timestampMs }

        val bEnd =
            b.observations.maxOf { it.timestampMs }

        return aStart <= bEnd && bStart <= aEnd
    }
}