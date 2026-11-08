package org.scottishtecharmy.soundscape.sim.eval

import kotlinx.serialization.Serializable
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Ground-truth or predicted landmark used in spatial object evaluation.
 */
data class EvalLandmark(
    val id: String,
    val category: SimCategory,
    val position: EnuPoint
)

/**
 * Pair of matched predicted and ground-truth landmarks with Euclidean distance in metres.
 */
data class MatchedLandmarkPair(
    val predictedId: String,
    val truthId: String,
    val category: SimCategory,
    val distanceMeters: Double
)

/**
 * Spatial landmark detection & localisation metrics.
 */
@Serializable
data class ObjectEvalMetrics(
    val predictedCount: Int,
    val truthCount: Int,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val precision: Double,
    val recall: Double,
    val f1: Double,
    val meanErrorMeters: Double,
    val medianErrorMeters: Double,
    val p90ErrorMeters: Double
)

/**
 * Sign OCR text evaluation metrics.
 */
@Serializable
data class TextEvalMetrics(
    val sampleCount: Int,
    val exactMatchRate: Double,
    val normalizedExactMatchRate: Double,
    val meanCer: Double
)

/**
 * Single room re-recognition trial outcome.
 *
 * @property queryId Identifier of the query scan.
 * @property trueRoomId Ground-truth room ID (`null` if the room is genuinely new / unseen).
 * @property predictedRoomId Predicted room ID (`null` if the recognizer abstained).
 */
data class RoomRecognitionTrial(
    val queryId: String,
    val trueRoomId: String?,
    val predictedRoomId: String?
)

/**
 * Room re-recognition confusion counts and hard safety flag (`noFalseMatch`).
 */
@Serializable
data class RoomRecognitionMetrics(
    val totalTrials: Int,
    val correctMatches: Int,
    val falseMatches: Int,
    val correctAbstentions: Int,
    val missedMatches: Int,
    val noFalseMatch: Boolean
)

/**
 * Single navigation episode for SPL evaluation.
 *
 * @property succeeded Whether the agent reached the goal within the stopping threshold.
 * @property shortestPathMeters Geodesic shortest path length $L_i$ from start to goal.
 * @property actualPathMeters Actual path length $P_i$ traversed by the agent.
 */
data class NavigationEpisode(
    val succeeded: Boolean,
    val shortestPathMeters: Double,
    val actualPathMeters: Double
)

/**
 * Navigation episode evaluation metrics (Success Rate and SPL).
 */
@Serializable
data class NavigationMetrics(
    val episodeCount: Int,
    val successRate: Double,
    val spl: Double
)

/**
 * Pure-Kotlin evaluation suite for spatial object localisation, OCR text accuracy,
 * room re-recognition, and navigation SPL.
 */
object SimEval {

    /**
     * Greedy category-aware matching within [maxDistanceMeters] (sorted by ascending distance).
     * Computes Precision, Recall, F1, and mean / median / p90 localisation error on matched pairs.
     */
    fun evaluateObjects(
        predicted: List<EvalLandmark>,
        truth: List<EvalLandmark>,
        maxDistanceMeters: Double = 5.0
    ): ObjectEvalMetrics {
        data class Candidate(val predIdx: Int, val truthIdx: Int, val dist: Double)

        val candidates = mutableListOf<Candidate>()
        for (i in predicted.indices) {
            val p = predicted[i]
            for (j in truth.indices) {
                val t = truth[j]
                if (p.category != t.category) continue
                val d = p.position.distanceTo2D(t.position)
                if (d <= maxDistanceMeters) {
                    candidates.add(Candidate(i, j, d))
                }
            }
        }
        candidates.sortBy { it.dist }

        val usedPred = BooleanArray(predicted.size)
        val usedTruth = BooleanArray(truth.size)
        val errors = mutableListOf<Double>()

        for (c in candidates) {
            if (!usedPred[c.predIdx] && !usedTruth[c.truthIdx]) {
                usedPred[c.predIdx] = true
                usedTruth[c.truthIdx] = true
                errors.add(c.dist)
            }
        }

        val tp = errors.size
        val fp = predicted.size - tp
        val fn = truth.size - tp

        val precision = if (predicted.isNotEmpty()) tp.toDouble() / predicted.size else if (truth.isEmpty()) 1.0 else 0.0
        val recall = if (truth.isNotEmpty()) tp.toDouble() / truth.size else if (predicted.isEmpty()) 1.0 else 0.0
        val f1 = if (precision + recall > 0.0) 2.0 * precision * recall / (precision + recall) else 0.0

        errors.sort()
        val meanErr = if (errors.isNotEmpty()) errors.sum() / errors.size else 0.0
        val medianErr = percentile(errors, 0.50)
        val p90Err = percentile(errors, 0.90)

        return ObjectEvalMetrics(
            predictedCount = predicted.size,
            truthCount = truth.size,
            truePositives = tp,
            falsePositives = fp,
            falseNegatives = fn,
            precision = precision,
            recall = recall,
            f1 = f1,
            meanErrorMeters = meanErr,
            medianErrorMeters = medianErr,
            p90ErrorMeters = p90Err
        )
    }

