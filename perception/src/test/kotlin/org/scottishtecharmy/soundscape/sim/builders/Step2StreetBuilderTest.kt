package org.scottishtecharmy.soundscape.sim.builders

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import org.scottishtecharmy.soundscape.sim.jvm.HttpGateway
import org.scottishtecharmy.soundscape.sim.jvm.HttpMapillaryClient
import org.scottishtecharmy.soundscape.sim.jvm.HttpOverpassClient
import org.scottishtecharmy.soundscape.sim.model.EnvironmentKind
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.BudgetExceededException
import org.scottishtecharmy.soundscape.sim.sources.DeferredOvertureClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Step2StreetBuilderTest {

    private val fixturesDir = File("src/test/resources/fixtures/ferry")

    @Test
    fun budgetEnforcesHardCaps() {
        val budget = Budget(maxMapillaryCalls = 2, maxSamCalls = 1, maxGeminiCalls = 1)
        budget.consumeMapillary(2)
        assertFailsWith<BudgetExceededException> {
            budget.consumeMapillary(1)
        }
        budget.consumeSam(1)
        assertFalse(budget.canConsumeSam(1))
        assertFailsWith<BudgetExceededException> {
            budget.consumeSam(1)
        }
        budget.consumeGemini(1)
        assertFalse(budget.canConsumeGemini(1))
        assertFailsWith<BudgetExceededException> {
            budget.consumeGemini(1)
        }
    }

    @Test
    fun httpGatewayScrubsAuthHeadersAndSignedUrls() {
        val headers = mapOf(
            "Authorization" to "OAuth SECRET_TOKEN",
            "x-goog-api-key" to "SECRET_KEY",
            "Accept" to "application/json"
        )
        val scrubbedHeaders = HttpGateway.scrubHeaders(headers)
        assertFalse("Authorization" in scrubbedHeaders)
        assertFalse("x-goog-api-key" in scrubbedHeaders)
        assertEquals("application/json", scrubbedHeaders["Accept"])

        val rawBody = """{"id":"123","thumb_2048_url":"https://scontent-sjc3-1.xx.fbcdn.net/v/t1.15752-9/signed?access_token=abc123"}"""
        val scrubbedBody = HttpGateway.scrubSignedUrlsInBody(rawBody)
        assertFalse(scrubbedBody.contains("scontent"))
        assertFalse(scrubbedBody.contains("access_token"))
        assertTrue(scrubbedBody.contains("https://stub.mapillary.example/thumb.jpg"))
    }

    @Test
    fun recordedFixturesHaveNoSecretsOrSignedUrls() {
        assertTrue(fixturesDir.exists(), "Expected recorded fixtures in ${fixturesDir.absolutePath}")
        val files = fixturesDir.listFiles { f -> f.extension == "json" }?.toList().orEmpty()
        assertTrue(files.isNotEmpty(), "Expected non-empty fixture JSON files")
        for (f in files) {
            val content = f.readText()
            assertFalse(content.contains("OAuth "), "Fixture ${f.name} must not contain OAuth header")
            assertFalse(content.contains("__GEMINI_CRED_"), "Fixture ${f.name} must not contain credential placeholder")
            assertFalse(content.contains("https://scontent"), "Fixture ${f.name} must not contain signed CDN URL")
        }
    }

    @Test
    fun buildsFerryBuildingFromRecordedFixturesWithZeroNetworkCalls() = runTest {
        val gateway = HttpGateway(replayDir = fixturesDir, replayOnly = true)
        val budget = Budget()
        val mapillary = HttpMapillaryClient(gateway = gateway, budget = budget)
        val overpass = HttpOverpassClient(gateway = gateway)
        val overture = DeferredOvertureClient()

        val center = LatLon(37.79545, -122.39373)
        val localEnu = LocalEnu(center.latitude, center.longitude)
        assertTrue(overture.getPlacesInBbox(localEnu.boundingBox(75.0)).isEmpty())

        val areaCache = AreaCache(currentBuilderVersion = "0.1.0")
        val builder = StreetBuilder(
            mapillarySource = mapillary,
            overpassSource = overpass,
            areaCache = areaCache,
            budget = budget
        )

        val nowMs = 1730000000000L
        val config = StreetBuildConfig(
            center = center,
            radiusMeters = 75.0,
            areaName = "Ferry Building Street Preview",
            builderVersion = "0.1.0",
            nowEpochMs = nowMs
        )

        val events = builder.buildArea(config).toList()
        assertTrue(events.any { it is BuildEvent.FetchingSources })
        val stationsEvent = events.filterIsInstance<BuildEvent.StationsSelected>().firstOrNull()
        assertNotNull(stationsEvent)
        assertTrue(stationsEvent.stations.size in 1..12)
        assertTrue(stationsEvent.stations.all { it.isPano })

        // Verify stations are spaced at least 8m apart
        val stationEnus = stationsEvent.stations.map { EnuPoint.fromPositionList(it.position) }
        for (i in stationEnus.indices) {
            for (j in i + 1 until stationEnus.size) {
                assertTrue(
                    stationEnus[i].distanceTo2D(stationEnus[j]) >= 7.99,
                    "Stations $i and $j are closer than 8m"
                )
            }
        }

        val viewsEvent = events.filterIsInstance<BuildEvent.ViewsPlanned>().firstOrNull()
        assertNotNull(viewsEvent)
        assertTrue(viewsEvent.views.isNotEmpty())
        assertTrue(viewsEvent.views.all { it.hitDistanceMeters <= 40.0 })

        val completed = events.filterIsInstance<BuildEvent.Completed>().firstOrNull()
        assertNotNull(completed)
        val env = completed.environment
        assertEquals(EnvironmentKind.STREET, env.kind)
        assertNotNull(env.objects)
        assertTrue(env.objects.isNotEmpty())

        val doors = env.objects.filter { it.category == SimCategory.DOOR }
        assertTrue(doors.size >= 5, "Expected OSM entrances merged into DOOR objects, found ${doors.size}")
        assertTrue(doors.any { it.linkedVenue != null }, "Expected at least one door linked to an OSM venue")

        // Verify AreaCache hit on immediate second call, and rebuild rules (newer captured_at, version change, >90 days)
        val geohash7 = env.provenance?.geohash7!!
        assertFalse(areaCache.shouldRebuild(geohash7, nowEpochMs = nowMs + 1000L, newestMapillaryCaptureEpochMs = env.provenance.newestCaptureEpochMs))
        assertTrue(areaCache.shouldRebuild(geohash7, nowEpochMs = nowMs + AreaCache.NINETY_DAYS_MS + 1000L))
        assertTrue(areaCache.shouldRebuild(geohash7, nowEpochMs = nowMs + 1000L, newestMapillaryCaptureEpochMs = (env.provenance.newestCaptureEpochMs ?: 0L) + 1000L))

        val v2Cache = AreaCache(currentBuilderVersion = "0.2.0")
        v2Cache.put(env)
        assertTrue(v2Cache.shouldRebuild(geohash7, nowEpochMs = nowMs))
    }

    @Test
    fun ferryBuildingSampleManifestLoadsAndValidates() {
        val sampleFile = File("samples/ferry-building.street.json")
        assertTrue(sampleFile.exists(), "Expected perception/samples/ferry-building.street.json to exist")
        val env = SimEnvironment.fromJson(sampleFile.readText())
        assertEquals("Ferry Building Street Preview", env.name)
        assertEquals(EnvironmentKind.STREET, env.kind)
        assertNotNull(env.geoAnchor)
        assertNotNull(env.provenance)
        assertNotNull(env.attribution)
        assertTrue(env.attribution.contains("Mapillary"))
        assertTrue(env.attribution.contains("OpenStreetMap"))
        assertTrue((env.stations?.size ?: 0) in 1..12)
        assertTrue((env.objects?.size ?: 0) > 10)
    }
}
