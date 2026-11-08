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
}
