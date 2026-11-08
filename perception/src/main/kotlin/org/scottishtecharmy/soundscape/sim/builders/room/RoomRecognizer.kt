package org.scottishtecharmy.soundscape.sim.builders.room

import kotlinx.serialization.Serializable
import org.scottishtecharmy.soundscape.sim.eval.SimEval
import org.scottishtecharmy.soundscape.sim.geo.Angles
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.Geometry2D
import org.scottishtecharmy.soundscape.sim.geo.Ray2D
import org.scottishtecharmy.soundscape.sim.model.GeoAnchor
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Embedding vector for a room keyframe tagged with its world compass bearing.
 */
@Serializable
data class FrameEmbedding(
    val frameId: String,
    val bearingDegrees: Double,
    val vector: List<Double>
)

/**
 * Compact bearing & position record of a localized room landmark for revisit matching and re-anchoring.
 */
@Serializable
data class RoomLandmarkBearing(
    val category: SimCategory,
    val bearingDegrees: Double,
    val distanceMeters: Double,
    val position: List<Double>,
    val label: String? = null
)

/**
 * Compact, serializable fingerprint of a scanned indoor room combining:
 * - Normalized OCR strings (`normalizedTexts`) and extracted room-number/alphanumeric identifiers (`identifierTokens`),
 * - 3-5 keyframe embeddings (`frameEmbeddings`) from `Embedder`,
 * - Detected landmark bearings and 3D positions (`landmarks`).
 */
@Serializable
data class RoomFingerprint(
    val roomId: String,
    val roomName: String? = null,
    val geoAnchor: GeoAnchor? = null,
    val normalizedTexts: List<String> = emptyList(),
    val identifierTokens: List<String> = emptyList(),
    val frameEmbeddings: List<FrameEmbedding> = emptyList(),
    val landmarks: List<RoomLandmarkBearing> = emptyList()
)

/**
 * Outcome of comparing a short 2-3 frame revisit scan against stored [RoomFingerprint]s.
 */
sealed class RecognitionResult {
    /**
     * Positive room match with recovered yaw offset and estimated standing position relative to the stored room origin.
     *
     * @property roomId Matched room ID.
     * @property roomName Optional human-readable room name (e.g. `"Room 214"`).
     * @property confidence Overall match score in `[0.0, 1.0]`.
     * @property yawOffsetDegrees Estimated yaw offset (`queryBearing - storedBearing` in `(-180, 180]`) in degrees.
     * @property estimatedStandingPositionEnu Estimated user standing position `(east, north, up=0)` in the stored room frame.
     */
    data class Matched(
        val roomId: String,
        val roomName: String?,
        val confidence: Double,
        val yawOffsetDegrees: Double,
        val estimatedStandingPositionEnu: EnuPoint
    ) : RecognitionResult()

    /**
     * Clean abstention when the scanned room is new, ambiguous, or contradicts stored room numbers.
     */
    data class Abstained(
        val reason: String,
        val bestCandidateRoomId: String? = null,
        val bestCandidateScore: Double = 0.0
    ) : RecognitionResult()
}

/**
 * Recognizes revisited rooms from a short 2-3 frame scan (or a full [RoomFingerprint])
 * against a library of stored [RoomFingerprint]s, with strict abstention on near-duplicate
 * room numbers (Decision D-09) and unknown layouts.
 */
