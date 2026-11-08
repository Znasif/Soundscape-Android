package org.scottishtecharmy.soundscape.sim.eval

import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimEvalTest {

    @Test
    fun evaluatesObjectMatchingGreedyByDistanceAndCategory() {
        val truth = listOf(
            EvalLandmark("t1", SimCategory.DOOR, EnuPoint(0.0, 0.0)),
            EvalLandmark("t2", SimCategory.DOOR, EnuPoint(10.0, 0.0)),
            EvalLandmark("t3", SimCategory.STEPS, EnuPoint(5.0, 5.0))
        )
        val predicted = listOf(
            EvalLandmark("p1", SimCategory.DOOR, EnuPoint(0.5, 0.0)),   // 0.5m from t1
            EvalLandmark("p2", SimCategory.DOOR, EnuPoint(11.5, 0.0)),  // 1.5m from t2
            EvalLandmark("p3", SimCategory.DOOR, EnuPoint(30.0, 30.0)), // false positive (> 5m)
            EvalLandmark("p4", SimCategory.SIGN, EnuPoint(5.0, 5.0))    // wrong category for t3
        )

        val metrics = SimEval.evaluateObjects(predicted, truth, maxDistanceMeters = 5.0)
        assertEquals(4, metrics.predictedCount)
        assertEquals(3, metrics.truthCount)
        assertEquals(2, metrics.truePositives)
        assertEquals(2, metrics.falsePositives)
        assertEquals(1, metrics.falseNegatives)
        assertEquals(0.5, metrics.precision, 1e-6)
        assertEquals(2.0 / 3.0, metrics.recall, 1e-6)
        assertEquals(1.0, metrics.meanErrorMeters, 1e-6)
        assertEquals(1.0, metrics.medianErrorMeters, 1e-6)
        assertTrue(metrics.p90ErrorMeters in 1.3..1.5)
    }

    @Test
    fun computesCharacterErrorRateExactMatchAndFuzzyCompare() {
        assertEquals(0.0, SimEval.characterErrorRate("Blue Bottle", "Blue Bottle"), 1e-6)
        assertEquals(1.0 / 4.0, SimEval.characterErrorRate("Book", "Bock"), 1e-6)

        val textMetrics = SimEval.evaluateTexts(
            listOf(
                "Blue Bottle Coffee" to "Blue Bottle Coffee",
                "  blue bottle  coffee " to "Blue Bottle Coffee",
                "Ferry Plza" to "Ferry Plaza"
            )
        )
        assertEquals(3, textMetrics.sampleCount)
        assertEquals(1.0 / 3.0, textMetrics.exactMatchRate, 1e-6)
        assertEquals(2.0 / 3.0, textMetrics.normalizedExactMatchRate, 1e-6)
        assertTrue(textMetrics.meanCer < 0.05)

        // Verify fuzzyCompare matches Soundscape behavior (0.0 for exact, low for prefix/typo, high for unrelated)
        assertEquals(0.0, SimEval.fuzzyCompare("Acme Bread", "Acme Bread"), 1e-6)
        assertTrue(SimEval.fuzzyCompare("Acme", "Acme Bread Company", needleCanBeShorter = true) < 0.05)
        assertTrue(SimEval.fuzzyCompare("Gotts Roadside", "Gott's Roadside", needleCanBeShorter = false) < 0.15)
        assertTrue(SimEval.fuzzyCompare("Blue Bottle", "Imperial Tea Court", needleCanBeShorter = false) > 0.6)
    }

    @Test
    fun evaluatesRoomRecognitionWithHardNoFalseMatchFlag() {
        val safeTrials = listOf(
            RoomRecognitionTrial(queryId = "q1", trueRoomId = "room-214", predictedRoomId = "room-214"),
            RoomRecognitionTrial(queryId = "q2", trueRoomId = "room-216", predictedRoomId = null), // abstained on near-duplicate
            RoomRecognitionTrial(queryId = "q3", trueRoomId = null, predictedRoomId = null)        // abstained on new room
        )
        val safeMetrics = SimEval.evaluateRoomRecognition(safeTrials)
        assertEquals(1, safeMetrics.correctMatches)
        assertEquals(0, safeMetrics.falseMatches)
        assertEquals(1, safeMetrics.correctAbstentions)
        assertEquals(1, safeMetrics.missedMatches)
        assertTrue(safeMetrics.noFalseMatch)

        val unsafeTrials = safeTrials + RoomRecognitionTrial(
            queryId = "q4",
            trueRoomId = "room-216",
            predictedRoomId = "room-214"
        )
        val unsafeMetrics = SimEval.evaluateRoomRecognition(unsafeTrials)
        assertEquals(1, unsafeMetrics.falseMatches)
        assertFalse(unsafeMetrics.noFalseMatch)
    }

    @Test
    fun evaluatesNavigationSplAndSuccessRate() {
        val episodes = listOf(
            NavigationEpisode(succeeded = true, shortestPathMeters = 10.0, actualPathMeters = 10.0), // 1.0
            NavigationEpisode(succeeded = true, shortestPathMeters = 10.0, actualPathMeters = 20.0), // 0.5
            NavigationEpisode(succeeded = false, shortestPathMeters = 10.0, actualPathMeters = 12.0) // 0.0
        )
        val metrics = SimEval.evaluateNavigation(episodes)
        assertEquals(3, metrics.episodeCount)
        assertEquals(2.0 / 3.0, metrics.successRate, 1e-6)
        assertEquals(0.5, metrics.spl, 1e-6)
    }
}
