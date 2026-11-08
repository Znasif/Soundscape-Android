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
        val estimatedCostUsd: Double
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
        val perceptionOnlyObjects: List<org.scottishtecharmy.soundscape.sim.model.SimObject> = emptyList()
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
    val dryRun: Boolean = false,
    val enablePerception: Boolean = false,
    val minDetectorConfidence: Double = 0.55
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

        val footprintsEnu = walledBuildings.map { b ->
            b.polygon.map { localEnu.latLonToEnu(it) }
        }.filter { it.size >= 3 }

        val selectedStations = StationSelector.selectStations(
            images = images,
            localEnu = localEnu,
            maxStations = config.maxStations,
            minSpacingMeters = config.minStationSpacingMeters,
            preferCloseToFootprints = footprintsEnu
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

        val plannedViews = ViewPlanner.planViews(
            stations = selectedStations,
            buildingFootprints = initialFootprints,
            maxDistanceMeters = config.maxViewDistanceMeters
        )
        emit(BuildEvent.ViewsPlanned(plannedViews))

        val prompts = listOf(
            "door" to SimCategory.DOOR,
            "shop sign" to SimCategory.SIGN,
            "steps" to SimCategory.STEPS
        )

        if (config.dryRun) {
            val plannedSam = minOf(plannedViews.size * prompts.size, budget.maxSamCalls)
            val plannedVerifier = minOf(plannedSam * 2, budget.maxGeminiCalls)
            val estCost = plannedSam * 0.0025 + plannedVerifier * 0.00015
            emit(
                BuildEvent.DryRunPlan(
                    plannedViews = plannedViews.size,
                    plannedSamCalls = plannedSam,
                    plannedVerifierCalls = plannedVerifier,
                    estimatedCostUsd = kotlin.math.round(estCost * 10000.0) / 10000.0
                )
            )
        }

        val sightings = mutableListOf<PerceptionSighting>()
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
                    for ((promptText, cat) in prompts) {
                        if (!budget.canConsumeSam(1)) break
                        val instances = segmentationClient.segment(cropImg.jpegBytes, promptText, cat)
                            .filter { it.confidence >= config.minDetectorConfidence }
                            .sortedByDescending { it.confidence }
                            .take(3)

                        for (inst in instances) {
                            // Compute world bearing & elevation: bottom-center for DOOR/STEPS, center for SIGN
                            val refX = if (cat == SimCategory.SIGN) inst.centerX else inst.bottomCenterX
                            val refY = if (cat == SimCategory.SIGN) inst.centerY else inst.bottomY
                            val worldRay = view.camera.pixelToRay(refX, refY)

                            val subCropBytes = panoramaImageSource.cropSubImage(cropImg, inst.box)
                            var verdict = VerifierVerdict.YES
                            if (verifier != null && budget.canConsumeGemini(1)) {
                                verdict = verifier.verifyCrop(subCropBytes, cat, promptText)
                            }
                            if (verdict == VerifierVerdict.NO) continue

                            var ocrText: String? = null
                            var ocrConf: Double? = null
                            if (cat == SimCategory.SIGN && textReader != null && budget.canConsumeGemini(1)) {
                                val ocr = textReader.readText(subCropBytes)
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
                                    signConfidence = ocrConf
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
            perceptionSightings = sightings
        )

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
        emit(BuildEvent.Completed(env, aggregated.perceptionOnlyObjects))
    }
}