class RoomRecognizer(
    val minMatchConfidence: Double = 0.75
) {
    /**
     * Matches [query] against [storedLibrary].
     */
    fun recognize(
        query: RoomFingerprint,
        storedLibrary: List<RoomFingerprint>
    ): RecognitionResult {
        if (storedLibrary.isEmpty()) {
            return RecognitionResult.Abstained("Stored room library is empty")
        }

        var bestMatch: RecognitionResult.Matched? = null
        var bestRejectedId: String? = null
        var bestRejectedScore = 0.0
        var bestRejectionReason = "No candidate exceeded minMatchConfidence ($minMatchConfidence)"

        for (candidate in storedLibrary) {
            val eval = evaluateCandidate(query, candidate)
            if (eval.vetoedByIdentifierMismatch) {
                if (eval.rawScore > bestRejectedScore) {
                    bestRejectedScore = eval.rawScore
                    bestRejectedId = candidate.roomId
                    bestRejectionReason = "Discriminative room identifier mismatch: query=${query.identifierTokens} vs stored=${candidate.identifierTokens}"
                }
                continue
            }

            if (eval.rawScore >= minMatchConfidence) {
                if (bestMatch == null || eval.rawScore > bestMatch.confidence) {
                    bestMatch = RecognitionResult.Matched(
                        roomId = candidate.roomId,
                        roomName = candidate.roomName,
                        confidence = eval.rawScore,
                        yawOffsetDegrees = eval.yawOffsetDegrees,
                        estimatedStandingPositionEnu = eval.standingPositionEnu
                    )
                }
            } else if (eval.rawScore > bestRejectedScore) {
                bestRejectedScore = eval.rawScore
                bestRejectedId = candidate.roomId
            }
        }

        return bestMatch ?: RecognitionResult.Abstained(
            reason = bestRejectionReason,
            bestCandidateRoomId = bestRejectedId,
            bestCandidateScore = bestRejectedScore
        )
    }

    private data class CandidateEval(
        val rawScore: Double,
        val yawOffsetDegrees: Double,
        val standingPositionEnu: EnuPoint,
        val vetoedByIdentifierMismatch: Boolean
    )

    private fun evaluateCandidate(
        query: RoomFingerprint,
        stored: RoomFingerprint
    ): CandidateEval {
        // 1. Hard discriminative check on room/door numbers (Decision D-09):
        // If both scans read distinct alphanumeric/numeric tokens (e.g. "214" vs "216"), veto immediately.
        val qTokens = query.identifierTokens.ifEmpty { extractIdentifierTokens(query.normalizedTexts) }.toSet()
        val sTokens = stored.identifierTokens.ifEmpty { extractIdentifierTokens(stored.normalizedTexts) }.toSet()
        if (qTokens.isNotEmpty() && sTokens.isNotEmpty() && qTokens.intersect(sTokens).isEmpty()) {
            return CandidateEval(
                rawScore = 0.15,
                yawOffsetDegrees = 0.0,
                standingPositionEnu = EnuPoint(0.0, 0.0, 0.0),
                vetoedByIdentifierMismatch = true
            )
        }

        // 2. Text similarity score in [0, 1]
        val textSim = computeTextSimilarity(query.normalizedTexts, stored.normalizedTexts, qTokens, sTokens)

        // 3. Search over yaw offset (queryBearing - storedBearing) in 2-degree steps, then refine
        var bestYawOffset = 0.0
        var bestCombinedAngularScore = -1.0
        var bestLandmarkSim = 0.0
        var bestEmbeddingSim = 0.0

        for (stepDeg in 0 until 360 step 2) {
            val candidateOffset = stepDeg.toDouble()
            val lSim = scoreLandmarkAlignment(query.landmarks, stored.landmarks, candidateOffset)
            val eSim = scoreEmbeddingAlignment(query.frameEmbeddings, stored.frameEmbeddings, candidateOffset)
            val combined = if (query.frameEmbeddings.isNotEmpty() && stored.frameEmbeddings.isNotEmpty()) {
                0.65 * lSim + 0.35 * eSim
            } else {
                lSim
            }
            if (combined > bestCombinedAngularScore) {
                bestCombinedAngularScore = combined
                bestYawOffset = candidateOffset
                bestLandmarkSim = lSim
                bestEmbeddingSim = eSim
            }
        }

        // Refine yaw offset using circular mean of matched static architectural landmarks
        val refinedYaw = refineYawOffsetFromStaticLandmarks(
            queryLandmarks = query.landmarks,
            storedLandmarks = stored.landmarks,
            initialYawOffset = bestYawOffset
        )

        val standingPos = estimateStandingPosition(
            queryLandmarks = query.landmarks,
            storedLandmarks = stored.landmarks,
            yawOffsetDegrees = refinedYaw
        )

        // Combine text, structural landmarks, and visual embeddings
        val hasTextBoth = query.normalizedTexts.isNotEmpty() && stored.normalizedTexts.isNotEmpty()
        val hasEmbedBoth = query.frameEmbeddings.isNotEmpty() && stored.frameEmbeddings.isNotEmpty()

        val rawCombined = when {
            hasTextBoth && hasEmbedBoth -> 0.35 * textSim + 0.45 * bestLandmarkSim + 0.20 * bestEmbeddingSim
            hasTextBoth -> 0.40 * textSim + 0.60 * bestLandmarkSim
            hasEmbedBoth -> 0.45 * bestLandmarkSim + 0.55 * bestEmbeddingSim
            else -> bestLandmarkSim
        }
        val appearanceGate = if (hasEmbedBoth && !hasTextBoth && bestEmbeddingSim < 0.72) {
            0.40 + 0.60 * (bestEmbeddingSim / 0.72).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        val totalScore = (rawCombined * appearanceGate).coerceIn(0.0, 1.0)

        return CandidateEval(
            rawScore = totalScore,
            yawOffsetDegrees = Angles.angleDifference(0.0, refinedYaw),
            standingPositionEnu = standingPos,
            vetoedByIdentifierMismatch = false
        )
    }

    private fun scoreLandmarkAlignment(
        queryLandmarks: List<RoomLandmarkBearing>,
        storedLandmarks: List<RoomLandmarkBearing>,
        yawOffsetDegrees: Double
    ): Double {
        if (queryLandmarks.isEmpty() || storedLandmarks.isEmpty()) return 0.0

        var weightedSum = 0.0
        var totalWeight = 0.0

        for (q in queryLandmarks) {
            val weight = categoryStabilityWeight(q.category)
            val correctedQueryBearing = Angles.normalizeBearing(q.bearingDegrees - yawOffsetDegrees)
            val sameCatStored = storedLandmarks.filter { it.category == q.category }
            if (sameCatStored.isEmpty()) {
                // Unmatched category penalty for static architectural landmarks
                if (weight >= 1.0) {
                    totalWeight += weight * 0.6
                }
                continue
            }

            var bestPairScore = 0.0
            for (s in sameCatStored) {
                val angDiff = Angles.absAngleDifference(correctedQueryBearing, s.bearingDegrees)
                val distDiff = abs(q.distanceMeters - s.distanceMeters)
                // Gaussian angular kernel (sigma = 12 deg) * distance kernel (sigma = 1.5 m)
                val angScore = exp(-(angDiff * angDiff) / (2.0 * 12.0 * 12.0))
                val distScore = exp(-(distDiff * distDiff) / (2.0 * 1.5 * 1.5))
                val pairScore = angScore * (0.7 + 0.3 * distScore)
                if (pairScore > bestPairScore) {
                    bestPairScore = pairScore
                }
            }
            weightedSum += weight * bestPairScore
            totalWeight += weight
        }

        val storedStaticCategories = storedLandmarks.map { it.category }.filter { categoryStabilityWeight(it) >= 1.0 }.toSet()
        val queryCategories = queryLandmarks.map { it.category }.toSet()
        val allStoredCategories = storedLandmarks.map { it.category }.toSet()
        val unionCats = queryCategories.union(allStoredCategories)
        val catOverlap = if (storedStaticCategories.isNotEmpty() && queryLandmarks.size >= 3) {
            storedStaticCategories.intersect(queryCategories).size.toDouble() / storedStaticCategories.size.toDouble()
        } else if (unionCats.isNotEmpty()) {
            queryCategories.intersect(allStoredCategories).size.toDouble() / unionCats.size.toDouble()
        } else 1.0

        val countFactor = (queryLandmarks.size.toDouble() / 2.0).coerceIn(0.55, 1.0)
        val originScore = if (totalWeight > 0.0) {
            (weightedSum / totalWeight) * (0.5 + 0.5 * catOverlap) * countFactor
        } else 0.0

        val constellationScore = scoreConstellationAlignment(queryLandmarks, storedLandmarks, yawOffsetDegrees) * countFactor
        return maxOf(originScore, constellationScore)
    }

    private fun scoreConstellationAlignment(
        queryLandmarks: List<RoomLandmarkBearing>,
        storedLandmarks: List<RoomLandmarkBearing>,
        yawOffsetDegrees: Double
    ): Double {
        val validQ = queryLandmarks.filter { it.position.size >= 3 }
        val validS = storedLandmarks.filter { it.position.size >= 3 }
        if (validQ.isEmpty() || validS.isEmpty()) return 0.0

        val rad = -yawOffsetDegrees * Angles.DEG_TO_RAD
        val cosA = cos(rad)
        val sinA = sin(rad)

        // Un-rotate clockwise bearing rotation: rotE = e * cos(rad) + n * sin(rad), rotN = -e * sin(rad) + n * cos(rad)
        val qRot = validQ.map { q ->
            val qe = q.position[0]
            val qn = q.position[2]
            val re = qe * cosA + qn * sinA
            val rn = -qe * sinA + qn * cosA
            EnuPoint(re, rn, 0.0)
        }
        val sPts = validS.map { s -> EnuPoint(s.position[0], s.position[2], 0.0) }

        var forwardWeightedSum = 0.0
        var forwardWeight = 0.0
        for (i in validQ.indices) {
            val q = validQ[i]
            val qp = qRot[i]
            val weight = categoryStabilityWeight(q.category).coerceAtLeast(0.4)
            val sameCatIndices = validS.indices.filter { validS[it].category == q.category }
            if (sameCatIndices.isEmpty()) {
                forwardWeight += weight * 0.8
                continue
            }
            var bestPair = 0.0
            for (j in sameCatIndices) {
                val sp = sPts[j]
                val d = qp.distanceTo2D(sp)
                val score = exp(-(d * d) / (2.0 * 1.6 * 1.6))
                if (score > bestPair) bestPair = score
            }
            forwardWeightedSum += weight * bestPair
            forwardWeight += weight
        }

        var backwardWeightedSum = 0.0
        var backwardWeight = 0.0
        for (j in validS.indices) {
            val s = validS[j]
            val sp = sPts[j]
            val weight = categoryStabilityWeight(s.category).coerceAtLeast(0.4)
            val sameCatIndices = validQ.indices.filter { validQ[it].category == s.category }
            if (sameCatIndices.isEmpty()) {
                backwardWeight += weight * 0.6
                continue
            }
            var bestPair = 0.0
            for (i in sameCatIndices) {
                val qp = qRot[i]
                val d = sp.distanceTo2D(qp)
                val score = exp(-(d * d) / (2.0 * 1.8 * 1.8))
                if (score > bestPair) bestPair = score
            }
            backwardWeightedSum += weight * bestPair
            backwardWeight += weight
        }

        val forwardScore = if (forwardWeight > 0.0) forwardWeightedSum / forwardWeight else 0.0
        val backwardScore = if (backwardWeight > 0.0) backwardWeightedSum / backwardWeight else 0.0
        val biScore = 0.65 * forwardScore + 0.35 * backwardScore

        val qCats = validQ.map { it.category }.toSet()
        val sCats = validS.map { it.category }.toSet()
        val unionSize = qCats.union(sCats).size
        val jaccard = if (unionSize > 0) qCats.intersect(sCats).size.toDouble() / unionSize.toDouble() else 0.0
        return biScore * (0.40 + 0.60 * jaccard)
    }

    private fun scoreEmbeddingAlignment(
        queryEmbeddings: List<FrameEmbedding>,
        storedEmbeddings: List<FrameEmbedding>,
        yawOffsetDegrees: Double
    ): Double {
        if (queryEmbeddings.isEmpty() || storedEmbeddings.isEmpty()) return 0.0
        var sumSim = 0.0
        var count = 0
        for (q in queryEmbeddings) {
            val correctedBearing = Angles.normalizeBearing(q.bearingDegrees - yawOffsetDegrees)
            val nearestStored = storedEmbeddings.minByOrNull {
                Angles.absAngleDifference(correctedBearing, it.bearingDegrees)
            } ?: continue
            val cosSim = cosineSimilarity(q.vector, nearestStored.vector).coerceIn(0.0, 1.0)
            val sharpened = cosSim * cosSim * cosSim
            sumSim += sharpened
            count++
        }
        val frameAlignedSim = if (count > 0) sumSim / count else 0.0

        // Also compute pooled mean-vector cosine similarity across the room scan
        val dim = minOf(
            queryEmbeddings.first().vector.size,
            storedEmbeddings.first().vector.size
        )
        if (dim == 0) return frameAlignedSim
        val qMean = DoubleArray(dim)
        val sMean = DoubleArray(dim)
        for (q in queryEmbeddings) {
            for (i in 0 until minOf(dim, q.vector.size)) qMean[i] += q.vector[i]
        }
        for (s in storedEmbeddings) {
            for (i in 0 until minOf(dim, s.vector.size)) sMean[i] += s.vector[i]
        }
        val pooledCos = cosineSimilarity(qMean.toList(), sMean.toList()).coerceIn(0.0, 1.0)
        val pooledSharpened = pooledCos * pooledCos * pooledCos * pooledCos
        return 0.45 * frameAlignedSim + 0.55 * pooledSharpened
    }

    private fun refineYawOffsetFromStaticLandmarks(
        queryLandmarks: List<RoomLandmarkBearing>,
        storedLandmarks: List<RoomLandmarkBearing>,
        initialYawOffset: Double
    ): Double {
        val diffs = mutableListOf<Double>()
        for (q in queryLandmarks) {
            if (categoryStabilityWeight(q.category) < 0.8) continue // ignore movable chairs when refining yaw
            val corrected = Angles.normalizeBearing(q.bearingDegrees - initialYawOffset)
            val bestStored = storedLandmarks
                .filter { it.category == q.category }
                .minByOrNull { Angles.absAngleDifference(corrected, it.bearingDegrees) }
                ?: continue
            if (Angles.absAngleDifference(corrected, bestStored.bearingDegrees) <= 14.0) {
                // Offset = queryBearing - storedBearing
                val offset = Angles.normalizeBearing(q.bearingDegrees - bestStored.bearingDegrees)
                diffs.add(offset)
            }
        }
        if (diffs.isEmpty()) return initialYawOffset
        val refined = Angles.circularMeanDegrees(diffs)
        return if (Angles.absAngleDifference(refined, initialYawOffset) <= 8.0) refined else initialYawOffset
    }

    private fun estimateStandingPosition(
        queryLandmarks: List<RoomLandmarkBearing>,
        storedLandmarks: List<RoomLandmarkBearing>,
        yawOffsetDegrees: Double
    ): EnuPoint {
        val rays = mutableListOf<Ray2D>()
        val directEstimates = mutableListOf<EnuPoint>()

        for (q in queryLandmarks) {
            if (categoryStabilityWeight(q.category) < 0.8) continue
            val trueBearingFromUser = Angles.normalizeBearing(q.bearingDegrees - yawOffsetDegrees)
            val bestStored = storedLandmarks
                .filter { it.category == q.category }
                .minByOrNull { Angles.absAngleDifference(trueBearingFromUser, it.bearingDegrees) }
                ?: continue
            if (Angles.absAngleDifference(trueBearingFromUser, bestStored.bearingDegrees) <= 25.0 &&
                bestStored.position.size >= 3
            ) {
                val landmarkEnu = EnuPoint.fromPositionList(bestStored.position).copy(up = 0.0)
                val bearingFromLandmarkToUser = Angles.normalizeBearing(trueBearingFromUser + 180.0)
                rays.add(Ray2D(origin = landmarkEnu, bearingDegrees = bearingFromLandmarkToUser))

                // Also compute range-bearing estimate: userPos = landmarkPos - d * dir(trueBearingFromUser)
                val rad = trueBearingFromUser * Angles.DEG_TO_RAD
                directEstimates.add(
                    EnuPoint(
                        east = landmarkEnu.east - q.distanceMeters * sin(rad),
                        north = landmarkEnu.north - q.distanceMeters * cos(rad),
                        up = 0.0
                    )
                )
            }
        }

        val triangulated = if (rays.size >= 2) {
            Geometry2D.triangulateRays(
                rays = rays,
                minParallaxDegrees = 10.0,
                maxRmsResidualMeters = 1.5,
                maxDistanceMeters = 20.0
            )?.point
        } else null

        if (triangulated != null) {
            return triangulated
        }
        if (directEstimates.isNotEmpty()) {
            return EnuPoint(
                east = directEstimates.sumOf { it.east } / directEstimates.size,
                north = directEstimates.sumOf { it.north } / directEstimates.size,
                up = 0.0
            )
        }
        return EnuPoint(0.0, 0.0, 0.0)
    }

    private fun computeTextSimilarity(
        qTexts: List<String>,
        sTexts: List<String>,
        qTokens: Set<String>,
        sTokens: Set<String>
    ): Double {
        if (qTexts.isEmpty() || sTexts.isEmpty()) return 0.0
        if (qTokens.isNotEmpty() && sTokens.isNotEmpty() && qTokens == sTokens) {
            return 1.0
        }
        var best = 0.0
        for (qt in qTexts) {
            for (st in sTexts) {
                // SimEval.fuzzyCompare returns 0.0 for exact match, 1.0 for completely different
                val sim = (1.0 - SimEval.fuzzyCompare(qt, st, needleCanBeShorter = false)).coerceIn(0.0, 1.0)
                if (sim > best) best = sim
            }
        }
        return best
    }

    companion object {
        private val IDENTIFIER_REGEX = Regex("\\b([0-9]+[a-zA-Z]?|[a-zA-Z][0-9]+)\\b")

        /**
         * Extracts discriminative room/door identifier tokens (such as `"214"` from `"Room 214"` or `"3B"` from `"Suite 3B"`).
         */
        fun extractIdentifierTokens(texts: List<String>): List<String> {
            val tokens = LinkedHashSet<String>()
            for (t in texts) {
                for (m in IDENTIFIER_REGEX.findAll(t)) {
                    tokens.add(m.value.lowercase())
                }
            }
            return tokens.toList()
        }

        /**
         * Creates a [RoomFingerprint] from a built indoor [SimEnvironment] and optional [FrameEmbedding]s.
         */
        fun fromEnvironment(
            roomId: String,
            env: SimEnvironment,
            frameEmbeddings: List<FrameEmbedding> = emptyList()
        ): RoomFingerprint {
            val normalizedTexts = env.signs.orEmpty()
                .map { SimEval.normalizeSignText(it.text) }
                .filter { it.isNotBlank() }
                .distinct()

            val tokens = extractIdentifierTokens(normalizedTexts)

            val landmarks = env.objects.orEmpty()
                .filter { it.category != SimCategory.FLOOR }
                .map { obj ->
                    val enu = EnuPoint.fromPositionList(obj.position)
                    val bearing = Angles.bearingBetweenEnu(EnuPoint(0.0, 0.0, 0.0), enu)
                    val dist = enu.horizontalNorm()
                    RoomLandmarkBearing(
                        category = obj.category,
                        bearingDegrees = bearing,
                        distanceMeters = dist,
                        position = obj.position,
                        label = obj.name
                    )
                }

            return RoomFingerprint(
                roomId = roomId,
                roomName = env.name,
                geoAnchor = env.geoAnchor,
                normalizedTexts = normalizedTexts,
                identifierTokens = tokens,
                frameEmbeddings = frameEmbeddings,
                landmarks = landmarks
            )
        }

        /**
         * Static architectural fixtures get higher weight than movable furniture (`CHAIR`, `TABLE`, `SOFA`).
         */
        fun categoryStabilityWeight(category: SimCategory): Double = when (category) {
            SimCategory.DOOR,
            SimCategory.SIGN,
            SimCategory.RECEPTION_DESK,
            SimCategory.COUNTER,
            SimCategory.STAIRS,
            SimCategory.STEPS,
            SimCategory.LIFT,
            SimCategory.WINDOW -> 1.0
            SimCategory.DESK,
            SimCategory.TABLE,
            SimCategory.SOFA -> 0.5
            SimCategory.CHAIR -> 0.2
            else -> 0.5
        }

        fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
            val n = minOf(a.size, b.size)
            if (n == 0) return 0.0
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in 0 until n) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom > 1e-12) dot / denom else 0.0
        }
    }
}
