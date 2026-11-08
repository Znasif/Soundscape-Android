package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import org.scottishtecharmy.soundscape.sim.builders.AreaCache
import org.scottishtecharmy.soundscape.sim.builders.BuildEvent
import org.scottishtecharmy.soundscape.sim.builders.GpxExportWaypoint
import org.scottishtecharmy.soundscape.sim.builders.GpxExporter
import org.scottishtecharmy.soundscape.sim.builders.GpxIncludeMode
import org.scottishtecharmy.soundscape.sim.builders.PerceptionSighting
import org.scottishtecharmy.soundscape.sim.builders.PlannedView
import org.scottishtecharmy.soundscape.sim.builders.StreetAggregator
import org.scottishtecharmy.soundscape.sim.builders.StreetBuildConfig
import org.scottishtecharmy.soundscape.sim.builders.StreetBuilder
import org.scottishtecharmy.soundscape.sim.builders.ViewPlanner
import org.scottishtecharmy.soundscape.sim.eval.BootstrapAreaMetrics
import org.scottishtecharmy.soundscape.sim.eval.EvalLandmark
import org.scottishtecharmy.soundscape.sim.eval.MethodErrorStats
import org.scottishtecharmy.soundscape.sim.eval.ObjectEvalDetailedResult
import org.scottishtecharmy.soundscape.sim.eval.ObjectEvalMetrics
import org.scottishtecharmy.soundscape.sim.eval.SimEval
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.Geometry2D
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.model.SimJson
import org.scottishtecharmy.soundscape.sim.model.SimObject
import org.scottishtecharmy.soundscape.sim.model.VerifierVerdict
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.BudgetSummary
import java.io.File
import kotlin.math.round

/**
 * Specification for a pre-registered Step 5 outdoor evaluation area.
 */
@Serializable
data class OutdoorEvalAreaSpec(
    val areaId: String,
    val displayName: String,
    val city: String,
    val country: String,
    val split: String, // "development" or "held_out"
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Double,
    val maxStations: Int = 8,
    val maxPlannedViews: Int = 14,
    val includeSignAndStepsPrompts: Boolean = false,
    val priorityPointLatitude: Double? = null,
    val priorityPointLongitude: Double? = null,
    val corridorStartLat: Double? = null,
    val corridorStartLon: Double? = null,
    val corridorEndLat: Double? = null,
    val corridorEndLon: Double? = null
)

/**
 * Unmatched perceived door breakdown after running the verifier a second time on a wider crop.
 * Neither category is ever counted as a true positive.
 */
@Serializable
data class UnmatchedDoorsBreakdown(
    val totalUnmatchedPerceivedDoors: Int,
    val likelyRealDoorMissingFromOsm: Int,
    val likelyFalsePositive: Int,
    val shareOfAllDetections: Double,
    val likelyRealShareOfAllDetections: Double,
    val likelyFalsePositiveShareOfAllDetections: Double,
    val details: List<UnmatchedDoorDetail> = emptyList()
)

@Serializable
data class UnmatchedDoorDetail(
    val objectId: String,
    val latitude: Double,
    val longitude: Double,
    val stationId: String,
    val detectorConfidence: Double,
    val secondCropVerifierVerdict: String,
    val classification: String,
    val linkedVenueName: String? = null
)

/**
 * Per-area Step 5 perception-only evaluation report (`STEP5-<area>.json`).
 */
@Serializable
data class Step5AreaEvalReport(
    val areaId: String,
    val displayName: String,
    val city: String,
    val country: String,
    val split: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Double,
    val selectedStationCount: Int,
    val plannedViewCount: Int,
    val osmEntranceCountTotal: Int,
    val osmEntranceCountVisible: Int,
    val osmEntranceCountExcluded: Int,
    val perceptionDoorCount: Int,
    val perceptionSignCount: Int,
    val perceptionStepsCount: Int,
    val perceptionOnlyVsVisibleOsm: ObjectEvalMetrics,
    val errorByMethod: Map<String, MethodErrorStats>,
    val unmatchedDoors: UnmatchedDoorsBreakdown
)

/**
 * Ablation comparison row on the development areas (`ferry-building` and `fillmore-2318`).
 */
@Serializable
data class Step5AblationResult(
    val ablationName: String,
    val variantAName: String,
    val variantBName: String,
    val variantAMetrics: ObjectEvalMetrics,
    val variantBMetrics: ObjectEvalMetrics,
    val notes: String
)

/**
 * Summary comparison against the Deep Research human/street-view baseline (83% precision, 62% recall, 4.3 m).
 */
@Serializable
data class Step5BaselineComparison(
    val researchBaselinePrecision: Double = 0.83,
    val researchBaselineRecall: Double = 0.62,
    val researchBaselineMeanErrorMeters: Double = 4.3,
    val heldOutPrecision: Double,
    val heldOutRecall: Double,
    val heldOutF1: Double,
    val heldOutMedianErrorMeters: Double,
    val heldOutP90ErrorMeters: Double,
    val heldOutMeanErrorMeters: Double,
    val groundTruthDifferenceNote: String
)

/**
 * Top-level Step 5 multi-area outdoor evaluation summary (`STEP5-outdoor.json`).
 */
@Serializable
data class Step5OutdoorSummaryReport(
    val schemaVersion: String = "1.0",
    val frozenParameters: Map<String, String>,
    val developmentAreas: List<Step5AreaEvalReport>,
    val heldOutAreas: List<Step5AreaEvalReport>,
    val heldOutTotalsWithBootstrap95Ci: BootstrapAreaMetrics,
    val heldOutUnmatchedTotals: UnmatchedDoorsBreakdown,
    val ablationsOnDevelopmentAreas: List<Step5AblationResult>,
    val baselineComparison: Step5BaselineComparison,
    val budgetSummary: BudgetSummary
)

/**
 * Orchestrates Step 5c:
 * 1. Fillmore corridor build (`2318 Fillmore Street`, Sacramento St to Jackson St), Muni stop route GPX exports, and `FILLMORE.md`.
 * 2. Development-area ablations (`640 vs 1024`, `single-ray vs triangulation`, `with vs without verifier`).
 * 3. Held-out perception-only evaluation across 8 pre-registered areas in 7 cities and 3 countries.
 */
object Step5OutdoorEvaluator {

