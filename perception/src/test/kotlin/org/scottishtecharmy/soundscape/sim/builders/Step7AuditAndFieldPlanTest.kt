package org.scottishtecharmy.soundscape.sim.builders

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.model.SimJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Step7AuditAndFieldPlanTest {

    private fun projectDir(): File {
        val cwd = File(System.getProperty("user.dir"))
        return if (File(cwd, "samples").exists()) cwd else File(cwd, "perception")
    }

    @Test
    fun allSampleManifestsParseCleanlyAndIncludeAttribution() {
        val samplesDir = File(projectDir(), "samples")
        assertTrue(samplesDir.exists(), "samples/ directory must exist")
        val jsonFiles = samplesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        assertTrue(jsonFiles.size >= 8, "Expected at least 8 sample JSON manifests (fillmore, room-214, 6 hypersim rooms)")

        for (file in jsonFiles) {
            val env = SimEnvironment.fromJson(file.readText())
            assertTrue(env.name.isNotBlank(), "Manifest ${file.name} must have a non-blank name")
            assertTrue(!env.attribution.isNullOrBlank(), "Manifest ${file.name} must include a non-blank attribution string")
            assertTrue(env.objects.orEmpty().isNotEmpty(), "Manifest ${file.name} must contain at least one landmark object")
        }
    }

    @Test
    fun portableSourcePackagesContainZeroJavaImports() {
        val mainKotlinRoot = File(projectDir(), "src/main/kotlin/org/scottishtecharmy/soundscape/sim")
        val portableDirs = listOf("model", "geo", "eval", "builders", "sources")
        for (dirName in portableDirs) {
            val dir = File(mainKotlinRoot, dirName)
            assertTrue(dir.exists(), "Portable package directory $dirName must exist")
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { ktFile ->
                    val lines = ktFile.readLines()
                    lines.forEachIndexed { idx, line ->
                        val trimmed = line.trim()
                        assertFalse(
                            trimmed.startsWith("import java.") || trimmed.startsWith("import javax."),
                            "Portable file ${ktFile.relativeTo(projectDir())} line ${idx + 1} must not import java.* or javax.*: $trimmed",
                        )
                    }
                }
        }
    }

    @Test
    fun repositoryContainsZeroCommittedImagesOrHdf5Files() {
        val forbiddenExtensions = setOf("jpg", "jpeg", "png", "webp", "h5", "hdf5")
        val root = projectDir()
        val offending = root.walkTopDown()
            .filter { file ->
                file.isFile &&
                    !file.path.contains("${File.separator}build${File.separator}") &&
                    !file.path.contains("${File.separator}.gradle${File.separator}") &&
                    file.extension.lowercase() in forbiddenExtensions
            }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue(
            offending.isEmpty(),
            "Repository must not contain committed images or HDF5 files, found: $offending",
        )
    }

    @Test
    fun fieldVerificationExampleJsonFollowsFieldTestPlanSchema() {
        val exampleFile = File(projectDir(), "field/fillmore-2026-11-08-example.json")
        assertTrue(exampleFile.exists(), "field/fillmore-2026-11-08-example.json must exist")
        val root = SimJson.decodeFromString(JsonObject.serializer(), exampleFile.readText())

        assertEquals("1.0.0", root["schemaVersion"]?.jsonPrimitive?.content)
        assertEquals("fillmore-2318", root["areaId"]?.jsonPrimitive?.content)
        assertTrue(!root["attribution"]?.jsonPrimitive?.content.isNullOrBlank())

        val outdoor = root["outdoorVerifications"]?.jsonArray
        assertNotNull(outdoor)
        assertTrue(outdoor.size >= 2, "Example field file must include at least 2 outdoor landmark verifications")
        val firstVerification = outdoor.first().jsonObject
        assertEquals("TRUE_POSITIVE", firstVerification["verdict"]?.jsonPrimitive?.content)

        val missed = root["missedGroundTruthLandmarks"]?.jsonArray
        assertNotNull(missed)
        assertTrue(missed.isNotEmpty(), "Example field file must demonstrate missed landmark logging")

        val indoor = root["indoorScanTrials"]?.jsonArray
        assertNotNull(indoor)
        assertTrue(indoor.isNotEmpty(), "Example field file must include an indoor scan trial")
    }
}
