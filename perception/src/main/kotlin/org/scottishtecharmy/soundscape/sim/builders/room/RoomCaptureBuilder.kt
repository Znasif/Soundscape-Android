package org.scottishtecharmy.soundscape.sim.builders.room

import org.scottishtecharmy.soundscape.sim.geo.Angles
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.Geometry2D
import org.scottishtecharmy.soundscape.sim.geo.Geohash
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.geo.Ray2D
import org.scottishtecharmy.soundscape.sim.model.EnvironmentKind
import org.scottishtecharmy.soundscape.sim.model.Evidence
import org.scottishtecharmy.soundscape.sim.model.Fingerprint
import org.scottishtecharmy.soundscape.sim.model.GeoAnchor
import org.scottishtecharmy.soundscape.sim.model.ObjectBearingSignature
import org.scottishtecharmy.soundscape.sim.model.Provenance
import org.scottishtecharmy.soundscape.sim.model.Sign
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.model.SimObject
import org.scottishtecharmy.soundscape.sim.model.Station
import org.scottishtecharmy.soundscape.sim.model.VerifierVerdict
import org.scottishtecharmy.soundscape.sim.model.Walkable
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.CategoryPrecheck
import org.scottishtecharmy.soundscape.sim.sources.Embedder
import org.scottishtecharmy.soundscape.sim.sources.SegmentationClient
import org.scottishtecharmy.soundscape.sim.sources.TextReader
import org.scottishtecharmy.soundscape.sim.sources.Verifier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.tan

/**
 * Output of [RoomCaptureBuilder.buildRoom].
 */
data class RoomBuildResult(
    val environment: SimEnvironment,
    val fingerprint: RoomFingerprint,
    val coverageReport: CoverageReport
)

/**
 * Builds an indoor [SimEnvironment] (`kind = EnvironmentKind.ROOM`) and [RoomFingerprint]
 * from a rotation-only head-turn scan captured with Ray-Ban Meta smart glasses.
 *
 * Pure Kotlin (`commonMain`-ready).
 */
