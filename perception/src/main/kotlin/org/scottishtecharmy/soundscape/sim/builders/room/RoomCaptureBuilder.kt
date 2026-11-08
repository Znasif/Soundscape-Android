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
            categoryPrecheck.detectPresentCategories(precheckFrames.map { it.imageBytes }, contextKey = roomId)
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
            val frameOrigin = frame.cameraPositionEnu?.copy(up = 0.0) ?: EnuPoint(0.0, 0.0, 0.0)
            for (cat in categoriesToSegment) {
                if (!budget.canConsumeSam(1)) break
                val prompt = categoryPrompt(cat)
                val instances = segmentationClient.segment(
                    imageJpegBytes = frame.imageBytes,
                    prompt = prompt,
                    category = cat,
                    contextKey = frame.frameId
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
                        val maxFloorDist = if (frame.cameraPositionEnu != null) 8.5 else 18.0
                        val minFloorPitch = if (frame.cameraPositionEnu != null) 7.5 else 4.0
                        for (u in sampleXs) {
                            var ray = cam.pixelToRay(u, inst.topY)
                            // If topY of the floor bounding box reaches near/above the horizon, step down into the floor box
                            if (ray.elevationDegrees > -minFloorPitch && inst.bottomY > inst.topY) {
                                for (frac in listOf(0.15, 0.25, 0.35, 0.50)) {
                                    val vStep = inst.topY + frac * (inst.bottomY - inst.topY)
                                    val candRay = cam.pixelToRay(u, vStep)
                                    if (candRay.elevationDegrees <= -minFloorPitch) {
                                        ray = candRay
                                        break
                                    }
                                }
                            }
                            val pt = Geometry2D.groundPlanePoint(
                                origin = frameOrigin,
                                bearingDegrees = ray.bearingDegrees,
                                elevationDegrees = ray.elevationDegrees,
                                cameraHeightMeters = frame.cameraHeightMeters,
                                minPitchBelowHorizonDegrees = minFloorPitch,
                                maxDistanceMeters = maxFloorDist
                            )
                            if (pt != null) {
                                val sectorBearing = Angles.bearingBetweenEnu(EnuPoint(0.0, 0.0, 0.0), pt)
                                floorBoundaryPoints.add(sectorBearing to pt)
                            }
                            if (frame.cameraPositionEnu != null) {
                                val nearRay = cam.pixelToRay(u, inst.bottomY)
                                val nearPt = Geometry2D.groundPlanePoint(
                                    origin = frameOrigin,
                                    bearingDegrees = nearRay.bearingDegrees,
                                    elevationDegrees = nearRay.elevationDegrees,
                                    cameraHeightMeters = frame.cameraHeightMeters,
                                    minPitchBelowHorizonDegrees = 4.0,
                                    maxDistanceMeters = maxFloorDist
                                )
                                if (nearPt != null) {
                                    val b = Angles.bearingBetweenEnu(EnuPoint(0.0, 0.0, 0.0), nearPt)
                                    floorBoundaryPoints.add(b to nearPt)
                                }
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
                                originEnu = frameOrigin,
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
        val hasTranslatedPose = keyframes.any { it.cameraPositionEnu != null }
        val floorPolygonEnu = buildSimplifiedFloorPolygon(floorBoundaryPoints, centerAtSampleMean = hasTranslatedPose)

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
            val boxKey = "${best.frame.frameId}_${best.box.joinToString("_")}"

            // Verify low-confidence clusters (0.28..0.72) when verifier is provided
            val verdict = if (verifier != null && best.confidence < 0.72 && budget.canConsumeGemini(1)) {
                verifier.verifyCrop(cropBytes, best.category, best.promptLabel, contextKey = boxKey)
            } else {
                VerifierVerdict.YES
            }
            if (verdict == VerifierVerdict.NO) continue

            // If cluster contains sightings from translated camera positions (>= 0.35m baseline), attempt multi-view ray triangulation
            val distinctOriginSightings = cluster.distinctBy {
                "${round2(it.sighting.originEnu.east)}_${round2(it.sighting.originEnu.north)}"
            }
            val maxBaseline = if (distinctOriginSightings.size >= 2) {
                var maxB = 0.0
                for (i in distinctOriginSightings.indices) {
                    for (j in i + 1 until distinctOriginSightings.size) {
                        val d = distinctOriginSightings[i].sighting.originEnu.distanceTo2D(distinctOriginSightings[j].sighting.originEnu)
                        if (d > maxB) maxB = d
                    }
                }
                maxB
            } else 0.0

            val triangulatedPt = if (maxBaseline >= 0.35) {
                Geometry2D.triangulateRays(
                    rays = distinctOriginSightings.map {
                        Ray2D(origin = it.sighting.originEnu, bearingDegrees = it.sighting.bearingDegrees)
                    },
                    minParallaxDegrees = 6.0,
                    maxRmsResidualMeters = 1.25,
                    maxDistanceMeters = 18.0
                )?.point
            } else null

            // Average 3D position weighted by detector confidence (or triangulated XY when available)
            val totalWeight = cluster.sumOf { it.sighting.confidence }.coerceAtLeast(1e-6)
            val avgEast = triangulatedPt?.east ?: (cluster.sumOf { it.position.east * it.sighting.confidence } / totalWeight)
            val avgNorth = triangulatedPt?.north ?: (cluster.sumOf { it.position.north * it.sighting.confidence } / totalWeight)
            val avgUp = cluster.sumOf { it.position.up * it.sighting.confidence } / totalWeight
            val meanConf = (cluster.sumOf { it.sighting.confidence } / cluster.size).coerceIn(0.0, 1.0)
            val clusterMethod = if (triangulatedPt != null) "MULTI_VIEW_TRIANGULATION" else cluster.first().localisationMethod

            var ocrText: String? = null
            var ocrConf = meanConf
            if ((best.category == SimCategory.SIGN || best.category == SimCategory.DOOR) &&
                textReader != null &&
                budget.canConsumeGemini(1)
            ) {
                val ocr = textReader.readText(cropBytes, contextKey = boxKey)
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
                    localisationMethod = clusterMethod
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
                val vec = embedder.embedImage(ef.imageBytes, contextKey = ef.frameId)
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
            val pos = kf.cameraPositionEnu
            Station(
                id = kf.frameId,
                position = listOf(
                    round3(pos?.east ?: 0.0),
                    round3(kf.cameraHeightMeters),
                    round3(pos?.north ?: 0.0)
                ),
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
                builderVersion = "0.6.0-step6",
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
        val originEnu: EnuPoint,
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
        val origin = s.originEnu
        val isWallElevated = (s.category == SimCategory.SIGN || s.category == SimCategory.WINDOW)
        if (!isWallElevated) {
            val minPitch = if (s.frame.cameraPositionEnu != null) 2.5 else 4.0
            val groundPt = Geometry2D.groundPlanePoint(
                origin = origin,
                bearingDegrees = s.bearingDegrees,
                elevationDegrees = s.elevationDegrees,
                cameraHeightMeters = s.frame.cameraHeightMeters,
                minPitchBelowHorizonDegrees = minPitch,
                maxDistanceMeters = 18.0
            )
            if (groundPt != null) {
                val dist = origin.distanceTo2D(groundPt)
                return LocalizedRoomSighting(
                    sighting = s,
                    position = groundPt,
                    distanceMeters = dist,
                    localisationMethod = if (s.frame.cameraPositionEnu != null) "POSE_GROUND_PLANE_PITCH" else "GROUND_PLANE_PITCH"
                )
            }
            // If bottom edge is near the horizon (e.g. elevated counter or occluded doorway bottom), intersect with floor polygon
            if (floorPolygonEnu.size >= 3) {
                val ray2d = Ray2D(origin = origin, bearingDegrees = s.bearingDegrees)
                val wallHit = Geometry2D.intersectRayWithPolygon(ray2d, floorPolygonEnu, maxDistanceMeters = 18.0)
                if (wallHit != null) {
                    return LocalizedRoomSighting(
                        sighting = s,
                        position = wallHit.point.copy(up = 0.0),
                        distanceMeters = wallHit.distanceMeters,
                        localisationMethod = "WALL_POLYGON_RAY_INTERSECTION"
                    )
                }
            }
            return null
        }

        // For wall-mounted SIGN / WINDOW, intersect bearing ray with reconstructed floor/wall polygon first
        val ray2d = Ray2D(origin = origin, bearingDegrees = s.bearingDegrees)
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
            origin = origin,
            bearingDegrees = s.bearingDegrees,
            elevationDegrees = if (s.elevationDegrees <= -4.0) s.elevationDegrees else -18.0,
            cameraHeightMeters = s.frame.cameraHeightMeters
        ) ?: return null
        return LocalizedRoomSighting(
            sighting = s,
            position = fallbackGround.copy(up = 1.6),
            distanceMeters = origin.distanceTo2D(fallbackGround),
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
                val sameOrigin = seed.sighting.originEnu.distanceTo2D(cand.sighting.originEnu) < 0.25
                val angDiff = Angles.absAngleDifference(seed.sighting.bearingDegrees, cand.sighting.bearingDegrees)
                val relDistDiff = abs(seed.distanceMeters - cand.distanceMeters) /
                    maxOf(seed.distanceMeters, cand.distanceMeters, 0.5)

                if (dist2d <= mergeDistanceMeters || (sameOrigin && angDiff <= mergeBearingDegrees && relDistDiff <= 0.25)) {
                    used[j] = true
                    cluster.add(cand)
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    /**
     * Builds a simplified 2D floor polygon around `(0, 0)` (or around the sample mean when [centerAtSampleMean] is true)
     * from sampled floor-wall boundary rays.
     */
    private fun buildSimplifiedFloorPolygon(
        samples: List<Pair<Double, EnuPoint>>,
        centerAtSampleMean: Boolean = false
    ): List<EnuPoint> {
        if (samples.size < 3) return emptyList()

        val center = if (centerAtSampleMean) {
            val meanE = samples.sumOf { it.second.east } / samples.size
            val meanN = samples.sumOf { it.second.north } / samples.size
            EnuPoint(meanE, meanN, 0.0)
        } else {
            EnuPoint(0.0, 0.0, 0.0)
        }

        // Bin samples into 36 angular sectors (10 deg each) and keep the boundary point per sector
        val numSectors = 36
        val sectorWidth = 360.0 / numSectors
        val sectorVertices = mutableListOf<EnuPoint>()

        for (sec in 0 until numSectors) {
            val startAng = sec * sectorWidth
            val endAng = (sec + 1) * sectorWidth
            val inSector = samples.filter { (_, pt) ->
                val b = if (centerAtSampleMean) Angles.bearingBetweenEnu(center, pt) else Angles.bearingBetweenEnu(EnuPoint(0.0, 0.0, 0.0), pt)
                val norm = Angles.normalizeBearing(b)
                norm >= startAng && norm < endAng
            }
            if (inSector.isNotEmpty()) {
                val sortedByDist = inSector.sortedBy { (_, pt) -> center.distanceTo2D(pt) }
                val idx = if (centerAtSampleMean) {
                    ((sortedByDist.size - 1) * 0.75).toInt().coerceIn(0, sortedByDist.size - 1)
                } else {
                    sortedByDist.size / 2
                }
                sectorVertices.add(sortedByDist[idx].second)
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
