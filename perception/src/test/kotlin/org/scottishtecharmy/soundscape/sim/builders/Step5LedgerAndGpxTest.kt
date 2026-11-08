package org.scottishtecharmy.soundscape.sim.builders

import org.scottishtecharmy.soundscape.sim.jvm.JvmFileBudgetLedgerStore
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.BudgetExceededException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Step5LedgerAndGpxTest {

    @Test
    fun ledgerSurvivesRestartAndEnforcesCapAcrossTwoSeparateRuns() {
        val tempFile = Files.createTempFile("budget-ledger-test", ".json").toFile()
        try {
            tempFile.delete()
            val store1 = JvmFileBudgetLedgerStore(tempFile)

            // Run 1: spend 3 SAM calls and 4 Gemini calls against cap of 5 SAM and 6 Gemini
            val run1Budget = Budget(
                maxMapillaryCalls = 10,
                maxSamCalls = 5,
                maxGeminiCalls = 6,
                step = "step-5",
                command = "build-area --name run-1",
                ledgerStore = store1,
                clockEpochMs = { 1731033000000L }
            )
            run1Budget.consumeSam(2, isReplay = false)
            run1Budget.consumeSam(1, isReplay = false)
            run1Budget.consumeGemini(4, isReplay = false)
            run1Budget.consumeMapillary(3, isReplay = false)
            assertEquals(3, run1Budget.samCalls)
            assertEquals(4, run1Budget.geminiCalls)
            assertEquals(3, run1Budget.mapillaryCalls)
            assertTrue(tempFile.exists(), "Expected ledger file to be written after live calls")

            // Restart: create a brand-new store and Budget instance reading the persisted file
            val store2 = JvmFileBudgetLedgerStore(tempFile)
            val run2Budget = Budget(
                maxMapillaryCalls = 10,
                maxSamCalls = 5,
                maxGeminiCalls = 6,
                step = "step-5",
                command = "build-area --name run-2",
                ledgerStore = store2,
                clockEpochMs = { 1731033100000L }
            )

            // 1. Verify it survived restart
            assertEquals(3, run2Budget.samCalls)
            assertEquals(4, run2Budget.geminiCalls)
            assertEquals(3, run2Budget.mapillaryCalls)
            assertEquals(2, run2Budget.remainingSamAllowance)
            assertEquals(2, run2Budget.remainingGeminiAllowance)

            // 2. Verify checkPlannedCalls refuses a run whose planned calls would exceed the step cap
            assertFailsWith<BudgetExceededException> {
                run2Budget.checkPlannedCalls(plannedSam = 3)
            }

            // 3. Spend the remaining 2 SAM calls in run 2 -> succeeds (total = 5)
            run2Budget.consumeSam(2, isReplay = false)
            assertEquals(5, run2Budget.samCalls)
            assertEquals(0, run2Budget.remainingSamAllowance)

            // 4. Attempting 1 more live SAM call across the second run must fail with BudgetExceededException
            assertFailsWith<BudgetExceededException> {
                run2Budget.consumeSam(1, isReplay = false)
            }

            // 5. Replayed calls are counted separately and never charged even when live cap is exhausted
            run2Budget.consumeSam(10, isReplay = true)
            run2Budget.consumeGemini(15, isReplay = true)
            run2Budget.consumeMapillary(8, isReplay = true)
            assertEquals(5, run2Budget.samCalls)
            assertEquals(10, run2Budget.replayedSamCalls)
            assertEquals(4, run2Budget.geminiCalls)
            assertEquals(15, run2Budget.replayedGeminiCalls)
            assertEquals(3, run2Budget.mapillaryCalls)
            assertEquals(8, run2Budget.replayedMapillaryCalls)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun committedSeedLedgerContainsSteps0To4Totals() {
        val ledgerFile = File("docs/agent/metrics/budget-ledger.json")
        assertTrue(ledgerFile.exists(), "Expected perception/docs/agent/metrics/budget-ledger.json to exist")
        val data = assertNotNull(JvmFileBudgetLedgerStore(ledgerFile).load())
        val steps0To3 = assertNotNull(data.stepTotals["steps-0-to-3"])
        assertEquals(45, steps0To3.samLiveCalls)
        assertEquals(35, steps0To3.geminiLiveCalls)
        assertEquals(16, steps0To3.mapillaryLiveCalls)

        val step4 = assertNotNull(data.stepTotals["step-4"])
        assertEquals(0, step4.samLiveCalls)
        assertEquals(0, step4.geminiLiveCalls)
        assertEquals(0, step4.mapillaryLiveCalls)
    }

    @Test
    fun gpxExporterProducesValidSoundscapeGpxParsedByMirrorParser() {
        // Mirrors GpxParser.kt (lines 14-229) and GpxUtils.kt (lines 11-61)
        val ferrySample = File("samples/ferry-building.street.json")
        assertTrue(ferrySample.exists())
        val env = SimEnvironment.fromJson(ferrySample.readText())

        val gpxXml = GpxExporter.exportEnvironmentToGpx(
            env = env,
            includeMode = GpxIncludeMode.PERCEIVED
        )

        val parsed = SimGpxMirrorParser.parseGpxFile(gpxXml)
        assertTrue(parsed.name.startsWith("Prototype:"), "Route name must indicate Prototype, got '${parsed.name}'")
        assertTrue(parsed.description.contains("Mapillary") && parsed.description.contains("OpenStreetMap"))
        assertTrue(parsed.markers.isNotEmpty(), "Expected perceived waypoints in exported GPX")
        assertEquals(parsed.rawWaypoints.size, parsed.rawRoutePoints.size)

        for (wpt in parsed.markers) {
            assertTrue(wpt.latitude in 37.78..37.81, "Expected valid SF latitude, got ${wpt.latitude}")
            assertTrue(wpt.longitude in -122.41..-122.38, "Expected valid SF longitude, got ${wpt.longitude}")
            assertTrue(
                wpt.name.startsWith("Door, perceived") ||
                    wpt.name.startsWith("Shop sign") ||
                    wpt.name.startsWith("Steps, perceived"),
                "Expected listener-friendly waypoint name, got '${wpt.name}'"
            )
            assertTrue(wpt.desc.contains("Mapillary imageId="), "Expected Mapillary imageId in desc: ${wpt.desc}")
            assertTrue(wpt.desc.contains("sam-3.1 confidence="), "Expected sam-3.1 confidence in desc: ${wpt.desc}")
            assertTrue(wpt.desc.contains("Attribution:"), "Expected attribution in desc: ${wpt.desc}")
        }
    }

    @Test
    fun offlineReplayReproducesHeldOutMetricsWithZeroNetworkCalls() = kotlinx.coroutines.test.runTest {
        val fixturesRootDir = File("src/test/resources/fixtures")
        val metricsDir = File("docs/agent/metrics")
        val tempOutDir = Files.createTempDirectory("step5-replay-test").toFile()
        try {
            val replayAreas = listOf("paris-chatelet", "seattle-king-street")
            for (areaId in replayAreas) {
                val spec = org.scottishtecharmy.soundscape.sim.jvm.Step5OutdoorEvaluator.PRE_REGISTERED_AREAS.first { it.areaId == areaId }
                val committedJson = File(metricsDir, "STEP5-$areaId.json")
                assertTrue(committedJson.exists(), "Expected committed metrics file ${committedJson.path}")
                val committedReport = org.scottishtecharmy.soundscape.sim.model.SimJson.decodeFromString(
                    org.scottishtecharmy.soundscape.sim.jvm.Step5AreaEvalReport.serializer(),
                    committedJson.readText()
                )

                val replayBudget = Budget(
                    maxMapillaryCalls = 5000,
                    maxSamCalls = 300,
                    maxGeminiCalls = 600,
                    step = "step-5-test"
                )
                val replayed = org.scottishtecharmy.soundscape.sim.jvm.Step5OutdoorEvaluator.evaluateSingleArea(
                    spec = spec,
                    budget = replayBudget,
                    fixturesRootDir = fixturesRootDir,
                    samplesDir = File(tempOutDir, "samples"),
                    gpxOutDir = File(tempOutDir, "gpx"),
                    metricsOutDir = File(tempOutDir, "metrics"),
                    replayOnly = true
                )

                // Zero live calls, non-zero replayed calls
                assertEquals(0, replayBudget.mapillaryCalls)
                assertEquals(0, replayBudget.samCalls)
                assertEquals(0, replayBudget.geminiCalls)
                assertTrue(replayBudget.replayedSamCalls > 0, "Expected replayed SAM calls for $areaId")

                // Reproduced metrics must match committed STEP5-<area>.json exactly
                assertEquals(committedReport.perceptionDoorCount, replayed.report.perceptionDoorCount)
                assertEquals(committedReport.osmEntranceCountVisible, replayed.report.osmEntranceCountVisible)
                assertEquals(committedReport.osmEntranceCountExcluded, replayed.report.osmEntranceCountExcluded)
                assertEquals(
                    committedReport.perceptionOnlyVsVisibleOsm.truePositives,
                    replayed.report.perceptionOnlyVsVisibleOsm.truePositives
                )
                assertEquals(
                    committedReport.perceptionOnlyVsVisibleOsm.falsePositives,
                    replayed.report.perceptionOnlyVsVisibleOsm.falsePositives
                )
                assertEquals(
                    committedReport.perceptionOnlyVsVisibleOsm.falseNegatives,
                    replayed.report.perceptionOnlyVsVisibleOsm.falseNegatives
                )
                assertEquals(
                    committedReport.perceptionOnlyVsVisibleOsm.precision,
                    replayed.report.perceptionOnlyVsVisibleOsm.precision,
                    1e-6
                )
                assertEquals(
                    committedReport.perceptionOnlyVsVisibleOsm.recall,
                    replayed.report.perceptionOnlyVsVisibleOsm.recall,
                    1e-6
                )
                assertEquals(
                    committedReport.unmatchedDoors.likelyRealDoorMissingFromOsm,
                    replayed.report.unmatchedDoors.likelyRealDoorMissingFromOsm
                )
            }
        } finally {
            tempOutDir.deleteRecursively()
        }
    }

    @Test
    fun allExportedStep5GpxFilesParseCleanlyInSoundscapeMirrorParser() {
        val gpxDir = File("samples/gpx")
        assertTrue(gpxDir.exists(), "Expected samples/gpx directory")
        val gpxFiles = gpxDir.listFiles { f -> f.extension == "gpx" }?.toList().orEmpty()
        assertTrue(gpxFiles.size >= 12, "Expected at least 12 GPX files (10 areas + 2 Fillmore routes), found ${gpxFiles.size}")
        for (gpxFile in gpxFiles) {
            val parsed = SimGpxMirrorParser.parseGpxFile(gpxFile.readText())
            assertTrue(parsed.name.contains("Prototype"), "Expected Prototype route name in ${gpxFile.name}")
            assertTrue(parsed.markers.isNotEmpty(), "Expected non-empty waypoints in ${gpxFile.name}")
            assertEquals(parsed.rawWaypoints.size, parsed.rawRoutePoints.size, "Expected matching wpt and rtept counts in ${gpxFile.name}")
        }
    }
}