class RoomCaptureBuilder(
    private val segmentationClient: SegmentationClient,
    private val categoryPrecheck: CategoryPrecheck,
    private val verifier: Verifier? = null,
    private val textReader: TextReader? = null,
    private val embedder: Embedder? = null,
    private val budget: Budget = Budget(maxSamCalls = 80, maxGeminiCalls = 200),
    private val maxGyroRadPerSec: Double = 0.8,
    private val mergeDistanceMeters: Double = 0.65,
    private val mergeBearingDegrees: Double = 10.0,
    private val cropExtractor: (imageBytes: ByteArray, box: List<Int>) -> ByteArray = { bytes, _ -> bytes }
) {

    /**
     * Processes a head-turn scan of [CaptureFrame]s into a [RoomBuildResult].
     */
    suspend fun buildRoom(
        roomId: String,
        roomName: String? = null,
        frames: List<CaptureFrame>,
        geoAnchor: GeoAnchor? = null,
        createdAtEpochMs: Long = 1710000000000L
    ): RoomBuildResult {
        // 1. Evaluate spherical coverage and filter out high-gyro blurry frames
        val coverage = CaptureCoverage(maxGyroRadPerSec = maxGyroRadPerSec)
        val coverageReport = coverage.evaluate(frames)
        val keyframes = coverageReport.selectedKeyframes

        if (keyframes.isEmpty()) {
            val emptyEnv = SimEnvironment(
                name = roomName ?: "Scanned Room",
                kind = EnvironmentKind.ROOM,
                geoAnchor = geoAnchor,
                provenance = Provenance(
                    createdAtEpochMs = createdAtEpochMs,
                    builderVersion = "0.4.0-step4",
                    sources = listOf("MWDAT_GLASSES", "SAM_3_1", "GEMINI_FLASH")
                )
            )
            return RoomBuildResult(
                environment = emptyEnv,
                fingerprint = RoomFingerprint(roomId = roomId, roomName = roomName, geoAnchor = geoAnchor),
                coverageReport = coverageReport
            )
        }

        // 2. Category precheck on 4-6 keyframes evenly spread around the room
        val precheckFrames = selectSpreadKeyframes(keyframes, maxCount = 6)
        val presentCategories = if (budget.canConsumeGemini(1)) {
            categoryPrecheck.detectPresentCategories(precheckFrames.map { it.imageBytes })
        } else {
            DEFAULT_INDOOR_CATEGORIES
        }

        // Categories to segment per keyframe: present categories + FLOOR
        val categoriesToSegment = LinkedHashSet<SimCategory>().apply {
            add(SimCategory.FLOOR)
            addAll(presentCategories.filter { it != SimCategory.FLOOR })
        }

        // 3. Segment keyframes and collect floor boundary rays & object sightings
        val floorBoundaryPoints = mutableListOf<Pair<Double, EnuPoint>>() // (bearing, EnuPoint)
        val rawSightings = mutableListOf<RawRoomSighting>()

        for (frame in keyframes) {
            val cam = frame.toPerspectiveCamera()
            for (cat in categoriesToSegment) {
                if (!budget.canConsumeSam(1)) break
                val prompt = categoryPrompt(cat)
                val instances = segmentationClient.segment(
                    imageJpegBytes = frame.imageBytes,
                    prompt = prompt,
                    category = cat
                )

                for (inst in instances) {
                    if (inst.confidence < 0.28 || inst.box.size < 4) continue

                    if (cat == SimCategory.FLOOR) {
                        // Extract upper edge (inst.topY) where the floor meets the wall across this floor segment
                        val sampleXs = listOf(
                            0.75 * inst.box[0] + 0.25 * inst.box[2],
                            inst.bottomCenterX,
                            0.25 * inst.box[0] + 0.75 * inst.box[2]
                        ).distinct()
                        for (u in sampleXs) {
                            val ray = cam.pixelToRay(u, inst.topY)
                            val pt = Geometry2D.groundPlanePoint(
                                origin = EnuPoint(0.0, 0.0, 0.0),
                                bearingDegrees = ray.bearingDegrees,
                                elevationDegrees = ray.elevationDegrees,
                                cameraHeightMeters = frame.cameraHeightMeters,
                                minPitchBelowHorizonDegrees = 4.0,
                                maxDistanceMeters = 18.0
                            )
                            if (pt != null) {
                                floorBoundaryPoints.add(ray.bearingDegrees to pt)
                            }
                        }
                    } else {
                        // For floor-contacting objects use bottom-center (bottomCenterX, bottomY);
                        // for elevated wall objects (SIGN, WINDOW) use center (centerX, centerY)
                        val isWallElevated = (cat == SimCategory.SIGN || cat == SimCategory.WINDOW)
                        val refU = if (isWallElevated) inst.centerX else inst.bottomCenterX
                        val refV = if (isWallElevated) inst.centerY else inst.bottomY
                        val ray = cam.pixelToRay(refU, refV)

                        rawSightings.add(
                            RawRoomSighting(
                                frame = frame,
                                category = cat,
                                promptLabel = prompt,
                                bearingDegrees = ray.bearingDegrees,
                                elevationDegrees = ray.elevationDegrees,
                                box = inst.box,
                                normalizedBox = inst.normalizedBox,
                                confidence = inst.confidence
                            )
                        )
                    }
                }
            }
        }

        // 4. Construct simplified walkable floor polygon from floor-wall boundary samples
        val floorPolygonEnu = buildSimplifiedFloorPolygon(floorBoundaryPoints)

        // 5. Project each sighting to 3D local ENU (east, up, north)
        val localizedSightings = rawSightings.mapNotNull { sighting ->
            localizeSighting(sighting, floorPolygonEnu)
        }

        // 6. Merge detections across overlapping frames (within 0.65m or within 10 deg & 25% distance)
        val mergedClusters = mergeRoomSightings(localizedSightings)

        // 7. Verify low-confidence clusters & run OCR on signs/doors
        val objects = mutableListOf<SimObject>()
        val signs = mutableListOf<Sign>()
        var objIdx = 1
        var signIdx = 1

        for (cluster in mergedClusters) {
            val best = cluster.maxByOrNull { it.sighting.confidence }!!.sighting
            val cropBytes = cropExtractor(best.frame.imageBytes, best.box)

            // Verify low-confidence clusters (0.28..0.72) when verifier is provided
            val verdict = if (verifier != null && best.confidence < 0.72 && budget.canConsumeGemini(1)) {
                verifier.verifyCrop(cropBytes, best.category, best.promptLabel)
            } else {
                VerifierVerdict.YES
            }
            if (verdict == VerifierVerdict.NO) continue

            // Average 3D position weighted by detector confidence
            val totalWeight = cluster.sumOf { it.sighting.confidence }.coerceAtLeast(1e-6)
            val avgEast = cluster.sumOf { it.position.east * it.sighting.confidence } / totalWeight
            val avgNorth = cluster.sumOf { it.position.north * it.sighting.confidence } / totalWeight
            val avgUp = cluster.sumOf { it.position.up * it.sighting.confidence } / totalWeight
            val meanConf = (cluster.sumOf { it.sighting.confidence } / cluster.size).coerceIn(0.0, 1.0)

            var ocrText: String? = null
            var ocrConf = meanConf
            if ((best.category == SimCategory.SIGN || best.category == SimCategory.DOOR) &&
                textReader != null &&
                budget.canConsumeGemini(1)
            ) {
                val ocr = textReader.readText(cropBytes)
                if (ocr != null && ocr.text.isNotBlank()) {
                    ocrText = ocr.text.trim()
                    ocrConf = ocr.confidence
                }
            }

            val objId = "room-${best.category.name.lowercase()}-${objIdx++}"
            val displayName = when {
                !ocrText.isNullOrBlank() && best.category == SimCategory.SIGN -> ocrText
                !ocrText.isNullOrBlank() && best.category == SimCategory.DOOR -> "$ocrText Door"
                else -> defaultCategoryLabel(best.category)
            }

            val evidences = cluster.map { item ->
                Evidence(
                    stationId = item.sighting.frame.frameId,
                    bearingDegrees = round2(item.sighting.bearingDegrees),
                    elevationDegrees = round2(item.sighting.elevationDegrees),
                    box = item.sighting.box,
                    normalizedBox = item.sighting.normalizedBox,
                    detectorConfidence = round3(item.sighting.confidence),
                    verifierResult = verdict,
                    localisationMethod = item.localisationMethod
                )
            }

            val posList = listOf(round3(avgEast), round3(avgUp), round3(avgNorth))
            objects.add(
                SimObject(
                    id = objId,
                    name = displayName,
                    category = best.category,
                    position = posList,
                    confidence = round3(meanConf),
                    sources = listOf("sam-3.1"),
                    text = ocrText,
                    evidence = evidences
                )
            )

            if (best.category == SimCategory.SIGN && !ocrText.isNullOrBlank()) {
                signs.add(
                    Sign(
                        id = "room-sign-${signIdx++}",
                        text = ocrText,
                        confidence = round3(ocrConf),
                        position = posList,
                        stationId = best.frame.frameId,
                        bearingDegrees = round2(best.bearingDegrees)
                    )
                )
            }
        }

        // 8. Compute 3-5 keyframe embeddings via Embedder for RoomFingerprint
        val embeddingFrames = selectSpreadKeyframes(keyframes, maxCount = 5)
        val frameEmbeddings = mutableListOf<FrameEmbedding>()
        if (embedder != null) {
            for (ef in embeddingFrames) {
                val vec = embedder.embedImage(ef.imageBytes)
                if (vec.isNotEmpty()) {
                    frameEmbeddings.add(
                        FrameEmbedding(
                            frameId = ef.frameId,
                            bearingDegrees = round2(ef.eulerAngles.yawDegrees),
                            vector = vec
                        )
                    )
                }
            }
        }

        val walkableSurface = if (floorPolygonEnu.size >= 3) {
            Walkable(
                floorPolygon = floorPolygonEnu.map { pt ->
                    listOf(round3(pt.east), 0.0, round3(pt.north))
                }
            )
        } else null

        val stations = keyframes.map { kf ->
            Station(
                id = kf.frameId,
                position = listOf(0.0, round3(kf.cameraHeightMeters), 0.0),
                compassAngle = round2(kf.eulerAngles.yawDegrees),
                capturedAtEpochMs = kf.timestampNs / 1_000_000L,
                isPano = false
            )
        }

        val resolvedRoomName = roomName
            ?: signs.firstOrNull()?.text
            ?: "Scanned Room"

        val baseEnv = SimEnvironment(
            name = resolvedRoomName,
            kind = EnvironmentKind.ROOM,
            geoAnchor = geoAnchor,
            provenance = Provenance(
                createdAtEpochMs = createdAtEpochMs,
                builderVersion = "0.4.0-step4",
                sources = listOf("MWDAT_GLASSES", "SAM_3_1", "GEMINI_FLASH"),
                newestCaptureEpochMs = keyframes.maxOfOrNull { it.timestampNs / 1_000_000L },
                geohash7 = geoAnchor?.let { Geohash.encode(it.latitude, it.longitude, precision = 7) }
            ),
            objects = objects,
            signs = signs,
            stations = stations,
            walkable = walkableSurface
        )

        val fingerprint = RoomRecognizer.fromEnvironment(roomId, baseEnv, frameEmbeddings)
        val envWithFp = baseEnv.copy(
            fingerprint = Fingerprint(
                normalizedTexts = fingerprint.normalizedTexts,
                distinctiveTokens = fingerprint.identifierTokens,
                embeddings = fingerprint.frameEmbeddings.map { it.vector },
                objectBearings = fingerprint.landmarks.mapIndexed { idx, lm ->
                    ObjectBearingSignature(
                        objectId = "lm-$idx",
                        category = lm.category,
                        bearingDegrees = lm.bearingDegrees,
                        distanceMeters = lm.distanceMeters,
                        text = lm.label
                    )
                }
            )
        )
        return RoomBuildResult(
            environment = envWithFp,
            fingerprint = fingerprint,
            coverageReport = coverageReport
        )
    }

    private data class RawRoomSighting(
        val frame: CaptureFrame,
        val category: SimCategory,
        val promptLabel: String,
        val bearingDegrees: Double,
        val elevationDegrees: Double,
        val box: List<Int>,
        val normalizedBox: List<Double>,
        val confidence: Double
    )

    private data class LocalizedRoomSighting(
        val sighting: RawRoomSighting,
        val position: EnuPoint,
        val distanceMeters: Double,
        val localisationMethod: String
    )

    private fun localizeSighting(
        s: RawRoomSighting,
        floorPolygonEnu: List<EnuPoint>
    ): LocalizedRoomSighting? {
        val isWallElevated = (s.category == SimCategory.SIGN || s.category == SimCategory.WINDOW)
        if (!isWallElevated) {
            val groundPt = Geometry2D.groundPlanePoint(
                origin = EnuPoint(0.0, 0.0, 0.0),
                bearingDegrees = s.bearingDegrees,
                elevationDegrees = s.elevationDegrees,
                cameraHeightMeters = s.frame.cameraHeightMeters,
                minPitchBelowHorizonDegrees = 4.0,
                maxDistanceMeters = 18.0
            ) ?: return null
            val dist = groundPt.horizontalNorm()
            return LocalizedRoomSighting(
                sighting = s,
                position = groundPt,
                distanceMeters = dist,
                localisationMethod = "GROUND_PLANE_PITCH"
            )
        }

        // For wall-mounted SIGN / WINDOW, intersect bearing ray with reconstructed floor/wall polygon first
        val ray2d = Ray2D(origin = EnuPoint(0.0, 0.0, 0.0), bearingDegrees = s.bearingDegrees)
        val wallHit = if (floorPolygonEnu.size >= 3) {
            Geometry2D.intersectRayWithPolygon(ray2d, floorPolygonEnu, maxDistanceMeters = 18.0)
        } else null

        if (wallHit != null) {
            val dist = wallHit.distanceMeters
            val up = (s.frame.cameraHeightMeters + dist * tan(s.elevationDegrees * PI / 180.0)).coerceIn(0.2, 4.0)
            return LocalizedRoomSighting(
                sighting = s,
                position = wallHit.point.copy(up = up),
                distanceMeters = dist,
                localisationMethod = "WALL_POLYGON_RAY_INTERSECTION"
            )
        }

        // Fallback if floor polygon is unavailable
        val fallbackGround = Geometry2D.groundPlanePoint(
            origin = EnuPoint(0.0, 0.0, 0.0),
            bearingDegrees = s.bearingDegrees,
            elevationDegrees = if (s.elevationDegrees <= -4.0) s.elevationDegrees else -18.0,
            cameraHeightMeters = s.frame.cameraHeightMeters
        ) ?: return null
        return LocalizedRoomSighting(
            sighting = s,
            position = fallbackGround.copy(up = 1.6),
            distanceMeters = fallbackGround.horizontalNorm(),
            localisationMethod = "WALL_DEFAULT_DEPTH"
        )
    }

    private fun mergeRoomSightings(
        sightings: List<LocalizedRoomSighting>
    ): List<List<LocalizedRoomSighting>> {
        val sorted = sightings.sortedByDescending { it.sighting.confidence }
        val used = BooleanArray(sorted.size)
        val clusters = mutableListOf<List<LocalizedRoomSighting>>()

        for (i in sorted.indices) {
            if (used[i]) continue
            used[i] = true
            val seed = sorted[i]
            val cluster = mutableListOf(seed)

            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val cand = sorted[j]
                if (cand.sighting.category != seed.sighting.category) continue

                val dist2d = seed.position.distanceTo2D(cand.position)
                val angDiff = Angles.absAngleDifference(seed.sighting.bearingDegrees, cand.sighting.bearingDegrees)
                val relDistDiff = abs(seed.distanceMeters - cand.distanceMeters) /
                    maxOf(seed.distanceMeters, cand.distanceMeters, 0.5)

                if (dist2d <= mergeDistanceMeters || (angDiff <= mergeBearingDegrees && relDistDiff <= 0.25)) {
                    used[j] = true
                    cluster.add(cand)
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    /**
     * Builds a simplified 2D floor polygon around `(0, 0)` from sampled floor-wall boundary rays.
     */
    private fun buildSimplifiedFloorPolygon(
        samples: List<Pair<Double, EnuPoint>>
    ): List<EnuPoint> {
        if (samples.size < 3) return emptyList()

        // Bin samples into 36 angular sectors (10 deg each) and keep the median boundary point per sector
        val numSectors = 36
        val sectorWidth = 360.0 / numSectors
        val sectorVertices = mutableListOf<EnuPoint>()

        for (sec in 0 until numSectors) {
            val startAng = sec * sectorWidth
            val endAng = (sec + 1) * sectorWidth
            val inSector = samples.filter { (b, _) ->
                val norm = Angles.normalizeBearing(b)
                norm >= startAng && norm < endAng
            }
            if (inSector.isNotEmpty()) {
                val sortedByDist = inSector.sortedBy { (_, pt) -> pt.horizontalNorm() }
                val medianPt = sortedByDist[sortedByDist.size / 2].second
                sectorVertices.add(medianPt)
            }
        }

        if (sectorVertices.size < 3) return emptyList()
        return simplifyClosedPolygonRdp(sectorVertices, epsilonMeters = 0.12)
    }

    private fun simplifyClosedPolygonRdp(
        vertices: List<EnuPoint>,
        epsilonMeters: Double
    ): List<EnuPoint> {
        if (vertices.size <= 4) return vertices
        val closed = vertices + vertices.first()
        val simplified = rdpRecursive(closed, epsilonMeters)
        val result = if (simplified.size > 1 && simplified.first().distanceTo2D(simplified.last()) < 1e-6) {
            simplified.dropLast(1)
        } else {
            simplified
        }
        return if (result.size >= 3) result else vertices
    }

    private fun rdpRecursive(points: List<EnuPoint>, epsilon: Double): List<EnuPoint> {
        if (points.size < 3) return points
        val first = points.first()
        val last = points.last()
        var maxDist = -1.0
        var index = 0
        for (i in 1 until points.size - 1) {
            val d = pointToSegmentDistance2D(points[i], first, last)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        return if (maxDist > epsilon) {
            val left = rdpRecursive(points.subList(0, index + 1), epsilon)
            val right = rdpRecursive(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun pointToSegmentDistance2D(p: EnuPoint, a: EnuPoint, b: EnuPoint): Double {
        val dx = b.east - a.east
        val dy = b.north - a.north
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) return p.distanceTo2D(a)
        val u = (((p.east - a.east) * dx + (p.north - a.north) * dy) / lenSq).coerceIn(0.0, 1.0)
        val proj = EnuPoint(a.east + u * dx, a.north + u * dy, 0.0)
        return p.distanceTo2D(proj)
    }

    private fun selectSpreadKeyframes(keyframes: List<CaptureFrame>, maxCount: Int): List<CaptureFrame> {
        if (keyframes.size <= maxCount) return keyframes
        val result = mutableListOf<CaptureFrame>()
        val step = keyframes.size.toDouble() / maxCount.toDouble()
        for (i in 0 until maxCount) {
            val idx = (i * step).toInt().coerceIn(0, keyframes.size - 1)
            result.add(keyframes[idx])
        }
        return result.distinctBy { it.frameId }
    }

    companion object {
        val DEFAULT_INDOOR_CATEGORIES = setOf(
            SimCategory.DOOR,
            SimCategory.DESK,
            SimCategory.RECEPTION_DESK,
            SimCategory.COUNTER,
            SimCategory.CHAIR,
            SimCategory.TABLE,
            SimCategory.SOFA,
            SimCategory.SIGN,
            SimCategory.STAIRS,
            SimCategory.LIFT,
            SimCategory.WINDOW
        )

        fun categoryPrompt(category: SimCategory): String = when (category) {
            SimCategory.DOOR -> "door"
            SimCategory.RECEPTION_DESK -> "reception desk"
            SimCategory.DESK -> "desk"
            SimCategory.COUNTER -> "counter"
            SimCategory.CHAIR -> "chair"
            SimCategory.TABLE -> "table"
            SimCategory.SOFA -> "sofa"
            SimCategory.SIGN -> "sign"
            SimCategory.STAIRS, SimCategory.STEPS -> "stairs"
            SimCategory.LIFT -> "lift"
            SimCategory.WINDOW -> "window"
            SimCategory.FLOOR -> "floor"
            else -> category.name.lowercase().replace('_', ' ')
        }

        fun defaultCategoryLabel(category: SimCategory): String = when (category) {
            SimCategory.DOOR -> "Door"
            SimCategory.RECEPTION_DESK -> "Reception Desk"
            SimCategory.DESK -> "Desk"
            SimCategory.COUNTER -> "Counter"
            SimCategory.CHAIR -> "Chair"
            SimCategory.TABLE -> "Table"
            SimCategory.SOFA -> "Sofa"
            SimCategory.SIGN -> "Sign"
            SimCategory.STAIRS, SimCategory.STEPS -> "Stairs"
            SimCategory.LIFT -> "Lift"
            SimCategory.WINDOW -> "Window"
            else -> category.name.lowercase().replaceFirstChar { it.uppercase() }
        }

        private fun round2(v: Double): Double = round(v * 100.0) / 100.0
        private fun round3(v: Double): Double = round(v * 1000.0) / 1000.0
    }
}