    /**
     * Computes Character Error Rate (CER = Levenshtein edit distance / reference length) between
     * [predicted] and [reference].
     */
    fun characterErrorRate(predicted: String, reference: String): Double {
        if (reference.isEmpty()) return if (predicted.isEmpty()) 0.0 else 1.0
        val dist = levenshteinDistance(predicted, reference)
        return dist.toDouble() / reference.length.toDouble()
    }

    /**
     * Evaluates a batch of `(predicted, reference)` sign text pairs.
     */
    fun evaluateTexts(pairs: List<Pair<String, String>>): TextEvalMetrics {
        if (pairs.isEmpty()) {
            return TextEvalMetrics(0, 1.0, 1.0, 0.0)
        }
        var exact = 0
        var normExact = 0
        var sumCer = 0.0
        for ((pred, ref) in pairs) {
            if (pred == ref) exact++
            val normPred = normalizeSignText(pred)
            val normRef = normalizeSignText(ref)
            if (normPred == normRef) normExact++
            sumCer += characterErrorRate(normPred, normRef)
        }
        val n = pairs.size.toDouble()
        return TextEvalMetrics(
            sampleCount = pairs.size,
            exactMatchRate = exact / n,
            normalizedExactMatchRate = normExact / n,
            meanCer = sumCer / n
        )
    }

    /**
     * Normalizes sign text by trimming, collapsing internal whitespace, and lowercasing.
     */
    fun normalizeSignText(text: String): String =
        text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Port of Soundscape's `String.fuzzyCompare` (`StringExt.kt`): Damerau-Levenshtein distance
     * normalized to `[0.0, 1.0]` (where `0.0` is an exact match), with optional prefix truncation when
     * [needleCanBeShorter] is `true`.
     */
    fun fuzzyCompare(
        needle: String,
        haystack: String,
        needleCanBeShorter: Boolean = true
    ): Double {
        val nStr = normalizeSignText(needle)
        var hStr = normalizeSignText(haystack)
        if (nStr.isEmpty() && hStr.isEmpty()) return 0.0
        if (nStr.isEmpty() || hStr.isEmpty()) return 1.0

        var truncatedPenalty = 0.0
        if (needleCanBeShorter && hStr.length > nStr.length) {
            hStr = hStr.substring(0, nStr.length)
            truncatedPenalty = 0.01
        }
        val dist = damerauLevenshteinDistance(nStr, hStr)
        val maxLen = max(nStr.length, hStr.length).toDouble()
        return ((dist.toDouble() / maxLen) + truncatedPenalty).coerceIn(0.0, 1.0)
    }

    /**
     * Evaluates room re-recognition trials, tracking confusion counts and setting
     * `noFalseMatch = (falseMatches == 0)`.
     */
    fun evaluateRoomRecognition(trials: List<RoomRecognitionTrial>): RoomRecognitionMetrics {
        var correctMatches = 0
        var falseMatches = 0
        var correctAbstentions = 0
        var missedMatches = 0

        for (t in trials) {
            when {
                t.trueRoomId == null && t.predictedRoomId == null -> correctAbstentions++
                t.trueRoomId == null && t.predictedRoomId != null -> falseMatches++
                t.trueRoomId != null && t.predictedRoomId == null -> missedMatches++
                t.trueRoomId == t.predictedRoomId -> correctMatches++
                else -> falseMatches++
            }
        }
        return RoomRecognitionMetrics(
            totalTrials = trials.size,
            correctMatches = correctMatches,
            falseMatches = falseMatches,
            correctAbstentions = correctAbstentions,
            missedMatches = missedMatches,
            noFalseMatch = (falseMatches == 0)
        )
    }

    /**
     * Computes Success Rate and Success weighted by Path Length (SPL):
     * $$\text{SPL} = \frac{1}{N} \sum_{i=1}^{N} S_i \frac{L_i}{\max(P_i, L_i)}$$
     */
    fun evaluateNavigation(episodes: List<NavigationEpisode>): NavigationMetrics {
        if (episodes.isEmpty()) {
            return NavigationMetrics(episodeCount = 0, successRate = 0.0, spl = 0.0)
        }
        var successes = 0
        var splSum = 0.0
        for (ep in episodes) {
            if (ep.succeeded) {
                successes++
                val denom = max(ep.actualPathMeters, ep.shortestPathMeters)
                val ratio = if (denom > 1e-9) ep.shortestPathMeters / denom else 1.0
                splSum += ratio.coerceIn(0.0, 1.0)
            }
        }
        val n = episodes.size.toDouble()
        return NavigationMetrics(
            episodeCount = episodes.size,
            successRate = successes / n,
            spl = splSum / n
        )
    }

    private fun percentile(sortedValues: List<Double>, q: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        if (sortedValues.size == 1) return sortedValues[0]
        val pos = q * (sortedValues.size - 1)
        val lower = pos.toInt()
        val upper = ceil(pos).toInt().coerceAtMost(sortedValues.size - 1)
        val weight = pos - lower
        return sortedValues[lower] * (1.0 - weight) + sortedValues[upper] * weight
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }

    private fun damerauLevenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        var prev2 = IntArray(n + 1)
        var prev1 = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = min(
                    min(curr[j - 1] + 1, prev1[j] + 1),
                    prev1[j - 1] + cost
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = min(best, prev2[j - 2] + 1)
                }
                curr[j] = best
            }
            val tmp = prev2
            prev2 = prev1
            prev1 = curr
            curr = tmp
        }
        return prev1[n]
    }
}