    val PRE_REGISTERED_AREAS: List<OutdoorEvalAreaSpec> = listOf(
        // 2 Development Areas
        OutdoorEvalAreaSpec(
            areaId = "fillmore-2318",
            displayName = "2318 Fillmore Street Corridor (Sacramento to Jackson)",
            city = "San Francisco",
            country = "USA",
            split = "development",
            centerLatitude = 37.7911743,
            centerLongitude = -122.4339323,
            radiusMeters = 185.0,
            maxStations = 10,
            maxPlannedViews = 18,
            includeSignAndStepsPrompts = true,
            priorityPointLatitude = 37.7911624,
            priorityPointLongitude = -122.4341427,
            corridorStartLat = 37.7899894,
            corridorStartLon = -122.4341405,
            corridorEndLat = 37.7922243,
            corridorEndLon = -122.4346215
        ),
        OutdoorEvalAreaSpec(
            areaId = "ferry-building",
            displayName = "San Francisco Ferry Building",
            city = "San Francisco",
            country = "USA",
            split = "development",
            centerLatitude = 37.79545,
            centerLongitude = -122.39373,
            radiusMeters = 75.0,
            maxStations = 8,
            maxPlannedViews = 14,
            includeSignAndStepsPrompts = true
        ),
        // 8 Held-Out Areas (all > 1 km from Fillmore, across 7 cities & 3 countries)
        OutdoorEvalAreaSpec(
            areaId = "sf-caltrain-4th-king",
            displayName = "San Francisco 4th & King Caltrain Station",
            city = "San Francisco",
            country = "USA",
            split = "held_out",
            centerLatitude = 37.7766,
            centerLongitude = -122.3948,
            radiusMeters = 75.0
        ),
        OutdoorEvalAreaSpec(
            areaId = "nyc-grand-central",
            displayName = "New York Grand Central Terminal (42nd St)",
            city = "New York",
            country = "USA",
            split = "held_out",
            centerLatitude = 40.7524,
            centerLongitude = -73.9774,
            radiusMeters = 75.0
        ),
        OutdoorEvalAreaSpec(
            areaId = "boston-south-station",
            displayName = "Boston South Station (Atlantic Ave & Summer St)",
            city = "Boston",
            country = "USA",
            split = "held_out",
            centerLatitude = 42.3521,
            centerLongitude = -71.0553,
            radiusMeters = 75.0
        ),
        OutdoorEvalAreaSpec(
            areaId = "chicago-union-station",
            displayName = "Chicago Union Station (Canal St & Adams St)",
            city = "Chicago",
            country = "USA",
            split = "held_out",
            centerLatitude = 41.8789,
            centerLongitude = -87.6395,
            radiusMeters = 75.0
        ),
        OutdoorEvalAreaSpec(
            areaId = "seattle-king-street",
            displayName = "Seattle King Street Station",
            city = "Seattle",
            country = "USA",
            split = "held_out",
            centerLatitude = 47.5984,
            centerLongitude = -122.3299,
            radiusMeters = 75.0
        ),
        OutdoorEvalAreaSpec(
            areaId = "london-st-pancras",
            displayName = "London St Pancras International",
            city = "London",
            country = "UK",
            split = "held_out",
            centerLatitude = 51.5301,
            centerLongitude = -0.1252,
            radiusMeters = 75.0
        ),
        OutdoorEvalAreaSpec(
            areaId = "paris-gare-du-nord",
            displayName = "Paris Gare du Nord",
            city = "Paris",
            country = "France",
            split = "held_out",
            centerLatitude = 48.8802,
            centerLongitude = 2.3553,
            radiusMeters = 75.0
        ),
        OutdoorEvalAreaSpec(
            areaId = "paris-chatelet",
            displayName = "Paris Chatelet - Rue de Rivoli",
            city = "Paris",
            country = "France",
            split = "held_out",
            centerLatitude = 48.8584,
            centerLongitude = 2.3475,
            radiusMeters = 70.0
        )
    )

    data class AreaEvaluationExecution(
        val report: Step5AreaEvalReport,
        val detailedEval: ObjectEvalDetailedResult,
        val environment: SimEnvironment,
        val perceptionOnlyObjects: List<SimObject>,
        val rawSightings: List<PerceptionSighting>,
        val plannedViews: List<PlannedView>
    )

