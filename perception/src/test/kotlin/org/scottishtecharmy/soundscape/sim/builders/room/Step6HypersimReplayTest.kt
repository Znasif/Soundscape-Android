package org.scottishtecharmy.soundscape.sim.builders.room

import kotlinx.coroutines.test.runTest
import org.scottishtecharmy.soundscape.sim.jvm.HypersimNyu40Mapping
import org.scottishtecharmy.soundscape.sim.jvm.JvmFileBudgetLedgerStore
import org.scottishtecharmy.soundscape.sim.jvm.Step6HypersimEvaluator
import org.scottishtecharmy.soundscape.sim.jvm.Step6HypersimReport
import org.scottishtecharmy.soundscape.sim.model.EnvironmentKind
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.model.SimJson
import org.scottishtecharmy.soundscape.sim.sources.Budget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Step6HypersimReplayTest {

    @Test
    fun nyu40MappingTableMapsAllIndoorTargetCategoriesDeterministically() {
        assertEquals(SimCategory.FLOOR, HypersimNyu40Mapping.toSimCategory(2))
        assertEquals(SimCategory.CHAIR, HypersimNyu40Mapping.toSimCategory(5))
        assertEquals(SimCategory.SOFA, HypersimNyu40Mapping.toSimCategory(6))
        assertEquals(SimCategory.TABLE, HypersimNyu40Mapping.toSimCategory(7))
        assertEquals(SimCategory.DOOR, HypersimNyu40Mapping.toSimCategory(8))
        assertEquals(SimCategory.WINDOW, HypersimNyu40Mapping.toSimCategory(9))
        assertEquals(SimCategory.COUNTER, HypersimNyu40Mapping.toSimCategory(12))
        assertEquals(SimCategory.DESK, HypersimNyu40Mapping.toSimCategory(14))
        assertEquals(SimCategory.SIGN, HypersimNyu40Mapping.toSimCategory(30))
        assertEquals(SimCategory.COUNTER, HypersimNyu40Mapping.canonicalEvalCategory(SimCategory.RECEPTION_DESK))
        assertEquals(SimCategory.COUNTER, HypersimNyu40Mapping.canonicalEvalCategory(SimCategory.DESK))
    }

    @Test
    fun offlineReplayOfHypersimAi006006ReproducesStep6MetricsWithZeroNetworkCalls() = runTest {
        val gtFile = File("src/test/resources/fixtures/hypersim-gt/ai_006_006.json")
        val fixtureDir = File("src/test/resources/fixtures/hypersim-ai_006_006")
        val reportFile = File("docs/agent/metrics/STEP6-hypersim.json")

        assertTrue(gtFile.exists(), "Ground-truth JSON for ai_006_006 must exist")
        assertTrue(fixtureDir.exists(), "Recorded HTTP fixtures for ai_006_006 must exist")
        assertTrue(reportFile.exists(), "STEP6-hypersim.json report must exist")

        // Verify fixture directory is < 3 MB and has zero raw images or HDF5 files
        val files = fixtureDir.walkTopDown().filter { it.isFile }.toList()
        val totalBytes = files.sumOf { it.length() }
        assertTrue(
            totalBytes < 3L * 1024L * 1024L,
            "Fixture directory size ($totalBytes bytes) must be < 3 MB"
        )
        for (f in files) {
            val ext = f.extension.lowercase()
            assertTrue(
                ext !in setOf("jpg", "jpeg", "png", "hdf5", "h5", "zip"),
                "No raw image or HDF5 file may be committed in fixtures: ${f.name}"
            )
        }

        val sceneGt = Step6HypersimEvaluator.loadSceneGt(gtFile)
        val replayBudget = Budget(
            maxMapillaryCalls = 0,
            maxSamCalls = 300,
            maxGeminiCalls = 300,
            step = "step-6-replay-test"
        )

        // Replay with imageCacheDir = null (proving zero reliance on /tmp/hypersim_cache JPEGs) and replayOnly = true
        val bundle = Step6HypersimEvaluator.evaluateSingleScene(
            sceneGt = sceneGt,
            imageCacheDir = null,
            httpFixtureDir = fixtureDir,
            budget = replayBudget,
            replayOnly = true
        )

        assertEquals(0, replayBudget.samCalls, "Offline replay must make 0 live SAM 3.1 calls")
        assertEquals(0, replayBudget.geminiCalls, "Offline replay must make 0 live Gemini calls")
        assertTrue(replayBudget.replayedSamCalls > 0, "Offline replay must consume recorded SAM 3.1 fixtures")
        assertTrue(replayBudget.replayedGeminiCalls > 0, "Offline replay must consume recorded Gemini fixtures")

        val committedReport = SimJson.decodeFromString(Step6HypersimReport.serializer(), reportFile.readText())
        val expectedReal6Dof = committedReport.perScenePose6DofRealHeight.first { it.sceneId == "ai_006_006" }
        val expectedAssumed165 = committedReport.perScenePose6DofAssumed165m.first { it.sceneId == "ai_006_006" }
        val expectedRotOnly = committedReport.perSceneRotationOnlySubset.first { it.sceneId == "ai_006_006" }

        assertEquals(expectedReal6Dof.objectMetrics, bundle.real6DofResult.sceneMetrics.objectMetrics)
        assertEquals(expectedReal6Dof.floorMetrics, bundle.real6DofResult.sceneMetrics.floorMetrics)
        assertEquals(expectedAssumed165.objectMetrics, bundle.assumed165Result.sceneMetrics.objectMetrics)
        assertEquals(expectedRotOnly.objectMetrics, bundle.rotOnlyResult.sceneMetrics.objectMetrics)
    }

    @Test
    fun allSixHypersimRoomSamplesAndBudgetLedgerAreValidAndWithinCaps() {
        for (sceneId in Step6HypersimEvaluator.PRE_REGISTERED_SCENES) {
            val sampleFile = File("samples/hypersim-$sceneId.room.json")
            assertTrue(sampleFile.exists(), "Sample file samples/hypersim-$sceneId.room.json must exist")
            val env = SimEnvironment.fromJson(sampleFile.readText())
            assertEquals(EnvironmentKind.ROOM, env.kind)
            val attr = assertNotNull(env.attribution)
            assertTrue(attr.contains("Hypersim") && attr.contains("CC BY-SA 3.0"))
            assertTrue(env.objects.orEmpty().isNotEmpty(), "Room $sceneId must contain localized objects")
        }

        val ledgerFile = File("docs/agent/metrics/budget-ledger.json")
        assertTrue(ledgerFile.exists(), "budget-ledger.json must exist")
        val store = JvmFileBudgetLedgerStore(ledgerFile)
        val step6Totals = Budget(step = "step-6", ledgerStore = store).summary()
        assertTrue(step6Totals.samCalls in 1..300, "Step 6 live SAM 3.1 calls (${step6Totals.samCalls}) must be in 1..300")
        assertTrue(step6Totals.geminiCalls in 1..300, "Step 6 live Gemini calls (${step6Totals.geminiCalls}) must be in 1..300")
        assertTrue(
            step6Totals.downloadedBytes in 1L..(15L * 1024L * 1024L * 1024L),
            "Step 6 downloaded bytes (${step6Totals.downloadedBytes}) must be logged and <= 15 GB"
        )
    }
}
