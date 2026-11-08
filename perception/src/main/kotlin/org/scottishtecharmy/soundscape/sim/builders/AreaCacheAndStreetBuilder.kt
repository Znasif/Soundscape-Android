package org.scottishtecharmy.soundscape.sim.builders

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.scottishtecharmy.soundscape.sim.geo.Geohash
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import org.scottishtecharmy.soundscape.sim.model.EnvironmentKind
import org.scottishtecharmy.soundscape.sim.model.GeoAnchor
import org.scottishtecharmy.soundscape.sim.model.Provenance
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.model.Station
import org.scottishtecharmy.soundscape.sim.model.VerifierVerdict
import org.scottishtecharmy.soundscape.sim.model.Walkable
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.MapillarySource
import org.scottishtecharmy.soundscape.sim.sources.OverpassSource
import org.scottishtecharmy.soundscape.sim.sources.PanoramaImageSource
import org.scottishtecharmy.soundscape.sim.sources.SegmentationClient
import org.scottishtecharmy.soundscape.sim.sources.TextReader
import org.scottishtecharmy.soundscape.sim.sources.Verifier
import kotlin.math.roundToInt

/**
 * Geohash-7 keyed cache for [SimEnvironment] street manifests with deterministic invalidation rules.
 */
class AreaCache(
    val currentBuilderVersion: String = DEFAULT_BUILDER_VERSION,
    val maxAgeMs: Long = NINETY_DAYS_MS
) {
    private val store = mutableMapOf<String, SimEnvironment>()

    fun keyFor(latitude: Double, longitude: Double): String =
        Geohash.encode(latitude, longitude, precision = 7)

    fun put(environment: SimEnvironment) {
        val anchor = environment.geoAnchor ?: return
        val key = environment.provenance?.geohash7 ?: keyFor(anchor.latitude, anchor.longitude)
        store[key] = environment
    }

    fun get(geohash7: String): SimEnvironment? = store[geohash7]

    /**
     * Returns `true` if the cached manifest for [geohash7] is missing or stale under any of the 3 rules:
     * 1. `builderVersion` changed (`cached.provenance.builderVersion != currentBuilderVersion`).
     * 2. Mapillary has a newer `captured_at` timestamp in the cell (`newestMapillaryCaptureEpochMs > cachedNewest`).
     * 3. The cached manifest is older than `90 days` (`nowEpochMs - createdAtEpochMs > maxAgeMs`).
     */
    fun shouldRebuild(
        geohash7: String,
        nowEpochMs: Long,
        newestMapillaryCaptureEpochMs: Long? = null
    ): Boolean {
        val cached = store[geohash7] ?: return true
        val prov = cached.provenance ?: return true

        if (prov.builderVersion != currentBuilderVersion) return true
        if (nowEpochMs - prov.createdAtEpochMs > maxAgeMs) return true
        if (newestMapillaryCaptureEpochMs != null) {
            val cachedNewest = prov.newestCaptureEpochMs ?: 0L
            if (newestMapillaryCaptureEpochMs > cachedNewest) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_BUILDER_VERSION = "0.1.0"
        const val NINETY_DAYS_MS = 90L * 24L * 3600L * 1000L
    }
}

/**
 * Progressive build events emitted by [StreetBuilder.buildArea].
 */
sealed class BuildEvent {
    data class CacheHit(val geohash7: String, val environment: SimEnvironment) : BuildEvent()
    data class FetchingSources(val center: LatLon, val radiusMeters: Double) : BuildEvent()
    data class StationsSelected(val stations: List<Station>, val totalImagesFound: Int) : BuildEvent()
    data class ViewsPlanned(val views: List<PlannedView>) : BuildEvent()
    data class DryRunPlan(
        val plannedViews: Int,
        val plannedSamCalls: Int,
        val plannedVerifierCalls: Int,
        val estimatedCostUsd: Double,
        val remainingSamAllowance: Int = 0,
        val remainingGeminiAllowance: Int = 0,
        val remainingMapillaryAllowance: Int = 0
    ) : BuildEvent()
    data class Feature(val obj: org.scottishtecharmy.soundscape.sim.model.SimObject) : BuildEvent()
    data class Sign(val sign: org.scottishtecharmy.soundscape.sim.model.Sign) : BuildEvent()
    data class PerceptionProgress(
        val completedViews: Int,
        val totalViews: Int,
        val sightingsSoFar: Int
    ) : BuildEvent()
    data class Completed(
        val environment: SimEnvironment,
        val perceptionOnlyObjects: List<org.scottishtecharmy.soundscape.sim.model.SimObject> = emptyList(),
        val rawSightings: List<PerceptionSighting> = emptyList(),
        val plannedViews: List<PlannedView> = emptyList(),
        val unmatchedDoorSecondVerdicts: Map<String, VerifierVerdict> = emptyMap()
    ) : BuildEvent()
    data class Failed(val message: String, val cause: Throwable? = null) : BuildEvent()
}

/**
 * Configuration for [StreetBuilder].
 */
data class StreetBuildConfig(
    val center: LatLon,
    val radiusMeters: Double = 75.0,
    val areaName: String = "Street Preview (${center.latitude}, ${center.longitude})",
    val builderVersion: String = AreaCache.DEFAULT_BUILDER_VERSION,
    val nowEpochMs: Long = 1730000000000L,
    val maxStations: Int = 12,
    val minStationSpacingMeters: Double = 8.0,
    val maxViewDistanceMeters: Double = 40.0,
    val cropDimension: Int = 640,
    val maxPlannedViews: Int = 24,
    val prompts: List<Pair<String, SimCategory>> = listOf(
        "door" to SimCategory.DOOR,
        "shop sign" to SimCategory.SIGN,
        "steps" to SimCategory.STEPS
    ),
    val dryRun: Boolean = false,
    val enablePerception: Boolean = false,
    val enableVerifier: Boolean = true,
    val enableTriangulation: Boolean = true,
    val minDetectorConfidence: Double = 0.55,
    val spatialSpread: Boolean = true,
    val roundRobinViews: Boolean = true,
    val corridorSegmentLatLon: Pair<LatLon, LatLon>? = null,
    val corridorHalfWidthMeters: Double = 35.0,
    val priorityPointLatLon: LatLon? = null
)

/**
 * Orchestrates outdoor Street Preview manifest construction from OSM (Overpass), Mapillary,
 * and optional Panorama SAM 3.1 + Gemini Flash perception, emitting a [Flow] of [BuildEvent].
 */
class StreetBuilder(
    private val mapillarySource: MapillarySource,
    private val overpassSource: OverpassSource,
    private val areaCache: AreaCache = AreaCache(),
    private val panoramaImageSource: PanoramaImageSource? = null,
    private val segmentationClient: SegmentationClient? = null,
    private val verifier: Verifier? = null,
    private val textReader: TextReader? = null,
    private val budget: Budget = Budget()
) {
    fun buildArea(config: StreetBuildConfig): Flow<BuildEvent> = flow {
        val geohash7 = Geohash.encode(config.center.latitude, config.center.longitude, precision = 7)
        val localEnu = LocalEnu(config.center.latitude, config.center.longitude)
        val bbox = localEnu.boundingBox(config.radiusMeters)

        emit(BuildEvent.FetchingSources(config.center, config.radiusMeters))

        val images = mapillarySource.getImagesInBbox(bbox)
        val newestCapture = images.maxOfOrNull { it.capturedAtEpochMs }

        if (!areaCache.shouldRebuild(geohash7, config.nowEpochMs, newestCapture)) {
            val cached = areaCache.get(geohash7)!!
            emit(BuildEvent.CacheHit(geohash7, cached))
            emit(BuildEvent.Completed(cached))
            return@flow
        }

        val mapFeatures = mapillarySource.getMapFeaturesInBbox(bbox)
        val overpassData = overpassSource.queryArea(config.center, config.radiusMeters)

        val walledBuildings = overpassData.buildings.filter { it.tags["building"] != "roof" }
            .ifEmpty { overpassData.buildings }

        val allFootprintsWithBuilding = walledBuildings.map { b ->
            b to b.polygon.map { localEnu.latLonToEnu(it) }
        }.filter { it.second.size >= 3 }
        val footprintsEnu = allFootprintsWithBuilding.map { it.second }

        val entranceEnus = overpassData.entrances.map { localEnu.latLonToEnu(it.location) }
        val footprintsWithEntrances = if (entranceEnus.isNotEmpty() && config.priorityPointLatLon == null) {
            allFootprintsWithBuilding.filter { (_, poly) ->
                entranceEnus.any { entEnu ->
                    org.scottishtecharmy.soundscape.sim.geo.Geometry2D.snapPointToPolygonEdge(entEnu, poly, 6.0) != null
                }
            }
        } else {
            emptyList()
        }
        val stationTargetFootprints = if (footprintsWithEntrances.isNotEmpty()) {
            footprintsWithEntrances.map { it.second }
        } else {
            footprintsEnu
        }
        val preferredBuildingIds = footprintsWithEntrances.map { it.first.osmId }.toSet()

        val corridorEnu = config.corridorSegmentLatLon?.let {
            localEnu.latLonToEnu(it.first) to localEnu.latLonToEnu(it.second)
        }
        val priorityEnu = config.priorityPointLatLon?.let { localEnu.latLonToEnu(it) }

        val selectedStations = StationSelector.selectStations(
            images = images,
            localEnu = localEnu,
            maxStations = config.maxStations,
            minSpacingMeters = config.minStationSpacingMeters,
            preferCloseToFootprints = stationTargetFootprints,
            corridorSegmentEnu = corridorEnu,
            corridorHalfWidthMeters = config.corridorHalfWidthMeters,
            spatialSpread = config.spatialSpread,
            priorityPointEnu = priorityEnu
        )
        emit(BuildEvent.StationsSelected(selectedStations, images.size))

        val initialFootprints = walledBuildings.map { b ->
            val enuVerts = b.polygon.map { localEnu.latLonToEnu(it) }
            org.scottishtecharmy.soundscape.sim.model.BuildingFootprint(
                osmId = b.osmId,
                name = b.name,
                verticesEnu = enuVerts.map { listOf(it.east, it.north) },
                verticesLatLon = b.polygon.map { listOf(it.latitude, it.longitude) }
            )
        }

        val allPlannedViews = ViewPlanner.planViews(
            stations = selectedStations,
            buildingFootprints = initialFootprints,
            maxDistanceMeters = config.maxViewDistanceMeters,
            cropDimension = config.cropDimension,
            roundRobinAcrossStations = config.roundRobinViews,
            preferredBuildingIds = preferredBuildingIds
        )
        val prioritizedViews = if (priorityEnu != null) {
            allPlannedViews.sortedWith(
                compareBy<PlannedView> {
                    val d = it.stationEnu.distanceTo2D(priorityEnu)
                    val bearingToPriority = org.scottishtecharmy.soundscape.sim.geo.Angles.bearingBetweenEnu(it.stationEnu, priorityEnu)
                    val yawDiff = org.scottishtecharmy.soundscape.sim.geo.Angles.absAngleDifference(it.yawDegrees, bearingToPriority)
                    !(d <= 35.0 && yawDiff <= 60.0)
                }
            )
        } else if (config.roundRobinViews) {
            allPlannedViews
        } else {
            allPlannedViews.sortedBy { it.hitDistanceMeters }
        }
        val plannedViews = prioritizedViews.take(config.maxPlannedViews)
        emit(BuildEvent.ViewsPlanned(plannedViews))

        val prompts = config.prompts

        if (config.dryRun) {
            val plannedSam = minOf(plannedViews.size * prompts.size, budget.remainingSamAllowance)
            val plannedVerifier = minOf(plannedSam * 2, budget.remainingGeminiAllowance)
            val estCost = plannedSam * 0.0025 + plannedVerifier * 0.00015
            emit(
                BuildEvent.DryRunPlan(
                    plannedViews = plannedViews.size,
                    plannedSamCalls = plannedSam,
                    plannedVerifierCalls = plannedVerifier,
                    estimatedCostUsd = kotlin.math.round(estCost * 10000.0) / 10000.0,
                    remainingSamAllowance = budget.remainingSamAllowance,
                    remainingGeminiAllowance = budget.remainingGeminiAllowance,
                    remainingMapillaryAllowance = budget.remainingMapillaryAllowance
                )
            )
        }

        val sightings = mutableListOf<PerceptionSighting>()
        val cropImagesByKey = mutableMapOf<String, org.scottishtecharmy.soundscape.sim.sources.PerspectiveCropImage>()
        val runPerception = config.enablePerception &&
            !config.dryRun &&
            panoramaImageSource != null &&
            segmentationClient != null

        if (runPerception) {
            for ((viewIdx, view) in plannedViews.withIndex()) {
                if (!budget.canConsumeSam(1)) break
                val cropImg = panoramaImageSource.extractPerspectiveCrop(
                    stationId = view.station.id,
                    compassAngleDegrees = view.station.compassAngle,
                    camera = view.camera
                )
                if (cropImg != null) {
                    val viewKey = "${view.station.id}_yaw${view.yawDegrees.roundToInt()}_w${cropImg.camera.width}"
                    cropImagesByKey[viewKey] = cropImg
                    for ((promptText, cat) in prompts) {
                        if (!budget.canConsumeSam(1)) break
                        val instances = segmentationClient.segment(
                            imageJpegBytes = cropImg.jpegBytes,
                            prompt = promptText,
                            category = cat,
                            contextKey = viewKey
                        )
                            .filter { it.confidence >= config.minDetectorConfidence }
                            .sortedByDescending { it.confidence }
                            .take(3)

                        for (inst in instances) {
                            val refX = if (cat == SimCategory.SIGN) inst.centerX else inst.bottomCenterX
                            val refY = if (cat == SimCategory.SIGN) inst.centerY else inst.bottomY
                            val worldRay = view.camera.pixelToRay(refX, refY)

                            val boxKey = "${viewKey}_${inst.box.joinToString("_")}"
                            val subCropBytes = panoramaImageSource.cropSubImage(cropImg, inst.box, paddingFraction = 0.15)
                            var verdict = VerifierVerdict.YES
                            if (config.enableVerifier && verifier != null && budget.canConsumeGemini(1)) {
                                verdict = verifier.verifyCrop(
                                    cropJpegBytes = subCropBytes,
                                    category = cat,
                                    promptLabel = promptText,
                                    contextKey = boxKey
                                )
                            }
                            if (verdict == VerifierVerdict.NO) continue

                            var ocrText: String? = null
                            var ocrConf: Double? = null
                            if (cat == SimCategory.SIGN && textReader != null && budget.canConsumeGemini(1)) {
                                val ocr = textReader.readText(subCropBytes, contextKey = boxKey)
                                if (ocr != null && ocr.text.isNotBlank()) {
                                    ocrText = ocr.text
                                    ocrConf = ocr.confidence
                                }
                            }

                            sightings.add(
                                PerceptionSighting(
                                    stationId = view.station.id,
                                    stationEnu = view.stationEnu,
                                    category = cat,
                                    bearingDegrees = worldRay.bearingDegrees,
                                    elevationDegrees = worldRay.elevationDegrees,
                                    box = inst.box,
                                    normalizedBox = inst.normalizedBox,
                                    detectorConfidence = inst.confidence,
                                    verifierVerdict = verdict,
                                    signText = ocrText,
                                    signConfidence = ocrConf,
                                    viewYawDegrees = view.yawDegrees,
                                    cropWidth = cropImg.camera.width
                                )
                            )
                        }
                    }
                }
                emit(BuildEvent.PerceptionProgress(viewIdx + 1, plannedViews.size, sightings.size))
            }
        }

        val aggregated = StreetAggregator.aggregate(
            localEnu = localEnu,
            buildings = overpassData.buildings,
            entrances = overpassData.entrances,
            pois = overpassData.pois,
            mapFeatures = mapFeatures,
            perceptionSightings = sightings,
            enableTriangulation = config.enableTriangulation
        )

        val unmatchedDoorSecondVerdicts = LinkedHashMap<String, VerifierVerdict>()
        if (runPerception && config.enableVerifier && verifier != null) {
            val osmDoorLandmarks = overpassData.entrances.mapIndexed { idx, ent ->
                val rawEnu = localEnu.latLonToEnu(ent.location)
                val snappedEnu = footprintsEnu.mapNotNull { poly ->
                    org.scottishtecharmy.soundscape.sim.geo.Geometry2D.snapPointToPolygonEdge(rawEnu, poly, 5.0)
                }.minByOrNull { it.distanceMeters }?.snappedPoint ?: rawEnu
                org.scottishtecharmy.soundscape.sim.eval.EvalLandmark("osm-$idx", SimCategory.DOOR, snappedEnu)
            }
            val percDoors = aggregated.perceptionOnlyObjects.filter { it.category == SimCategory.DOOR }
            val percLandmarks = percDoors.map {
                org.scottishtecharmy.soundscape.sim.eval.EvalLandmark(
                    id = it.id ?: it.name,
                    category = SimCategory.DOOR,
                    position = org.scottishtecharmy.soundscape.sim.geo.EnuPoint.fromPositionList(it.position)
                )
            }
            val matchVsOsm = org.scottishtecharmy.soundscape.sim.eval.SimEval.evaluateObjectsDetailed(
                predicted = percLandmarks,
                truth = osmDoorLandmarks,
                maxDistanceMeters = 5.0
            )
            for (unmatchedId in matchVsOsm.unmatchedPredictedIds) {
                val doorObj = percDoors.firstOrNull { (it.id ?: it.name) == unmatchedId } ?: continue
                val firstEv = doorObj.evidence.firstOrNull() ?: continue
                val matchingSighting = sightings.firstOrNull { s ->
                    s.stationId == firstEv.stationId && s.box == firstEv.box
                }
                if (matchingSighting != null && budget.canConsumeGemini(1)) {
                    val vKey = "${matchingSighting.stationId}_yaw${matchingSighting.viewYawDegrees.roundToInt()}_w${matchingSighting.cropWidth}"
                    val cropImg = cropImagesByKey[vKey]
                    if (cropImg != null) {
                        val widerCropBytes = panoramaImageSource.cropSubImage(
                            crop = cropImg,
                            box = matchingSighting.box,
                            paddingFraction = 0.45
                        )
                        val secondKey = "${vKey}_${matchingSighting.box.joinToString("_")}_secondcrop"
                        val secondVerdict = verifier.verifyCrop(
                            cropJpegBytes = widerCropBytes,
                            category = SimCategory.DOOR,
                            promptLabel = "building entrance door on facade",
                            contextKey = secondKey
                        )
                        unmatchedDoorSecondVerdicts[unmatchedId] = secondVerdict
                    }
                }
            }
        }

        for (obj in aggregated.objects) {
            emit(BuildEvent.Feature(obj))
        }
        for (sign in aggregated.signs) {
            emit(BuildEvent.Sign(sign))
        }

        val sourcesUsed = mutableListOf("OSM", "MAPILLARY")
        if (sightings.isNotEmpty()) {
            sourcesUsed.add("SAM_3_1")
            if (verifier != null || textReader != null) {
                sourcesUsed.add("GEMINI_FLASH")
            }
        }

        val env = SimEnvironment(
            schemaVersion = "1.0",
            name = config.areaName,
            kind = EnvironmentKind.STREET,
            geoAnchor = GeoAnchor(
                latitude = config.center.latitude,
                longitude = config.center.longitude,
                yawDegrees = 0.0
            ),
            provenance = Provenance(
                createdAtEpochMs = config.nowEpochMs,
                builderVersion = config.builderVersion,
                sources = sourcesUsed,
                newestCaptureEpochMs = newestCapture,
                geohash7 = geohash7
            ),
            attribution = SimEnvironment.DEFAULT_ATTRIBUTION,
            objects = aggregated.objects,
            signs = aggregated.signs,
            stations = selectedStations,
            walkable = Walkable(
                buildingFootprints = aggregated.buildingFootprints
            )
        )

        areaCache.put(env)
        emit(
            BuildEvent.Completed(
                environment = env,
                perceptionOnlyObjects = aggregated.perceptionOnlyObjects,
                rawSightings = sightings,
                plannedViews = plannedViews,
                unmatchedDoorSecondVerdicts = unmatchedDoorSecondVerdicts
            )
        )
    }
}