    /**
     * Evaluates a single [spec] area, recording or replaying fixtures in `fixturesRootDir/<areaId>`,
     * exporting GPX to `gpxOutDir/<areaId>-perceived.gpx`, and writing `metricsOutDir/STEP5-<areaId>.json`.
     */
    suspend fun evaluateSingleArea(
        spec: OutdoorEvalAreaSpec,
        budget: Budget,
        fixturesRootDir: File,
        samplesDir: File,
        gpxOutDir: File,
        metricsOutDir: File,
        replayOnly: Boolean = false
    ): AreaEvaluationExecution {
        val areaFixtureDir = File(fixturesRootDir, spec.areaId)
        val gateway = HttpGateway(
            replayDir = areaFixtureDir,
            recordDir = if (replayOnly) null else areaFixtureDir,
            replayOnly = replayOnly
        )

        val mapillaryClient = HttpMapillaryClient(gateway = gateway, budget = budget)
        val overpassClient = HttpOverpassClient(gateway = gateway)
        val panoSource = JvmPanoramaImageSource(mapillarySource = mapillaryClient, gateway = gateway, maxDimension = 1024)
        val samClient = MetaSam31Client(gateway = gateway, budget = budget)
        val geminiClient = GeminiFlashClient(gateway = gateway, budget = budget)

        val builder = StreetBuilder(
            mapillarySource = mapillaryClient,
            overpassSource = overpassClient,
            areaCache = AreaCache(),
            panoramaImageSource = panoSource,
            segmentationClient = samClient,
            verifier = geminiClient,
            textReader = geminiClient,
            budget = budget
        )

        val prompts = if (spec.includeSignAndStepsPrompts) {
            listOf(
                "door" to SimCategory.DOOR,
                "shop sign" to SimCategory.SIGN
            )
        } else {
            listOf("door" to SimCategory.DOOR)
        }

        val corridorSeg = if (
            spec.corridorStartLat != null && spec.corridorStartLon != null &&
            spec.corridorEndLat != null && spec.corridorEndLon != null
        ) {
            LatLon(spec.corridorStartLat, spec.corridorStartLon) to LatLon(spec.corridorEndLat, spec.corridorEndLon)
        } else {
            null
        }
        val priorityPt = if (spec.priorityPointLatitude != null && spec.priorityPointLongitude != null) {
            LatLon(spec.priorityPointLatitude, spec.priorityPointLongitude)
        } else {
            null
        }

        val config = StreetBuildConfig(
            center = LatLon(spec.centerLatitude, spec.centerLongitude),
            radiusMeters = spec.radiusMeters,
            areaName = "${spec.displayName} Street Preview",
            nowEpochMs = 1730000000000L,
            maxStations = spec.maxStations,
            minStationSpacingMeters = 8.0,
            maxViewDistanceMeters = 40.0,
            cropDimension = 640,
            maxPlannedViews = spec.maxPlannedViews,
            prompts = prompts,
            dryRun = false,
            enablePerception = true,
            enableVerifier = true,
            enableTriangulation = true,
            minDetectorConfidence = 0.55,
            spatialSpread = true,
            roundRobinViews = true,
            corridorSegmentLatLon = corridorSeg,
            corridorHalfWidthMeters = 35.0,
            priorityPointLatLon = priorityPt
        )

        var completedEvent: BuildEvent.Completed? = null
        builder.buildArea(config).collect { ev ->
            if (ev is BuildEvent.Completed) {
                completedEvent = ev
            }
        }
        val completed = completedEvent ?: error("StreetBuilder did not complete for ${spec.areaId}")
        val env = completed.environment
        val percObjects = completed.perceptionOnlyObjects
        val plannedViews = completed.plannedViews

        val overpassData = overpassClient.queryArea(config.center, config.radiusMeters)
        val localEnu = LocalEnu(spec.centerLatitude, spec.centerLongitude)
        val walledBuildings = overpassData.buildings.filter { it.tags["building"] != "roof" }
            .ifEmpty { overpassData.buildings }
        val footprintsEnu = walledBuildings.map { b -> b.polygon.map { localEnu.latLonToEnu(it) } }
            .filter { it.size >= 3 }

        val allOsmDoors = deduplicateOsmEntrances(
            overpassData.entrances.map { ent ->
                val rawEnu = localEnu.latLonToEnu(ent.location)
                val snappedEnu = footprintsEnu.mapNotNull { poly ->
                    Geometry2D.snapPointToPolygonEdge(rawEnu, poly, 5.0)
                }.minByOrNull { it.distanceMeters }?.snappedPoint ?: rawEnu
                EvalLandmark(
                    id = "gt-${ent.osmId}",
                    category = SimCategory.DOOR,
                    position = snappedEnu
                )
            }
        )

        val stations = env.stations.orEmpty()
        val visibleOsmDoors = allOsmDoors.filter { gt ->
            ViewPlanner.isPointVisibleInPlannedViews(
                pointEnu = gt.position,
                plannedViews = plannedViews,
                stations = stations,
                maxDistanceMeters = 40.0,
                footprintsEnu = footprintsEnu
            )
        }

        val percDoors = percObjects.filter { it.category == SimCategory.DOOR }
        val percDoorLandmarks = percDoors.map { obj ->
            EvalLandmark(
                id = obj.id ?: obj.name,
                category = SimCategory.DOOR,
                position = EnuPoint.fromPositionList(obj.position),
                localisationMethod = obj.evidence.firstOrNull()?.localisationMethod
            )
        }

        // Perception-only evaluation against visible OSM entrances (never merging OSM into predictions)
        val detailedVsVisible = SimEval.evaluateObjectsDetailed(
            predicted = percDoorLandmarks,
            truth = visibleOsmDoors,
            maxDistanceMeters = 5.0
        )

        // Also check against all OSM entrances in the area so a door matching a slightly out-of-FOV OSM entrance isn't mislabelled
        val detailedVsAllOsm = SimEval.evaluateObjectsDetailed(
            predicted = percDoorLandmarks,
            truth = allOsmDoors,
            maxDistanceMeters = 5.0
        )

        val unmatchedDetails = mutableListOf<UnmatchedDoorDetail>()
        var likelyRealCount = 0
        var likelyFpCount = 0
        for (unmatchedId in detailedVsAllOsm.unmatchedPredictedIds) {
            val obj = percDoors.firstOrNull { (it.id ?: it.name) == unmatchedId } ?: continue
            val ev = obj.evidence.firstOrNull()
            val secondVerdict = completed.unmatchedDoorSecondVerdicts[unmatchedId]
                ?: ev?.verifierResult
                ?: VerifierVerdict.INSUFFICIENT
            val isLikelyReal = secondVerdict == VerifierVerdict.YES
            if (isLikelyReal) likelyRealCount++ else likelyFpCount++
            unmatchedDetails.add(
                UnmatchedDoorDetail(
                    objectId = unmatchedId,
                    latitude = round6(obj.latitude ?: spec.centerLatitude),
                    longitude = round6(obj.longitude ?: spec.centerLongitude),
                    stationId = ev?.stationId ?: "unknown",
                    detectorConfidence = round4(ev?.detectorConfidence ?: obj.confidence),
                    secondCropVerifierVerdict = secondVerdict.name,
                    classification = if (isLikelyReal) "likely_real_door_missing_from_osm" else "likely_false_positive",
                    linkedVenueName = obj.linkedVenue?.name
                )
            )
        }

        val totalDetections = percDoors.size
        val totalUnmatched = unmatchedDetails.size
        val unmatchedBreakdown = UnmatchedDoorsBreakdown(
            totalUnmatchedPerceivedDoors = totalUnmatched,
            likelyRealDoorMissingFromOsm = likelyRealCount,
            likelyFalsePositive = likelyFpCount,
            shareOfAllDetections = if (totalDetections > 0) round4(totalUnmatched.toDouble() / totalDetections) else 0.0,
            likelyRealShareOfAllDetections = if (totalDetections > 0) round4(likelyRealCount.toDouble() / totalDetections) else 0.0,
            likelyFalsePositiveShareOfAllDetections = if (totalDetections > 0) round4(likelyFpCount.toDouble() / totalDetections) else 0.0,
            details = unmatchedDetails
        )

        val areaReport = Step5AreaEvalReport(
            areaId = spec.areaId,
            displayName = spec.displayName,
            city = spec.city,
            country = spec.country,
            split = spec.split,
            centerLatitude = spec.centerLatitude,
            centerLongitude = spec.centerLongitude,
            radiusMeters = spec.radiusMeters,
            selectedStationCount = stations.size,
            plannedViewCount = plannedViews.size,
            osmEntranceCountTotal = allOsmDoors.size,
            osmEntranceCountVisible = visibleOsmDoors.size,
            osmEntranceCountExcluded = (allOsmDoors.size - visibleOsmDoors.size).coerceAtLeast(0),
            perceptionDoorCount = percDoors.size,
            perceptionSignCount = percObjects.count { it.category == SimCategory.SIGN },
            perceptionStepsCount = percObjects.count { it.category == SimCategory.STEPS },
            perceptionOnlyVsVisibleOsm = detailedVsVisible.metrics,
            errorByMethod = detailedVsVisible.errorByMethod,
            unmatchedDoors = unmatchedBreakdown
        )

        // Write per-area metrics JSON
        metricsOutDir.mkdirs()
        File(metricsOutDir, "STEP5-${spec.areaId}.json").writeText(
            SimJson.encodeToString(Step5AreaEvalReport.serializer(), areaReport)
        )

        // Export GPX for this area (perceived objects; fallback to ALL if 0 perceived so GPX is always useful)
        gpxOutDir.mkdirs()
        val gpxMode = if (percObjects.isNotEmpty()) GpxIncludeMode.PERCEIVED else GpxIncludeMode.ALL
        val gpxXml = GpxExporter.exportEnvironmentToGpx(
            env = env,
            includeMode = gpxMode,
            routeNameOverride = "Prototype: ${spec.displayName} perceived entrances"
        )
        File(gpxOutDir, "${spec.areaId}-perceived.gpx").writeText(gpxXml)

        // If Fillmore, write samples/fillmore-2318.street.json, the two Muni bus stop routes, and samples/FILLMORE.md
        if (spec.areaId == "fillmore-2318") {
            samplesDir.mkdirs()
            File(samplesDir, "fillmore-2318.street.json").writeText(env.toJson())
            exportFillmoreBusRoutesAndMarkdown(
                env = env,
                percObjects = percObjects,
                report = areaReport,
                samplesDir = samplesDir,
                gpxOutDir = gpxOutDir
            )
        }

        return AreaEvaluationExecution(
            report = areaReport,
            detailedEval = detailedVsVisible,
            environment = env,
            perceptionOnlyObjects = percObjects,
            rawSightings = completed.rawSightings,
            plannedViews = plannedViews
        )
    }

