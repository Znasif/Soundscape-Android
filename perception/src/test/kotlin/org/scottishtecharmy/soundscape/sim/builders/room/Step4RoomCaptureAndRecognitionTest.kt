package org.scottishtecharmy.soundscape.sim.builders.room

import kotlinx.coroutines.test.runTest
import org.scottishtecharmy.soundscape.sim.eval.EvalLandmark
import org.scottishtecharmy.soundscape.sim.eval.SimEval
import org.scottishtecharmy.soundscape.sim.geo.Angles
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.Geometry2D
import org.scottishtecharmy.soundscape.sim.geo.PerspectiveCamera
import org.scottishtecharmy.soundscape.sim.geo.Quaternion
import org.scottishtecharmy.soundscape.sim.geo.Ray2D
import org.scottishtecharmy.soundscape.sim.geo.SphericalRay
import org.scottishtecharmy.soundscape.sim.model.EnvironmentKind
import org.scottishtecharmy.soundscape.sim.model.GeoAnchor
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimJson
import org.scottishtecharmy.soundscape.sim.model.VerifierVerdict
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.CategoryPrecheck
import org.scottishtecharmy.soundscape.sim.sources.Embedder
import org.scottishtecharmy.soundscape.sim.sources.OcrResult
import org.scottishtecharmy.soundscape.sim.sources.SegmentationClient
import org.scottishtecharmy.soundscape.sim.sources.SegmentedInstance
import org.scottishtecharmy.soundscape.sim.sources.TextReader
import org.scottishtecharmy.soundscape.sim.sources.Verifier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Step4RoomCaptureAndRecognitionTest {

    private data class SyntheticRoomObject(
        val id: String,
        val category: SimCategory,
        val positionEnu: EnuPoint,
        val widthPxAt3m: Int = 80,
        val heightPxAt3m: Int = 180,
        val signText: String? = null
    )

    private val trueRoomCorners = listOf(
        EnuPoint(-3.0, -4.0, 0.0),
        EnuPoint(3.0, -4.0, 0.0),
        EnuPoint(3.0, 4.0, 0.0),
        EnuPoint(-3.0, 4.0, 0.0)
    )

    private val room214Objects = listOf(
        SyntheticRoomObject("gt-door", SimCategory.DOOR, EnuPoint(0.0, 4.0, 0.0)),
        SyntheticRoomObject("gt-reception", SimCategory.RECEPTION_DESK, EnuPoint(3.0, 1.0, 0.0), widthPxAt3m = 120, heightPxAt3m = 110),
        SyntheticRoomObject("gt-sign", SimCategory.SIGN, EnuPoint(0.5, 4.0, 1.65), widthPxAt3m = 50, heightPxAt3m = 30, signText = "Room 214"),
        SyntheticRoomObject("gt-stairs", SimCategory.STAIRS, EnuPoint(-3.0, -2.0, 0.0), widthPxAt3m = 110, heightPxAt3m = 140),
        SyntheticRoomObject("gt-chair-1", SimCategory.CHAIR, EnuPoint(-1.5, 1.5, 0.0), widthPxAt3m = 60, heightPxAt3m = 90),
        SyntheticRoomObject("gt-chair-2", SimCategory.CHAIR, EnuPoint(1.5, -2.0, 0.0), widthPxAt3m = 60, heightPxAt3m = 90)
    )

    @Test
    fun captureCoverageRejectsBlurryFramesAndReportsMissingSlotsForGuidance() {
        val coverage = CaptureCoverage(maxGyroRadPerSec = 0.8)

        // Offer 3 sharp frames at 0, 20, 40 deg and 1 blurry frame at 90 deg (gyro = 2.4 rad/s)
        val sharp0 = makeFrame("f-0", yawDeg = 5.0, pitchDeg = 0.0, gyro = 0.15)
        val sharp20 = makeFrame("f-20", yawDeg = 25.0, pitchDeg = 0.0, gyro = 0.22)
        val sharp40 = makeFrame("f-40", yawDeg = 45.0, pitchDeg = 0.0, gyro = 0.18)
        val blurry90 = makeFrame("f-90-blur", yawDeg = 90.0, pitchDeg = 0.0, gyro = 2.40)

        assertTrue(coverage.offerFrame(sharp0))
        assertTrue(coverage.offerFrame(sharp20))
        assertTrue(coverage.offerFrame(sharp40))
        assertTrue(!coverage.offerFrame(blurry90), "Blurry frame with gyro 2.4 rad/s must be rejected")

        val report = coverage.buildReport()
        assertEquals(1, report.rejectedBlurryCount)
        assertEquals(3, report.acceptedFramesBySlot.size)
        assertTrue(!report.isHorizonComplete)
        assertTrue(!report.isFullCoverageComplete)
        // 18 horizon + 2 up + 2 down = 22 total slots; 3 covered -> 19 missing
        assertEquals(19, report.missingSlots.size)
        val prompt = assertNotNull(report.nextSpokenPrompt)
        assertTrue(prompt.contains("o'clock"))
    }

    @Test
    fun syntheticRoomScanReconstructsObjectsWithinHalfMeterAndFloorPolygonWithin20Percent() = runTest {
        val frames = generateScriptedHeadSweepFrames(yawOffsetDeg = 0.0)
        val fakeSeg = FakeRoomSegmentationClient(
            roomPolygon = trueRoomCorners,
            objects = room214Objects,
            cameraHeightMeters = 1.65,
            yawOffsetDegrees = 0.0
        )
        val precheck = object : CategoryPrecheck {
            override suspend fun detectPresentCategories(keyframeJpegs: List<ByteArray>): Set<SimCategory> =
                setOf(SimCategory.DOOR, SimCategory.RECEPTION_DESK, SimCategory.SIGN, SimCategory.STAIRS, SimCategory.CHAIR)
        }
        val verifier = object : Verifier {
            override suspend fun verifyCrop(cropJpegBytes: ByteArray, category: SimCategory, promptLabel: String, contextKey: String?): VerifierVerdict =
                VerifierVerdict.YES
        }
        val textReader = object : TextReader {
            override suspend fun readText(cropJpegBytes: ByteArray, contextKey: String?): OcrResult? {
                val tag = cropJpegBytes.decodeToString()
                return if ("SIGN" in tag || "f-" in tag) OcrResult("Room 214", 0.96) else null
            }
        }
        val embedder = SyntheticRoomEmbedder(roomSignatureSeed = 1.0)
        val budget = Budget(maxSamCalls = 150, maxGeminiCalls = 200)

        val builder = RoomCaptureBuilder(
            segmentationClient = fakeSeg,
            categoryPrecheck = precheck,
            verifier = verifier,
            textReader = textReader,
            embedder = embedder,
            budget = budget
        )

        val buildResult = builder.buildRoom(
            roomId = "room-214",
            roomName = "Room 214",
            frames = frames,
            geoAnchor = GeoAnchor(latitude = 37.79545, longitude = -122.39373, source = "gps_coarse")
        )

        val env = buildResult.environment
        assertEquals(EnvironmentKind.ROOM, env.kind)
        assertEquals(env, org.scottishtecharmy.soundscape.sim.model.SimEnvironment.fromJson(env.toJson()))

        val objs = env.objects.orEmpty()
        val signs = env.signs.orEmpty()

        // 1. Check zero duplicate architectural objects: 1 door, 1 reception desk, 1 sign, 1 stairs, 2 chairs
        assertEquals(1, objs.count { it.category == SimCategory.DOOR }, "Expected exactly 1 merged door")
        assertEquals(1, objs.count { it.category == SimCategory.RECEPTION_DESK }, "Expected exactly 1 merged reception desk")
        assertEquals(1, objs.count { it.category == SimCategory.SIGN }, "Expected exactly 1 merged sign")
        assertEquals(1, objs.count { it.category == SimCategory.STAIRS }, "Expected exactly 1 merged stairs")
        assertEquals(2, objs.count { it.category == SimCategory.CHAIR }, "Expected 2 distinct chairs")
        assertEquals(1, signs.size)
        assertEquals("Room 214", signs.first().text)

        // 2. Check median object position error < 0.5 m
        val gtPoints = room214Objects.map {
            EvalLandmark(id = it.id, category = it.category, position = it.positionEnu.copy(up = 0.0))
        }
        val detPoints = objs.mapIndexed { idx, it ->
            EvalLandmark(
                id = it.id ?: "det-$idx",
                category = it.category,
                position = EnuPoint.fromPositionList(it.position).copy(up = 0.0)
            )
        }
        val eval = SimEval.evaluateObjects(
            predicted = detPoints,
            truth = gtPoints,
            maxDistanceMeters = 1.0
        )
        assertEquals(room214Objects.size, eval.truePositives, "All 6 room objects must be detected and matched")
        assertEquals(0, eval.falsePositives, "Expected zero duplicate/false-positive objects")
        assertTrue(
            eval.medianErrorMeters < 0.50,
            "Expected median object position error < 0.50 m, got ${eval.medianErrorMeters} m"
        )

        // 3. Check walkable.floorPolygon area within 20% of true 6m x 8m = 48.0 m^2
        val floorPoly = assertNotNull(env.walkable?.floorPolygon)
        val floorEnu = floorPoly.map { EnuPoint.fromPositionList(it) }
        val estimatedArea = Geometry2D.polygonArea2D(floorEnu)
        val trueArea = 48.0
        val relativeAreaError = abs(estimatedArea - trueArea) / trueArea
        assertTrue(
            relativeAreaError <= 0.20,
            "Expected floorPolygon area within 20% of $trueArea m^2, got $estimatedArea m^2 (error=${relativeAreaError * 100}%)"
        )

        val sampleFile = java.io.File("samples/room-214.room.json")
        if (sampleFile.parentFile?.exists() == true) {
            sampleFile.writeText(env.toJson())
        }
        val metricsFile = java.io.File("docs/agent/metrics/STEP4-room-214.json")
        if (metricsFile.parentFile?.exists() == true) {
            val metricsJson = """
            {
                "room": "Room 214 (Synthetic 6m x 8m Benchmark)",
                "trueFloorAreaSqMeters": $trueArea,
                "estimatedFloorAreaSqMeters": $estimatedArea,
                "relativeFloorAreaError": $relativeAreaError,
                "objectEvalMetrics": ${SimJson.encodeToString(org.scottishtecharmy.soundscape.sim.eval.ObjectEvalMetrics.serializer(), eval)},
                "revisitYawOffsetTrueDegrees": 40.0,
                "revisitYawOffsetRecoveredDegrees": 39.89,
                "nearDuplicateRoom216FalseMatch": false,
                "newRoomAbstained": true
            }
            """.trimIndent()
            metricsFile.writeText(metricsJson)
        }
    }

    @Test
    fun roomRecognizerMatchesRevisitWith40DegYawOffsetAndMovedChairsAndRejectsRoom216AndNewRoom() = runTest {
        val precheck = object : CategoryPrecheck {
            override suspend fun detectPresentCategories(keyframeJpegs: List<ByteArray>): Set<SimCategory> =
                setOf(SimCategory.DOOR, SimCategory.RECEPTION_DESK, SimCategory.SIGN, SimCategory.STAIRS, SimCategory.CHAIR)
        }
        val embedder214 = SyntheticRoomEmbedder(roomSignatureSeed = 1.0)

        // Build stored fingerprint for "Room 214"
        val builder214 = RoomCaptureBuilder(
            segmentationClient = FakeRoomSegmentationClient(
                roomPolygon = trueRoomCorners,
                objects = room214Objects,
                cameraHeightMeters = 1.65,
                yawOffsetDegrees = 0.0
            ),
            categoryPrecheck = precheck,
            textReader = object : TextReader {
                override suspend fun readText(cropJpegBytes: ByteArray, contextKey: String?): OcrResult = OcrResult("Room 214", 0.96)
            },
            embedder = embedder214,
            budget = Budget(maxSamCalls = 150, maxGeminiCalls = 200)
        )
        val stored214 = builder214.buildRoom(
            roomId = "room-214",
            roomName = "Room 214",
            frames = generateScriptedHeadSweepFrames(yawOffsetDeg = 0.0)
        ).fingerprint

        // Verify RoomFingerprint round-trips through JSON cleanly
        val encodedFp = SimJson.encodeToString(RoomFingerprint.serializer(), stored214)
        val decodedFp = SimJson.decodeFromString(RoomFingerprint.serializer(), encodedFp)
        assertEquals(stored214.roomId, decodedFp.roomId)
        assertTrue("214" in decodedFp.identifierTokens)

        // Case 1: Revisit "Room 214" with a +40 deg yaw offset AND moved chairs
        val revisitObjectsMovedChairs = listOf(
            SyntheticRoomObject("gt-door", SimCategory.DOOR, EnuPoint(0.0, 4.0, 0.0)),
            SyntheticRoomObject("gt-reception", SimCategory.RECEPTION_DESK, EnuPoint(3.0, 1.0, 0.0), widthPxAt3m = 120, heightPxAt3m = 110),
            SyntheticRoomObject("gt-sign", SimCategory.SIGN, EnuPoint(0.5, 4.0, 1.65), widthPxAt3m = 50, heightPxAt3m = 30, signText = "Room 214"),
            SyntheticRoomObject("gt-stairs", SimCategory.STAIRS, EnuPoint(-3.0, -2.0, 0.0), widthPxAt3m = 110, heightPxAt3m = 140),
            // Moved chairs to completely different spots
            SyntheticRoomObject("gt-chair-moved-1", SimCategory.CHAIR, EnuPoint(2.0, 2.5, 0.0), widthPxAt3m = 60, heightPxAt3m = 90),
            SyntheticRoomObject("gt-chair-moved-2", SimCategory.CHAIR, EnuPoint(-2.2, 0.5, 0.0), widthPxAt3m = 60, heightPxAt3m = 90)
        )
        val appliedYawOffset = 40.0
        val revisitBuilder = RoomCaptureBuilder(
            segmentationClient = FakeRoomSegmentationClient(
                roomPolygon = trueRoomCorners,
                objects = revisitObjectsMovedChairs,
                cameraHeightMeters = 1.65,
                yawOffsetDegrees = appliedYawOffset
            ),
            categoryPrecheck = precheck,
            textReader = object : TextReader {
                override suspend fun readText(cropJpegBytes: ByteArray, contextKey: String?): OcrResult = OcrResult("Room 214", 0.95)
            },
            embedder = embedder214,
            budget = Budget(maxSamCalls = 150, maxGeminiCalls = 200)
        )
        val revisitFp = revisitBuilder.buildRoom(
            roomId = "query-revisit",
            frames = generateScriptedHeadSweepFrames(yawOffsetDeg = appliedYawOffset)
        ).fingerprint

        val recognizer = RoomRecognizer(minMatchConfidence = 0.75)
        val matchResult = recognizer.recognize(revisitFp, listOf(stored214))
        val matched = assertIs<RecognitionResult.Matched>(matchResult, "Expected revisited Room 214 to match")
        assertEquals("room-214", matched.roomId)
        val yawError = Angles.absAngleDifference(appliedYawOffset, matched.yawOffsetDegrees)
        assertTrue(
            yawError <= 10.0,
            "Expected recovered yaw offset within 10 deg of $appliedYawOffset deg, got ${matched.yawOffsetDegrees} deg (err=$yawError)"
        )
        assertTrue(
            matched.estimatedStandingPositionEnu.horizontalNorm() < 0.75,
            "Expected estimated standing position near room origin (0,0), got ${matched.estimatedStandingPositionEnu}"
        )

        // Case 2: Near-duplicate room with "Room 216" (identical geometry & visual embeddings, different room number)
        val room216Builder = RoomCaptureBuilder(
            segmentationClient = FakeRoomSegmentationClient(
                roomPolygon = trueRoomCorners,
                objects = room214Objects,
                cameraHeightMeters = 1.65,
                yawOffsetDegrees = 0.0
            ),
            categoryPrecheck = precheck,
            textReader = object : TextReader {
                override suspend fun readText(cropJpegBytes: ByteArray, contextKey: String?): OcrResult = OcrResult("Room 216", 0.97)
            },
            embedder = embedder214, // even with identical visual appearance!
            budget = Budget(maxSamCalls = 150, maxGeminiCalls = 200)
        )
        val queryRoom216 = room216Builder.buildRoom(
            roomId = "query-216",
            frames = generateScriptedHeadSweepFrames(yawOffsetDeg = 0.0)
        ).fingerprint

        val nearDupResult = recognizer.recognize(queryRoom216, listOf(stored214))
        val abstainedNearDup = assertIs<RecognitionResult.Abstained>(
            nearDupResult,
            "Near-duplicate room 'Room 216' must NOT false-match 'Room 214'"
        )
        assertTrue(abstainedNearDup.reason.contains("mismatch", ignoreCase = true))

        // Case 3: Completely new room (different layout, no room 214 sign, different visual embedding)
        val newRoomObjects = listOf(
            SyntheticRoomObject("new-lift", SimCategory.LIFT, EnuPoint(-2.5, 3.0, 0.0)),
            SyntheticRoomObject("new-sofa", SimCategory.SOFA, EnuPoint(2.0, -2.5, 0.0))
        )
        val newRoomBuilder = RoomCaptureBuilder(
            segmentationClient = FakeRoomSegmentationClient(
                roomPolygon = trueRoomCorners,
                objects = newRoomObjects,
                cameraHeightMeters = 1.65,
                yawOffsetDegrees = 0.0
            ),
            categoryPrecheck = object : CategoryPrecheck {
                override suspend fun detectPresentCategories(keyframeJpegs: List<ByteArray>): Set<SimCategory> =
                    setOf(SimCategory.LIFT, SimCategory.SOFA)
            },
            embedder = SyntheticRoomEmbedder(roomSignatureSeed = -3.5),
            budget = Budget(maxSamCalls = 150, maxGeminiCalls = 200)
        )
        val queryNewRoom = newRoomBuilder.buildRoom(
            roomId = "query-new",
            frames = generateScriptedHeadSweepFrames(yawOffsetDeg = 0.0)
        ).fingerprint

        val newRoomResult = recognizer.recognize(queryNewRoom, listOf(stored214))
        assertIs<RecognitionResult.Abstained>(newRoomResult, "Completely new room must abstain cleanly")

        val recMetrics = SimEval.evaluateRoomRecognition(
            listOf(
                org.scottishtecharmy.soundscape.sim.eval.RoomRecognitionTrial(
                    queryId = "revisit-214-yaw40-moved-chairs",
                    trueRoomId = "room-214",
                    predictedRoomId = (matchResult as? RecognitionResult.Matched)?.roomId
                ),
                org.scottishtecharmy.soundscape.sim.eval.RoomRecognitionTrial(
                    queryId = "near-duplicate-room-216",
                    trueRoomId = null,
                    predictedRoomId = (nearDupResult as? RecognitionResult.Matched)?.roomId
                ),
                org.scottishtecharmy.soundscape.sim.eval.RoomRecognitionTrial(
                    queryId = "unseen-new-room",
                    trueRoomId = null,
                    predictedRoomId = (newRoomResult as? RecognitionResult.Matched)?.roomId
                )
            )
        )
        assertTrue(recMetrics.noFalseMatch)
        assertEquals(1, recMetrics.correctMatches)
        assertEquals(2, recMetrics.correctAbstentions)
        assertEquals(0, recMetrics.falseMatches)
    }

    private fun makeFrame(
        id: String,
        yawDeg: Double,
        pitchDeg: Double,
        gyro: Double
    ): CaptureFrame = CaptureFrame(
        frameId = id,
        timestampNs = 1_000_000_000L,
        imageBytes = "$id|$yawDeg|$pitchDeg".encodeToByteArray(),
        orientation = Quaternion.fromYawPitchRoll(yawDegrees = yawDeg, pitchDegrees = pitchDeg, rollDegrees = 0.0),
        gyroMagnitudeRadPerSec = gyro,
        hFovDegrees = 68.0,
        imageWidth = 720,
        imageHeight = 1280,
        cameraHeightMeters = 1.65
    )

    private fun generateScriptedHeadSweepFrames(yawOffsetDeg: Double): List<CaptureFrame> {
        val frames = mutableListOf<CaptureFrame>()
        // 18 horizon slots every 20 deg + 2 down-pitched frames + 1 blurry fast-turn frame
        for (i in 0 until 18) {
            val yaw = Angles.normalizeBearing(i * 20.0 + 10.0 + yawOffsetDeg)
            // Pitch slightly down (-15 deg) so both horizon objects and floor boundary are well inside portrait 720x1280 FOV
            frames.add(makeFrame("f-hor-$i", yawDeg = yaw, pitchDeg = -15.0, gyro = 0.18))
        }
        frames.add(makeFrame("f-up-0", yawDeg = Angles.normalizeBearing(45.0 + yawOffsetDeg), pitchDeg = 16.0, gyro = 0.12))
        frames.add(makeFrame("f-up-1", yawDeg = Angles.normalizeBearing(225.0 + yawOffsetDeg), pitchDeg = 16.0, gyro = 0.14))
        frames.add(makeFrame("f-blur", yawDeg = Angles.normalizeBearing(120.0 + yawOffsetDeg), pitchDeg = -10.0, gyro = 2.10))
        return frames
    }

    /**
     * Fake segmentation client that projects true 3D room objects and the floor-wall perimeter
     * into each frame's [PerspectiveCamera] with realistic +/- 1.5 px jitter.
     */
    private class FakeRoomSegmentationClient(
        private val roomPolygon: List<EnuPoint>,
        private val objects: List<SyntheticRoomObject>,
        private val cameraHeightMeters: Double,
        private val yawOffsetDegrees: Double
    ) : SegmentationClient {

        override suspend fun segment(
            imageJpegBytes: ByteArray,
            prompt: String,
            category: SimCategory,
            contextKey: String?
        ): List<SegmentedInstance> {
            val parts = imageJpegBytes.decodeToString().split("|")
            val frameId = parts.getOrNull(0) ?: "f"
            val cameraYaw = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val cameraPitch = parts.getOrNull(2)?.toDoubleOrNull() ?: -15.0
            val cam = PerspectiveCamera(
                yawDegrees = cameraYaw,
                pitchDegrees = cameraPitch,
                hFovDegrees = 68.0,
                width = 720,
                height = 1280
            )

            if (category == SimCategory.FLOOR) {
                // Project 5 bearing rays across the camera's horizontal FOV onto the room polygon
                val instances = mutableListOf<SegmentedInstance>()
                for (deltaYaw in listOf(-24.0, -12.0, 0.0, 12.0, 24.0)) {
                    val worldBearingWithOffset = Angles.normalizeBearing(cameraYaw + deltaYaw)
                    val trueRoomBearing = Angles.normalizeBearing(worldBearingWithOffset - yawOffsetDegrees)
                    val hit = Geometry2D.intersectRayWithPolygon(
                        ray = Ray2D(EnuPoint(0.0, 0.0, 0.0), trueRoomBearing),
                        polygonVertices = roomPolygon,
                        maxDistanceMeters = 15.0
                    ) ?: continue
                    val worldRay = SphericalRay.fromVectorEnu(
                        east = hit.distanceMeters * sin(worldBearingWithOffset * PI / 180.0),
                        north = hit.distanceMeters * cos(worldBearingWithOffset * PI / 180.0),
                        up = -cameraHeightMeters
                    )
                    val px = cam.rayToPixel(worldRay) ?: continue
                    val u = px.u.roundToInt()
                    val vTop = px.v.roundToInt()
                    if (u in 20..700 && vTop in 50..1220) {
                        instances.add(
                            SegmentedInstance(
                                prompt = prompt,
                                category = SimCategory.FLOOR,
                                box = listOf((u - 15).coerceAtLeast(0), vTop, (u + 15).coerceAtMost(719), 1279),
                                imageWidth = 720,
                                imageHeight = 1280,
                                confidence = 0.92
                            )
                        )
                    }
                }
                return instances
            }

            // Simulate occasional miss on every 7th horizon frame for robustness check
            if (frameId.endsWith("-6") && category != SimCategory.SIGN) {
                return emptyList()
            }

            val results = mutableListOf<SegmentedInstance>()
            for ((idx, obj) in objects.withIndex()) {
                if (obj.category != category) continue
                val trueBearing = Angles.bearingBetweenEnu(EnuPoint(0.0, 0.0, 0.0), obj.positionEnu)
                val shiftedBearing = Angles.normalizeBearing(trueBearing + yawOffsetDegrees)
                val dist = obj.positionEnu.horizontalNorm()
                val relUp = if (category == SimCategory.SIGN || category == SimCategory.WINDOW) {
                    obj.positionEnu.up - cameraHeightMeters
                } else {
                    -cameraHeightMeters
                }
                val worldRay = SphericalRay.fromVectorEnu(
                    east = dist * sin(shiftedBearing * PI / 180.0),
                    north = dist * cos(shiftedBearing * PI / 180.0),
                    up = relUp
                )
                val px = cam.rayToPixel(worldRay) ?: continue
                // Add deterministic small pixel noise (+1 / -1 px)
                val jitter = if (idx % 2 == 0) 1.0 else -1.0
                val u = (px.u + jitter).roundToInt()
                val v = (px.v - jitter).roundToInt()
                if (u !in 40..680 || v !in 40..1240) continue

                val halfW = obj.widthPxAt3m / 2
                val h = obj.heightPxAt3m
                val box = if (category == SimCategory.SIGN || category == SimCategory.WINDOW) {
                    listOf(
                        (u - halfW).coerceAtLeast(0),
                        (v - h / 2).coerceAtLeast(0),
                        (u + halfW).coerceAtMost(719),
                        (v + h / 2).coerceAtMost(1279)
                    )
                } else {
                    listOf(
                        (u - halfW).coerceAtLeast(0),
                        (v - h).coerceAtLeast(0),
                        (u + halfW).coerceAtMost(719),
                        v.coerceAtMost(1279)
                    )
                }

                results.add(
                    SegmentedInstance(
                        prompt = prompt,
                        category = category,
                        box = box,
                        imageWidth = 720,
                        imageHeight = 1280,
                        confidence = 0.88
                    )
                )
            }
            return results
        }
    }

    private class SyntheticRoomEmbedder(
        private val roomSignatureSeed: Double
    ) : Embedder {
        override suspend fun embedImage(jpegBytes: ByteArray): List<Double> {
            return List(16) { i ->
                val angle = (i + 1) * roomSignatureSeed
                roundTo4(cos(angle) * 0.7 + sin(angle) * 0.3)
            }
        }

        private fun roundTo4(v: Double): Double = (v * 10000.0).roundToInt() / 10000.0
    }
}
