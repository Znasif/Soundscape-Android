package org.scottishtecharmy.soundscape.sim.builders

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.EquirectangularPano
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import org.scottishtecharmy.soundscape.sim.geo.PerspectiveCamera
import org.scottishtecharmy.soundscape.sim.jvm.GeminiFlashClient
import org.scottishtecharmy.soundscape.sim.jvm.HttpGateway
import org.scottishtecharmy.soundscape.sim.jvm.JvmPanoramaImageSource
import org.scottishtecharmy.soundscape.sim.jvm.MetaSam31Client
import org.scottishtecharmy.soundscape.sim.jvm.Step3EntranceEvalReport
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.model.SimJson
import org.scottishtecharmy.soundscape.sim.model.VerifierVerdict
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.MapillaryDetection
import org.scottishtecharmy.soundscape.sim.sources.MapillaryImage
import org.scottishtecharmy.soundscape.sim.sources.MapillaryMapFeature
import org.scottishtecharmy.soundscape.sim.sources.MapillarySource
import org.scottishtecharmy.soundscape.sim.sources.OcrResult
import org.scottishtecharmy.soundscape.sim.sources.OsmBuilding
import org.scottishtecharmy.soundscape.sim.sources.OsmEntrance
import org.scottishtecharmy.soundscape.sim.sources.OsmPoi
import org.scottishtecharmy.soundscape.sim.sources.OverpassAreaData
import org.scottishtecharmy.soundscape.sim.sources.OverpassSource
import org.scottishtecharmy.soundscape.sim.sources.SegmentationClient
import org.scottishtecharmy.soundscape.sim.sources.SegmentedInstance
import org.scottishtecharmy.soundscape.sim.sources.TextReader
import org.scottishtecharmy.soundscape.sim.sources.Verifier
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Step3PerceptionTest {

    @Test
    fun parsesRecordedSam31ResponseFixtureAndDecodesMaskWithZeroNetwork() = runTest {
        val samFixtureDir = File("src/test/resources/fixtures/sam31")
        val gateway = HttpGateway(replayDir = samFixtureDir, replayOnly = true)
        val budget = Budget(maxSamCalls = 5)
        val client = MetaSam31Client(
            gateway = gateway,
            budget = budget,
            fixtureKeyOverride = "sam31_door_fixture"
        )

        val instances = client.segment(
            imageJpegBytes = ByteArray(16) { 1 },
            prompt = "door",
            category = SimCategory.DOOR
        )
        assertEquals(1, budget.samCalls)
        // Two co-horizontal door wings [29, 250, 43, 372] and [145, 259, 158, 368] merge into [29, 250, 158, 372]
        assertEquals(1, instances.size)
        val door = instances.first()
        assertEquals(listOf(29, 250, 158, 372), door.box)
        assertEquals(4, door.normalizedBox.size)
        assertTrue(door.normalizedBox.all { it in 0.0..1.0 })
        assertTrue(door.confidence > 0.60)
        assertNotNull(door.maskData)

        val decodedMask = MetaSam31Client.decodeOneBitMask(door.maskData!!, 110, 14)
        assertNotNull(decodedMask)
        assertEquals(110 * 14, decodedMask.size)
        assertTrue(decodedMask.any { it }, "Expected non-empty foreground pixels in decoded SAM 3.1 mask")
    }

    @Test
    fun syntheticPanoramaReprojectionAndSubCropExtraction() = runTest {
        // Create a synthetic 720x360 equirectangular panorama with a bright red patch at bearing 90 deg, elevation 0 deg
        val panoW = 720
        val panoH = 360
        val pano = BufferedImage(panoW, panoH, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until panoH) {
            for (x in 0 until panoW) {
                pano.setRGB(x, y, Color(30, 40, 50).rgb)
            }
        }
        val targetCenter = EquirectangularPano.sphericalToPixel(
            bearingDegrees = 90.0,
            elevationDegrees = 0.0,
            width = panoW,
            height = panoH,
            compassAngleDegrees = 0.0
        )
        val cx = targetCenter.u.toInt()
        val cy = targetCenter.v.toInt()
        for (dy in -10..10) {
            for (dx in -10..10) {
                pano.setRGB((cx + dx + panoW) % panoW, (cy + dy).coerceIn(0, panoH - 1), Color(250, 20, 20).rgb)
            }
        }

        val source = JvmPanoramaImageSource(maxDimension = 640)
        source.putPanoramaForTesting("synth-pano-1", pano)

        val cam = PerspectiveCamera(yawDegrees = 90.0, pitchDegrees = 0.0, hFovDegrees = 90.0, width = 320, height = 320)
        val crop = source.extractPerspectiveCrop("synth-pano-1", compassAngleDegrees = 0.0, camera = cam)
        assertNotNull(crop)

        val decodedCrop = ImageIO.read(ByteArrayInputStream(crop.jpegBytes))
        assertEquals(320, decodedCrop.width)
        assertEquals(320, decodedCrop.height)

        // Center pixel of the yaw=90 perspective view must see the bright red target placed at bearing=90
        val centerRgb = Color(decodedCrop.getRGB(160, 160))
        assertTrue(centerRgb.red > 180 && centerRgb.green < 80, "Expected red target at crop center, got $centerRgb")

        val subBytes = source.cropSubImage(crop, listOf(140, 140, 180, 180))
        val subImg = ImageIO.read(ByteArrayInputStream(subBytes))
        assertNotNull(subImg)
        assertTrue(subImg.width > 40 && subImg.height > 40)
    }

    @Test
    fun syntheticMultiRayTriangulationSingleRayFootprintAndVenueLinking() = runTest {
        val anchor = LatLon(37.79545, -122.39373)
        val localEnu = LocalEnu(anchor.latitude, anchor.longitude)

        // Building facade along north = 20m, from east = -20m to +20m
        val bCornersEnu = listOf(
            EnuPoint(-20.0, 20.0),
            EnuPoint(20.0, 20.0),
            EnuPoint(20.0, 40.0),
            EnuPoint(-20.0, 40.0)
        )
        val building = OsmBuilding(
            osmId = "way/100",
            name = "Market Hall",
            polygon = bCornersEnu.map { localEnu.enuToLatLon(it) },
            tags = mapOf("building" to "commercial")
        )

        // Named POI inside the building near east = 0, north = 22m
        val poi = OsmPoi(
            osmId = "node/200",
            name = "Blue Bottle Coffee",
            category = "cafe",
            location = localEnu.enuToLatLon(EnuPoint(0.0, 22.0))
        )

        // Two stations viewing a door at (east = 0, north = 20) -> multi-ray triangulation
        val st1Enu = EnuPoint(-6.0, 5.0)
        val st2Enu = EnuPoint(6.0, 5.0)
        val doorTrue = EnuPoint(0.0, 20.0)
        val bearing1 = org.scottishtecharmy.soundscape.sim.geo.Angles.bearingBetweenEnu(st1Enu, doorTrue)
        val bearing2 = org.scottishtecharmy.soundscape.sim.geo.Angles.bearingBetweenEnu(st2Enu, doorTrue)

        // Plus a sign right above the door with OCR text "Blue Bottle Coffee" (single ray hitting footprint)
        val sightings = listOf(
            PerceptionSighting(
                stationId = "st-1",
                stationEnu = st1Enu,
                category = SimCategory.DOOR,
                bearingDegrees = bearing1,
                elevationDegrees = -6.0,
                box = listOf(280, 240, 360, 420),
                normalizedBox = listOf(0.4375, 0.375, 0.5625, 0.6563),
                detectorConfidence = 0.82,
                verifierVerdict = VerifierVerdict.YES
            ),
            PerceptionSighting(
                stationId = "st-2",
                stationEnu = st2Enu,
                category = SimCategory.DOOR,
                bearingDegrees = bearing2,
                elevationDegrees = -6.0,
                box = listOf(275, 242, 355, 418),
                normalizedBox = listOf(0.4297, 0.3781, 0.5547, 0.6531),
                detectorConfidence = 0.85,
                verifierVerdict = VerifierVerdict.YES
            ),
            PerceptionSighting(
                stationId = "st-1",
                stationEnu = st1Enu,
                category = SimCategory.SIGN,
                bearingDegrees = bearing1,
                elevationDegrees = 4.0,
                box = listOf(270, 180, 370, 220),
                normalizedBox = listOf(0.4219, 0.2813, 0.5781, 0.3438),
                detectorConfidence = 0.88,
                verifierVerdict = VerifierVerdict.YES,
                signText = "Blue Bottle Coffee",
                signConfidence = 0.95
            )
        )

        val result = StreetAggregator.aggregate(
            localEnu = localEnu,
            buildings = listOf(building),
            entrances = emptyList(),
            pois = listOf(poi),
            mapFeatures = emptyList(),
            perceptionSightings = sightings
        )

        val doorObj = result.objects.firstOrNull { it.category == SimCategory.DOOR }
        assertNotNull(doorObj)
        val doorPos = EnuPoint.fromPositionList(doorObj.position)
        assertTrue(doorPos.distanceTo2D(doorTrue) < 0.25, "Expected triangulated door within 0.25m of truth, got $doorPos")
        assertEquals("MULTI_RAY_TRIANGULATION_FACADE_SNAP", doorObj.evidence.first().localisationMethod)
        val linkedVenue = assertNotNull(doorObj.linkedVenue)
        assertEquals("Blue Bottle Coffee", linkedVenue.name)
        assertEquals("Blue Bottle Coffee Entrance", doorObj.name)
    }

    @Test
    fun streetBuilderSupportsDryRunAndRespectsBudgetCapCleanly() = runTest {
        val anchor = LatLon(37.79545, -122.39373)
        val localEnu = LocalEnu(anchor.latitude, anchor.longitude)
        val bCornersEnu = listOf(
            EnuPoint(-15.0, 15.0),
            EnuPoint(15.0, 15.0),
            EnuPoint(15.0, 30.0),
            EnuPoint(-15.0, 30.0)
        )
        val fakeMapillary = object : MapillarySource {
            override suspend fun getImagesInBbox(bbox: org.scottishtecharmy.soundscape.sim.geo.BoundingBox, limit: Int): List<MapillaryImage> =
                listOf(
                    MapillaryImage(id = "pano-1", isPano = true, compassAngle = 0.0, capturedAtEpochMs = 1700000000000L, location = anchor)
                )
            override suspend fun getImageById(imageId: String): MapillaryImage? = null
            override suspend fun getMapFeaturesInBbox(bbox: org.scottishtecharmy.soundscape.sim.geo.BoundingBox, limit: Int): List<MapillaryMapFeature> = emptyList()
            override suspend fun getImageDetections(imageId: String): List<MapillaryDetection> = emptyList()
        }
        val fakeOverpass = object : OverpassSource {
            override suspend fun queryArea(center: LatLon, radiusMeters: Double): OverpassAreaData = OverpassAreaData(
                buildings = listOf(OsmBuilding("way/1", "Test Building", bCornersEnu.map { localEnu.enuToLatLon(it) }))
            )
        }

        val panoSource = JvmPanoramaImageSource()
        panoSource.putPanoramaForTesting("pano-1", BufferedImage(360, 180, BufferedImage.TYPE_INT_RGB))

        val budget = Budget(maxSamCalls = 2, maxGeminiCalls = 1)
        val fakeSam = object : SegmentationClient {
            override suspend fun segment(imageJpegBytes: ByteArray, prompt: String, category: SimCategory): List<SegmentedInstance> {
                budget.consumeSam(1)
                return listOf(
                    SegmentedInstance(prompt, category, listOf(280, 260, 360, 420), 640, 640, 0.85)
                )
            }
        }
        val fakeVerifier = object : Verifier {
            override suspend fun verifyCrop(cropJpegBytes: ByteArray, category: SimCategory, promptLabel: String): VerifierVerdict {
                budget.consumeGemini(1)
                return VerifierVerdict.YES
            }
        }
        val fakeReader = object : TextReader {
            override suspend fun readText(cropJpegBytes: ByteArray): OcrResult? {
                budget.consumeGemini(1)
                return OcrResult("Test Sign", 0.9)
            }
        }

        val builder = StreetBuilder(
            mapillarySource = fakeMapillary,
            overpassSource = fakeOverpass,
            panoramaImageSource = panoSource,
            segmentationClient = fakeSam,
            verifier = fakeVerifier,
            textReader = fakeReader,
            budget = budget
        )

        // 1. Test dry-run mode
        val dryEvents = builder.buildArea(StreetBuildConfig(center = anchor, dryRun = true, enablePerception = true)).toList()
        val dryPlan = dryEvents.filterIsInstance<BuildEvent.DryRunPlan>().firstOrNull()
        assertNotNull(dryPlan)
        assertEquals(0, budget.samCalls, "Dry run must not consume SAM budget")

        // 2. Test live mode with tight cap (maxSamCalls = 2, maxGeminiCalls = 1) -> stops cleanly without throwing
        val freshBuilder = StreetBuilder(
            mapillarySource = fakeMapillary,
            overpassSource = fakeOverpass,
            areaCache = AreaCache(),
            panoramaImageSource = panoSource,
            segmentationClient = fakeSam,
            verifier = fakeVerifier,
            textReader = fakeReader,
            budget = budget
        )
        val liveEvents = freshBuilder.buildArea(StreetBuildConfig(center = anchor, enablePerception = true)).toList()
        assertTrue(liveEvents.any { it is BuildEvent.Completed })
        assertTrue(liveEvents.any { it is BuildEvent.Feature })
        assertEquals(2, budget.samCalls, "Should stop cleanly at maxSamCalls = 2")
        assertEquals(1, budget.geminiCalls, "Should stop cleanly at maxGeminiCalls = 1")
    }

    @Test
    fun step3SampleAndMetricsExistAndContainPerceptionEvidence() {
        val sampleFile = File("samples/ferry-building.street.json")
        assertTrue(sampleFile.exists())
        val env = SimEnvironment.fromJson(sampleFile.readText())
        val objects = env.objects.orEmpty()
        val perceivedObjects = objects.filter { "SAM_3_1" in it.sources }
        assertTrue(perceivedObjects.isNotEmpty(), "Expected perception-derived objects with SAM_3_1 source in sample")
        assertTrue(
            perceivedObjects.all { obj -> obj.evidence.any { it.normalizedBox != null && it.localisationMethod != null } },
            "Expected normalizedBox and localisationMethod on perceived object evidence"
        )

        val metricsFile = File("docs/agent/metrics/STEP3-ferry-building.json")
        assertTrue(metricsFile.exists(), "Expected STEP3-ferry-building.json metrics file to exist")
        val report = SimJson.decodeFromString(Step3EntranceEvalReport.serializer(), metricsFile.readText())
        assertTrue(report.perceptionDetectedDoorCount >= 1)
        assertTrue(report.mergedTotalDoorCount >= 8)
        assertTrue(report.budgetUsed.samCalls in 1..60)
        assertTrue(report.budgetUsed.geminiCalls in 1..120)
    }
}