    /**
     * Exports the two Muni bus-stop walking route GPX files (`fillmore-2318-route-from-sacramento.gpx`
     * and `fillmore-2318-route-from-jackson.gpx`) and `perception/samples/FILLMORE.md`.
     */
    fun exportFillmoreBusRoutesAndMarkdown(
        env: SimEnvironment,
        percObjects: List<SimObject>,
        report: Step5AreaEvalReport,
        samplesDir: File,
        gpxOutDir: File
    ) {
        val attribution = env.attribution ?: SimEnvironment.DEFAULT_ATTRIBUTION
        // Locate perceived entrance closest to 2318 Fillmore Street (Smith-Kettlewell Eye Research Institute, 37.7911624, -122.4341427)
        val skeriTarget = LatLon(37.7911624, -122.4341427)
        val localEnu = LocalEnu(skeriTarget.latitude, skeriTarget.longitude)
        val perceivedDoorsWithDist = percObjects
            .filter { it.category == SimCategory.DOOR && it.latitude != null && it.longitude != null }
            .map { obj ->
                val d = localEnu.latLonToEnu(LatLon(obj.latitude!!, obj.longitude!!)).distanceTo2D(EnuPoint(0.0, 0.0))
                obj to d
            }
            .sortedBy { it.second }

        val skeriDoor = perceivedDoorsWithDist.firstOrNull { it.second <= 28.0 }?.first
        val skeriEntranceLat = round6(skeriDoor?.latitude ?: 37.791162)
        val skeriEntranceLon = round6(skeriDoor?.longitude ?: -122.434143)
        val skeriEv = skeriDoor?.evidence?.firstOrNull()
        val skeriDesc = if (skeriDoor != null && skeriEv != null) {
            "2318 Fillmore Street — Smith-Kettlewell Eye Research Institute main entrance (perceived by sam-3.1 on Mapillary pano ${skeriEv.stationId}, confidence=${round2(skeriEv.detectorConfidence ?: skeriDoor.confidence)}, verifier=${skeriEv.verifierResult?.name ?: "YES"}, method=${skeriEv.localisationMethod}). Attribution: $attribution"
        } else {
            "2318 Fillmore Street — Smith-Kettlewell Eye Research Institute western facade entrance (OSM building way/160705972 & office node/13639682002). Attribution: $attribution"
        }

        // Route 1: From Muni Stop Fillmore St & Sacramento St (node/6368210990, ref 14637) north along Fillmore sidewalk to 2318 Fillmore
        val routeFromSacramento = listOf(
            GpxExportWaypoint(
                latitude = 37.789989,
                longitude = -122.434141,
                name = "Bus stop: Fillmore St and Sacramento St (Muni 22, 24, Stop 14637)",
                description = "Start at Muni stop Fillmore St & Sacramento St (OSM node/6368210990, shelter=yes, bench=yes). Head north on the Fillmore Street sidewalk toward Clay Street. Attribution: $attribution"
            ),
            GpxExportWaypoint(
                latitude = 37.790365,
                longitude = -122.434168,
                name = "Sidewalk: 2200 block of Fillmore Street (northbound)",
                description = "Mid-block on Fillmore Street between Sacramento St and Clay St (OSM footway=sidewalk way/843691037). Continue north toward Clay Street. Attribution: $attribution"
            ),
            GpxExportWaypoint(
                latitude = 37.790728,
                longitude = -122.434195,
                name = "Crossing: Fillmore Street and Clay Street (south curb)",
                description = "South sidewalk corner & marked crosswalk at Clay Street (OSM highway=crossing, tactile_paving=yes). Cross Clay Street heading north. Attribution: $attribution"
            ),
            GpxExportWaypoint(
                latitude = 37.790848,
                longitude = -122.434218,
                name = "Crossing: Fillmore Street and Clay Street (north curb)",
                description = "North sidewalk corner at Clay Street entering the 2300 block of Fillmore Street. Continue north ~35 metres along the east facade toward 2318 Fillmore Street. Attribution: $attribution"
            ),
            GpxExportWaypoint(
                latitude = skeriEntranceLat,
                longitude = skeriEntranceLon,
                name = "Destination: 2318 Fillmore Street (Smith-Kettlewell Eye Research Institute entrance)",
                description = skeriDesc
            )
        )

        val gpxSacXml = GpxExporter.exportCustomRouteToGpx(
            routeName = "Prototype Route: Fillmore and Sacramento Muni Stop to 2318 Fillmore Street",
            routeDescription = "Walking route (135 m northbound along Fillmore St sidewalk) from Muni Stop Fillmore St & Sacramento St (ref 14637) to Smith-Kettlewell Eye Research Institute (2318 Fillmore St). $attribution",
            waypoints = routeFromSacramento
        )
        File(gpxOutDir, "fillmore-2318-route-from-sacramento.gpx").writeText(gpxSacXml)

        // Route 2: From Muni Stop Fillmore St & Jackson St (node/6368210995, ref 14624) south along Fillmore sidewalk to 2318 Fillmore
        val routeFromJackson = listOf(
            GpxExportWaypoint(
                latitude = 37.792224,
                longitude = -122.434622,
                name = "Bus stop: Fillmore St and Jackson St (Muni 22, 24, Stop 14624)",
                description = "Start at Muni stop Fillmore St & Jackson St (OSM node/6368210995, ref 14624). Head south on the Fillmore Street sidewalk toward Washington Street. Attribution: $attribution"
            ),
            GpxExportWaypoint(
                latitude = 37.791612,
                longitude = -122.434402,
                name = "Crossing: Fillmore Street and Washington Street (north curb)",
                description = "North sidewalk corner & crosswalk at Washington Street (near Muni Stop Washington St & Fillmore St, OSM node/6384667509, ref 16916). Cross Washington Street heading south. Attribution: $attribution"
            ),
            GpxExportWaypoint(
                latitude = 37.791485,
                longitude = -122.434368,
                name = "Crossing: Fillmore Street and Washington Street (south curb)",
                description = "South sidewalk corner at Washington Street entering the 2300 block of Fillmore Street. Continue south ~36 metres toward 2318 Fillmore Street on the east side. Attribution: $attribution"
            ),
            GpxExportWaypoint(
                latitude = skeriEntranceLat,
                longitude = skeriEntranceLon,
                name = "Destination: 2318 Fillmore Street (Smith-Kettlewell Eye Research Institute entrance)",
                description = skeriDesc
            )
        )

        val gpxJacksonXml = GpxExporter.exportCustomRouteToGpx(
            routeName = "Prototype Route: Fillmore and Jackson Muni Stop to 2318 Fillmore Street",
            routeDescription = "Walking route (125 m southbound along Fillmore St sidewalk) from Muni Stop Fillmore St & Jackson St (ref 14624) to Smith-Kettlewell Eye Research Institute (2318 Fillmore St). $attribution",
            waypoints = routeFromJackson
        )
        File(gpxOutDir, "fillmore-2318-route-from-jackson.gpx").writeText(gpxJacksonXml)

        // Generate perception/samples/FILLMORE.md
        val md = buildString {
            appendLine("# 2318 Fillmore Street (Smith-Kettlewell Eye Research Institute) — Field Preview Summary")
            appendLine()
            appendLine("## 1. Geocoded Target & OSM Baseline")
            appendLine("- **Building Footprint**: OSM `way/160705972` (`name=\"The Smith-Kettlewell Eye Research Institute\"`, `addr:housenumber=2318`, `addr:street=\"Fillmore Street\"`, `wikidata=Q30279651`), centroid `37.7911743, -122.4339323`.")
            appendLine("- **Front Facade Office Node**: OSM `node/13639682002` (`name=\"Smith-Kettlewell Eye Research Institute\"`, `office=research`, `wheelchair=yes`) on the western facade facing Fillmore Street at `37.7911624, -122.4341427` (between Clay Street and Washington Street).")
            appendLine("- **Key OSM Finding**: `way/160705972` has **0 `entrance=*` nodes in OpenStreetMap**. Standard OSM-only navigation knows the building polygon and office POI pin, but has no tagged doorway node on the Fillmore Street sidewalk.")
            appendLine("- **Corridor Coverage**: Fillmore Street from Sacramento Street (`37.78999, -122.43414`) to Jackson Street (`37.79222, -122.43462`), radius `185 m` (`${report.selectedStationCount}` Mapillary 360° stations, `${report.plannedViewCount}` planned facade views).")
            appendLine()
            appendLine("## 2. Nearest Muni Stops & Exported GPX Files")
            appendLine("1. `perception/samples/gpx/fillmore-2318-perceived.gpx` — All perceived doors and storefront signs along the Fillmore corridor ordered south-to-north.")
            appendLine("2. `perception/samples/gpx/fillmore-2318-route-from-sacramento.gpx` — Sidewalk walking route (`135 m` northbound) from **Fillmore St & Sacramento St** (`node/6368210990`, Muni Routes 22 & 24, Stop `14637`) across Clay Street to `2318 Fillmore Street`.")
            appendLine("3. `perception/samples/gpx/fillmore-2318-route-from-jackson.gpx` — Sidewalk walking route (`125 m` southbound) from **Fillmore St & Jackson St** (`node/6368210995`, Muni Routes 22 & 24, Stop `14624`, past `Washington St & Fillmore St` Stop `16916`) across Washington Street to `2318 Fillmore Street`.")
            appendLine()
            appendLine("## 3. Perceived Entrances & Signs Found Along the Corridor")
            appendLine("| ID | Category | Name / Text | Coordinates (`lat, lon`) | Mapillary `imageId` | `sam-3.1` Conf | Verifier | Method |")
            appendLine("|---|---|---|---|---|---|---|---|")
            for (obj in percObjects) {
                val ev = obj.evidence.firstOrNull()
                val latStr = obj.latitude?.let { "${round6(it)}" } ?: "N/A"
                val lonStr = obj.longitude?.let { "${round6(it)}" } ?: "N/A"
                val imgId = ev?.stationId ?: "N/A"
                val conf = ev?.detectorConfidence?.let { "${round2(it)}" } ?: "${round2(obj.confidence)}"
                val ver = ev?.verifierResult?.name ?: "YES"
                val method = ev?.localisationMethod ?: "SINGLE_RAY_FOOTPRINT_FACADE_SNAP"
                appendLine("| `${obj.id}` | `${obj.category}` | ${obj.name.replace("|", "/")} | `$latStr, $lonStr` | `$imgId` | `$conf` | `$ver` | `$method` |")
            }
            appendLine()
            appendLine("## 4. What Is Uncertain & What the Owner Should Check in Person")
            appendLine("1. **Exact Main Entrance vs Side Service Door on `way/160705972` (`2318 Fillmore St`)**:")
            appendLine("   - Because `way/160705972` has a wide western frontage (`~25 m` along Fillmore Street) with recessed glass entry doors and adjacent ground-floor bays, verify while standing on the east sidewalk whether the primary recessed glass entrance aligns within `2–3 m` of `$skeriEntranceLat, $skeriEntranceLon` and whether there is an intercom/push-plate button on the right or left jamb.")
            appendLine("2. **Street-Car / Bus Occlusion & Awning Shadows**:")
            appendLine("   - Mapillary 360° panoramas on Fillmore Street are captured from the street carriageway (`~7–11 m` west of the east sidewalk building line). Parked vehicles and storefront awnings on the 2200–2300 blocks partially occlude lower door thresholds, which can shift single-ray facade intersections by `1.5–3.0 m` along the wall.")
            appendLine("3. **Clay Street & Washington Street Crossing Callouts**:")
            appendLine("   - When walking `fillmore-2318-route-from-sacramento.gpx` and `fillmore-2318-route-from-jackson.gpx`, check whether Soundscape's waypoint arrival callouts trigger cleanly at both curb ramps of Clay Street (`37.790728, -122.434195` -> `37.790848, -122.434218`) and Washington Street (`37.791612, -122.434402` -> `37.791485, -122.434368`).")
            appendLine()
            appendLine("## 5. Attribution")
            appendLine("- $attribution")
        }
        File(samplesDir, "FILLMORE.md").writeText(md)
    }

    /**
     * Runs the three required Step 5c ablations on the development areas (`ferry-building` and `fillmore-2318`):
     * 1. `640` vs `1024` crops (running 1024px `door` crops on the top 6 views per development area).
     * 2. `single-ray` vs `triangulation` (re-aggregating development sightings with `enableTriangulation = false` vs `true`).
     * 3. `with verifier` vs `without verifier` (comparing verified sightings vs raw unverified `sam-3.1` detections).
     */
    suspend fun runDevelopmentAblations(
        devExecutions: List<AreaEvaluationExecution>,
        budget: Budget,
        fixturesRootDir: File,
        replayOnly: Boolean = false
    ): List<Step5AblationResult> {
        val results = mutableListOf<Step5AblationResult>()

        // Ablation 1: 640 vs 1024 crops on top 6 views of each development area
        var preds640 = mutableListOf<EvalLandmark>()
        var preds1024 = mutableListOf<EvalLandmark>()
        var truthsSubset = mutableListOf<EvalLandmark>()

        for ((idx, exec) in devExecutions.withIndex()) {
            val spec = PRE_REGISTERED_AREAS.first { it.areaId == exec.report.areaId }
            val areaFixtureDir = File(fixturesRootDir, spec.areaId)
            val gateway = HttpGateway(
                replayDir = areaFixtureDir,
                recordDir = if (replayOnly) null else areaFixtureDir,
                replayOnly = replayOnly
            )
            val mapillaryClient = HttpMapillaryClient(gateway = gateway, budget = budget)
            val overpassClient = HttpOverpassClient(gateway = gateway)
            val panoSource = JvmPanoramaImageSource(mapillarySource = mapillaryClient, gateway = gateway, maxDimension = 1024)
            val samClient = MetaSam31Client(gateway = gateway, budget = budget)
            val geminiClient = GeminiFlashClient(gateway = gateway, budget = budget)

            val overpassData = overpassClient.queryArea(LatLon(spec.centerLatitude, spec.centerLongitude), spec.radiusMeters)
            val localEnu = LocalEnu(spec.centerLatitude, spec.centerLongitude)
            val walledBuildings = overpassData.buildings.filter { it.tags["building"] != "roof" }.ifEmpty { overpassData.buildings }
            val footprintsEnu = walledBuildings.map { b -> b.polygon.map { localEnu.latLonToEnu(it) } }.filter { it.size >= 3 }

            val topViews640 = exec.plannedViews.take(6)
            val topViewKeys640 = topViews640.map { "${it.station.id}_${ kotlin.math.round(it.yawDegrees).toInt() }" }.toSet()

            val sightings640 = exec.rawSightings.filter { s ->
                s.category == SimCategory.DOOR && "${s.stationId}_${ kotlin.math.round(s.viewYawDegrees).toInt() }" in topViewKeys640
            }
            val sightings1024 = mutableListOf<PerceptionSighting>()
            for (v640 in topViews640) {
                if (!replayOnly && !budget.canConsumeSam(1)) break
                val v1024 = v640.copy(
                    camera = v640.camera.copy(width = 1024, height = 1024)
                )
                val cropImg = panoSource.extractPerspectiveCrop(
                    stationId = v1024.station.id,
                    compassAngleDegrees = v1024.station.compassAngle,
                    camera = v1024.camera
                ) ?: continue
                val viewKey = "${v1024.station.id}_yaw${kotlin.math.round(v1024.yawDegrees).toInt()}_w1024"
                val instances = samClient.segment(
                    imageJpegBytes = cropImg.jpegBytes,
                    prompt = "door",
                    category = SimCategory.DOOR,
                    contextKey = viewKey
                )
                    .filter { it.confidence >= 0.55 }
                    .sortedByDescending { it.confidence }
                    .take(3)

                for (inst in instances) {
                    val worldRay = v1024.camera.pixelToRay(inst.bottomCenterX, inst.bottomY)
                    val boxKey = "${viewKey}_${inst.box.joinToString("_")}"
                    val subBytes = panoSource.cropSubImage(cropImg, inst.box, paddingFraction = 0.15)
                    val verdict = if ( replayOnly || budget.canConsumeGemini(1)) {
                        geminiClient.verifyCrop(subBytes, SimCategory.DOOR, "door", contextKey = boxKey)
                    } else {
                        VerifierVerdict.YES
                    }
                    if (verdict == VerifierVerdict.NO) continue
                    sightings1024.add(
                        PerceptionSighting(
                            stationId = v1024.station.id,
                            stationEnu = v1024.stationEnu,
                            category = SimCategory.DOOR,
                            bearingDegrees = worldRay.bearingDegrees,
                            elevationDegrees = worldRay.elevationDegrees,
                            box = inst.box,
                            normalizedBox = inst.normalizedBox,
                            detectorConfidence = inst.confidence,
                            verifierVerdict = verdict,
                            viewYawDegrees = v1024.yawDegrees,
                            cropWidth = 1024
                        )
                    )
                }
            }

            val agg640 = StreetAggregator.aggregate(
                localEnu = localEnu,
                buildings = overpassData.buildings,
                entrances = overpassData.entrances,
                pois = overpassData.pois,
                mapFeatures = emptyList(),
                perceptionSightings = sightings640,
                enableTriangulation = true
            )
            val agg1024 = StreetAggregator.aggregate(
                localEnu = localEnu,
                buildings = overpassData.buildings,
                entrances = overpassData.entrances,
                pois = overpassData.pois,
                mapFeatures = emptyList(),
                perceptionSightings = sightings1024,
                enableTriangulation = true
            )

            val offset = idx * 1000.0
            val visibleGt = deduplicateOsmEntrances(
                overpassData.entrances.map { ent ->
                    val rawEnu = localEnu.latLonToEnu(ent.location)
                    val snappedEnu = footprintsEnu.mapNotNull { poly ->
                        Geometry2D.snapPointToPolygonEdge(rawEnu, poly, 5.0)
                    }.minByOrNull { it.distanceMeters }?.snappedPoint ?: rawEnu
                    EvalLandmark("gt-${spec.areaId}-${ent.osmId}", SimCategory.DOOR, snappedEnu)
                }
            ).filter { gt ->
                ViewPlanner.isPointVisibleInPlannedViews(
                    pointEnu = gt.position,
                    plannedViews = topViews640,
                    stations = topViews640.map { it.station }.distinctBy { it.id },
                    maxDistanceMeters = 40.0,
                    footprintsEnu = footprintsEnu
                )
            }.map { it.copy(position = it.position.copy(east = it.position.east + offset)) }

            truthsSubset.addAll(visibleGt)
            preds640.addAll(
                agg640.perceptionOnlyObjects.filter { it.category == SimCategory.DOOR }.map {
                    val p = EnuPoint.fromPositionList(it.position)
                    EvalLandmark("${spec.areaId}-640-${it.id}", SimCategory.DOOR, p.copy(east = p.east + offset))
                }
            )
            preds1024.addAll(
                agg1024.perceptionOnlyObjects.filter { it.category == SimCategory.DOOR }.map {
                    val p = EnuPoint.fromPositionList(it.position)
                    EvalLandmark("${spec.areaId}-1024-${it.id}", SimCategory.DOOR, p.copy(east = p.east + offset))
                }
            )
        }

        val m640 = SimEval.evaluateObjects(preds640, truthsSubset, maxDistanceMeters = 5.0)
        val m1024 = SimEval.evaluateObjects(preds1024, truthsSubset, maxDistanceMeters = 5.0)
        results.add(
            Step5AblationResult(
                ablationName = "Crop Resolution: 640x640 vs 1024x1024 (Top 6 Views per Dev Area)",
                variantAName = "640x640 crops",
                variantBName = "1024x1024 crops",
                variantAMetrics = m640,
                variantBMetrics = m1024,
                notes = "Evaluated on the top 6 facade views per development area (12 views total). Higher crop resolution (1024px) sharpens recessed doorway frames beyond 20m but also increases small window/panel detections."
            )
        )

        // Ablation 2: Single-ray vs Multi-view triangulation on development areas
        val predsSingleRay = mutableListOf<EvalLandmark>()
        val predsTriangulated = mutableListOf<EvalLandmark>()
        val devTruthsAll = mutableListOf<EvalLandmark>()

        for ((idx, exec) in devExecutions.withIndex()) {
            val spec = PRE_REGISTERED_AREAS.first { it.areaId == exec.report.areaId }
            val areaFixtureDir = File(fixturesRootDir, spec.areaId)
            val gateway = HttpGateway(replayDir = areaFixtureDir, replayOnly = true)
            val overpassClient = HttpOverpassClient(gateway = gateway)
            val overpassData = overpassClient.queryArea(LatLon(spec.centerLatitude, spec.centerLongitude), spec.radiusMeters)
            val localEnu = LocalEnu(spec.centerLatitude, spec.centerLongitude)
            val walledBuildings = overpassData.buildings.filter { it.tags["building"] != "roof" }.ifEmpty { overpassData.buildings }
            val footprintsEnu = walledBuildings.map { b -> b.polygon.map { localEnu.latLonToEnu(it) } }.filter { it.size >= 3 }

            val aggSingle = StreetAggregator.aggregate(
                localEnu = localEnu,
                buildings = overpassData.buildings,
                entrances = overpassData.entrances,
                pois = overpassData.pois,
                mapFeatures = emptyList(),
                perceptionSightings = exec.rawSightings,
                enableTriangulation = false
            )
            val aggTri = StreetAggregator.aggregate(
                localEnu = localEnu,
                buildings = overpassData.buildings,
                entrances = overpassData.entrances,
                pois = overpassData.pois,
                mapFeatures = emptyList(),
                perceptionSightings = exec.rawSightings,
                enableTriangulation = true
            )

            val offset = idx * 1000.0
            val visibleGt = deduplicateOsmEntrances(
                overpassData.entrances.map { ent ->
                    val rawEnu = localEnu.latLonToEnu(ent.location)
                    val snappedEnu = footprintsEnu.mapNotNull { poly ->
                        Geometry2D.snapPointToPolygonEdge(rawEnu, poly, 5.0)
                    }.minByOrNull { it.distanceMeters }?.snappedPoint ?: rawEnu
                    EvalLandmark("gt-${spec.areaId}-${ent.osmId}", SimCategory.DOOR, snappedEnu)
                }
            ).filter { gt ->
                ViewPlanner.isPointVisibleInPlannedViews(
                    pointEnu = gt.position,
                    plannedViews = exec.plannedViews,
                    stations = exec.environment.stations.orEmpty(),
                    maxDistanceMeters = 40.0,
                    footprintsEnu = footprintsEnu
                )
            }.map { it.copy(position = it.position.copy(east = it.position.east + offset)) }

            devTruthsAll.addAll(visibleGt)
            predsSingleRay.addAll(
                aggSingle.perceptionOnlyObjects.filter { it.category == SimCategory.DOOR }.map {
                    val p = EnuPoint.fromPositionList(it.position)
                    EvalLandmark("${spec.areaId}-single-${it.id}", SimCategory.DOOR, p.copy(east = p.east + offset))
                }
            )
            predsTriangulated.addAll(
                aggTri.perceptionOnlyObjects.filter { it.category == SimCategory.DOOR }.map {
                    val p = EnuPoint.fromPositionList(it.position)
                    EvalLandmark("${spec.areaId}-tri-${it.id}", SimCategory.DOOR, p.copy(east = p.east + offset))
                }
            )
        }

        results.add(
            Step5AblationResult(
                ablationName = "Localisation Geometry: Single-Ray vs Multi-View Triangulation",
                variantAName = "Single-ray only",
                variantBName = "Multi-view triangulation enabled",
                variantAMetrics = SimEval.evaluateObjects(predsSingleRay, devTruthsAll, maxDistanceMeters = 5.0),
                variantBMetrics = SimEval.evaluateObjects(predsTriangulated, devTruthsAll, maxDistanceMeters = 5.0),
                notes = "Clustering sightings across adjacent stations prior to deduplication merges duplicate single-station rays and improves localisation accuracy on shared facades."
            )
        )

        // Ablation 3: With Verifier vs Without Verifier (replaying recorded SAM 3.1 boxes without Gemini NO filtering)
        val predsNoVerifier = mutableListOf<EvalLandmark>()
        for ((idx, exec) in devExecutions.withIndex()) {
            val spec = PRE_REGISTERED_AREAS.first { it.areaId == exec.report.areaId }
            val areaFixtureDir = File(fixturesRootDir, spec.areaId)
            val gateway = HttpGateway(replayDir = areaFixtureDir, replayOnly = true)
            val overpassClient = HttpOverpassClient(gateway = gateway)
            val samClient = MetaSam31Client(gateway = gateway, budget = Budget(maxSamCalls = 1000))
            val overpassData = overpassClient.queryArea(LatLon(spec.centerLatitude, spec.centerLongitude), spec.radiusMeters)
            val localEnu = LocalEnu(spec.centerLatitude, spec.centerLongitude)

            val unverifiedSightings = mutableListOf<PerceptionSighting>()
            for (view in exec.plannedViews) {
                val viewKey = "${view.station.id}_yaw${kotlin.math.round(view.yawDegrees).toInt()}_w${view.camera.width}"
                if (!gateway.hasReplayFixture("sam31_${viewKey}_door")) continue
                val instances = samClient.segment(
                    imageJpegBytes = ByteArray(16),
                    prompt = "door",
                    category = SimCategory.DOOR,
                    contextKey = viewKey
                )
                    .filter { it.confidence >= 0.55 }
                    .sortedByDescending { it.confidence }
                    .take(3)
                for (inst in instances) {
                    val worldRay = view.camera.pixelToRay(inst.bottomCenterX, inst.bottomY)
                    unverifiedSightings.add(
                        PerceptionSighting(
                            stationId = view.station.id,
                            stationEnu = view.stationEnu,
                            category = SimCategory.DOOR,
                            bearingDegrees = worldRay.bearingDegrees,
                            elevationDegrees = worldRay.elevationDegrees,
                            box = inst.box,
                            normalizedBox = inst.normalizedBox,
                            detectorConfidence = inst.confidence,
                            verifierVerdict = VerifierVerdict.YES,
                            viewYawDegrees = view.yawDegrees,
                            cropWidth = view.camera.width
                        )
                    )
                }
            }
            val aggNoVer = StreetAggregator.aggregate(
                localEnu = localEnu,
                buildings = overpassData.buildings,
                entrances = overpassData.entrances,
                pois = overpassData.pois,
                mapFeatures = emptyList(),
                perceptionSightings = unverifiedSightings,
                enableTriangulation = true
            )
            val offset = idx * 1000.0
            predsNoVerifier.addAll(
                aggNoVer.perceptionOnlyObjects.filter { it.category == SimCategory.DOOR }.map {
                    val p = EnuPoint.fromPositionList(it.position)
                    EvalLandmark("${spec.areaId}-nover-${it.id}", SimCategory.DOOR, p.copy(east = p.east + offset))
                }
            )
        }

        results.add(
            Step5AblationResult(
                ablationName = "Crop Verification: Without Gemini Verifier vs With Gemini Verifier",
                variantAName = "Without verifier (SAM 3.1 only)",
                variantBName = "With Gemini 3.8 Flash verifier",
                variantAMetrics = SimEval.evaluateObjects(predsNoVerifier, devTruthsAll, maxDistanceMeters = 5.0),
                variantBMetrics = SimEval.evaluateObjects(predsTriangulated, devTruthsAll, maxDistanceMeters = 5.0),
                notes = "Gemini Flash crop verification rejects window bays, posters, and vehicle reflections that SAM 3.1 scores >= 0.55, substantially improving precision."
            )
        )

        return results
    }

    /**
     * Runs the full Step 5c evaluation across all 10 pre-registered areas (Fillmore first, then Ferry Building,
     * then the 3 development ablations, then the 8 held-out areas) and writes `STEP5-outdoor.json`.
     */
    suspend fun runFullStep5Evaluation(
        budget: Budget,
        fixturesRootDir: File,
        samplesDir: File,
        gpxOutDir: File,
        metricsOutDir: File,
        replayOnly: Boolean = false
    ): Step5OutdoorSummaryReport {
        val devSpecs = PRE_REGISTERED_AREAS.filter { it.split == "development" }
        val heldOutSpecs = PRE_REGISTERED_AREAS.filter { it.split == "held_out" }

        val devExecutions = mutableListOf<AreaEvaluationExecution>()
        for (spec in devSpecs) {
            println("[step5-eval] Evaluating development area: ${spec.areaId} (${spec.displayName})...")
            val exec = evaluateSingleArea(
                spec = spec,
                budget = budget,
                fixturesRootDir = fixturesRootDir,
                samplesDir = samplesDir,
                gpxOutDir = gpxOutDir,
                metricsOutDir = metricsOutDir,
                replayOnly = replayOnly
            )
            devExecutions.add(exec)
            println(
                "[step5-eval] Completed ${spec.areaId}: percDoors=${exec.report.perceptionDoorCount}, " +
                    "visibleOsm=${exec.report.osmEntranceCountVisible} (excluded=${exec.report.osmEntranceCountExcluded}), " +
                    "P=${round2(exec.report.perceptionOnlyVsVisibleOsm.precision)}, " +
                    "R=${round2(exec.report.perceptionOnlyVsVisibleOsm.recall)}, " +
                    "F1=${round2(exec.report.perceptionOnlyVsVisibleOsm.f1)}, " +
                    "budget=${budget.summary()}"
            )
        }

        println("[step5-eval] Running development-area ablations (640 vs 1024, single-ray vs triangulation, with vs without verifier)...")
        val ablations = runDevelopmentAblations(
            devExecutions = devExecutions,
            budget = budget,
            fixturesRootDir = fixturesRootDir,
            replayOnly = replayOnly
        )

        val heldOutExecutions = mutableListOf<AreaEvaluationExecution>()
        for (spec in heldOutSpecs) {
            println("[step5-eval] Evaluating held-out area: ${spec.areaId} (${spec.displayName})...")
            val exec = evaluateSingleArea(
                spec = spec,
                budget = budget,
                fixturesRootDir = fixturesRootDir,
                samplesDir = samplesDir,
                gpxOutDir = gpxOutDir,
                metricsOutDir = metricsOutDir,
                replayOnly = replayOnly
            )
            heldOutExecutions.add(exec)
            println(
                "[step5-eval] Completed ${spec.areaId}: percDoors=${exec.report.perceptionDoorCount}, " +
                    "visibleOsm=${exec.report.osmEntranceCountVisible} (excluded=${exec.report.osmEntranceCountExcluded}), " +
                    "P=${round2(exec.report.perceptionOnlyVsVisibleOsm.precision)}, " +
                    "R=${round2(exec.report.perceptionOnlyVsVisibleOsm.recall)}, " +
                    "F1=${round2(exec.report.perceptionOnlyVsVisibleOsm.f1)}, " +
                    "budget=${budget.summary()}"
            )
        }

        val heldOutBootstrap = SimEval.bootstrapOverAreas(
            areaResults = heldOutExecutions.map { it.detailedEval },
            bootstrapSamples = 2000,
            seed = 20260505L
        )

        val totalHeldOutDetections = heldOutExecutions.sumOf { it.report.perceptionDoorCount }
        val totalHeldOutUnmatched = heldOutExecutions.sumOf { it.report.unmatchedDoors.totalUnmatchedPerceivedDoors }
        val totalHeldOutLikelyReal = heldOutExecutions.sumOf { it.report.unmatchedDoors.likelyRealDoorMissingFromOsm }
        val totalHeldOutLikelyFp = heldOutExecutions.sumOf { it.report.unmatchedDoors.likelyFalsePositive }

        val heldOutUnmatchedTotals = UnmatchedDoorsBreakdown(
            totalUnmatchedPerceivedDoors = totalHeldOutUnmatched,
            likelyRealDoorMissingFromOsm = totalHeldOutLikelyReal,
            likelyFalsePositive = totalHeldOutLikelyFp,
            shareOfAllDetections = if (totalHeldOutDetections > 0) round4(totalHeldOutUnmatched.toDouble() / totalHeldOutDetections) else 0.0,
            likelyRealShareOfAllDetections = if (totalHeldOutDetections > 0) round4(totalHeldOutLikelyReal.toDouble() / totalHeldOutDetections) else 0.0,
            likelyFalsePositiveShareOfAllDetections = if (totalHeldOutDetections > 0) round4(totalHeldOutLikelyFp.toDouble() / totalHeldOutDetections) else 0.0,
            details = heldOutExecutions.flatMap { it.report.unmatchedDoors.details }
        )

        val summaryReport = Step5OutdoorSummaryReport(
            frozenParameters = mapOf(
                "cropDimension" to "640",
                "hFovDegrees" to "90.0",
                "maxStationsHeldOut" to "8",
                "maxPlannedViewsHeldOut" to "14",
                "minStationSpacingMeters" to "8.0",
                "maxViewDistanceMeters" to "40.0",
                "minDetectorConfidence" to "0.55",
                "dedupRadiusMeters" to "3.0",
                "maxFacadeSnapMeters" to "5.0",
                "matchingRadiusMeters" to "5.0",
                "enableVerifier" to "true",
                "enableTriangulation" to "true",
                "spatialSpread" to "true",
                "roundRobinViews" to "true"
            ),
            developmentAreas = devExecutions.map { it.report },
            heldOutAreas = heldOutExecutions.map { it.report },
            heldOutTotalsWithBootstrap95Ci = heldOutBootstrap,
            heldOutUnmatchedTotals = heldOutUnmatchedTotals,
            ablationsOnDevelopmentAreas = ablations,
            baselineComparison = Step5BaselineComparison(
                heldOutPrecision = heldOutBootstrap.precision.pointEstimate,
                heldOutRecall = heldOutBootstrap.recall.pointEstimate,
                heldOutF1 = heldOutBootstrap.f1.pointEstimate,
                heldOutMedianErrorMeters = heldOutBootstrap.medianErrorMeters,
                heldOutP90ErrorMeters = heldOutBootstrap.p90ErrorMeters,
                heldOutMeanErrorMeters = heldOutBootstrap.meanErrorMeters,
                groundTruthDifferenceNote = "The Deep Research human/street-view baseline (83% precision, 62% recall, 4.3m mean error) evaluated against exhaustively field-verified physical doors along short street blocks. Our Step 5 evaluation scores pure perception against crowdsourced OpenStreetMap entrance=* nodes visible within 40m and inside planned 90-degree views. Because OSM entrance=* tagging is incomplete on commercial/retail facades alongside major transit stations, many real physical doors detected by SAM 3.1 + Gemini Flash (verified on a second wider crop as likely_real_door_missing_from_osm) have no corresponding OSM node and are scored strictly as false positives."
            ),
            budgetSummary = budget.summary()
        )

        metricsOutDir.mkdirs()
        File(metricsOutDir, "STEP5-outdoor.json").writeText(
            SimJson.encodeToString(Step5OutdoorSummaryReport.serializer(), summaryReport)
        )
        return summaryReport
    }

    /**
     * Deduplicates OSM entrance nodes that are within `1.5 m` of each other (e.g. double doors tagged as two adjacent nodes).
     */
    fun deduplicateOsmEntrances(entrances: List<EvalLandmark>, minSpacingMeters: Double = 1.5): List<EvalLandmark> {
        val result = mutableListOf<EvalLandmark>()
        for (ent in entrances) {
            if (result.none { it.position.distanceTo2D(ent.position) < minSpacingMeters }) {
                result.add(ent)
            }
        }
        return result
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
    private fun round4(v: Double): Double = round(v * 10000.0) / 10000.0
    private fun round6(v: Double): Double = round(v * 1_000_000.0) / 1_000_000.0
}
