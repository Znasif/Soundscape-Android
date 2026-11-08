package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.scottishtecharmy.soundscape.sim.builders.BuildEvent
import org.scottishtecharmy.soundscape.sim.builders.StreetBuildConfig
import org.scottishtecharmy.soundscape.sim.builders.StreetBuilder
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
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.BudgetSummary
import java.io.File

@Serializable
data class Step3EntranceEvalReport(
    val location: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Double,
    val distanceThresholdMeters: Double,
    val osmEntranceCountRaw: Int,
    val osmEntranceCountVisibleWithin40m: Int,
    val perceptionDetectedDoorCount: Int,
    val mergedTotalDoorCount: Int,
    val perceptionVsVisibleOsmMetrics: ObjectEvalMetrics,
    val perceptionVsAllOsmMetrics: ObjectEvalMetrics,
    val mergedVsAllOsmMetrics: ObjectEvalMetrics,
    val newDoorsFoundLackingInOsm: Int,
    val osmEntrancesConfirmedByPerception: Int,
    val osmEntrancesNotSeenByPerception: Int,
    val osmEntrancesOutOfStationRange40m: Int,
    val baselineComparison: BaselineComparison,
    val budgetUsed: BudgetSummary
)

@Serializable
data class BaselineComparison(
    val humanBaselinePrecision: Double = 0.83,
    val humanBaselineRecall: Double = 0.62,
    val humanBaselineErrorMeters: Double = 4.3,
    val step3VisiblePrecision: Double,
    val step3VisibleRecall: Double,
    val step3MeanErrorMeters: Double,
    val step3MergedRecall: Double
)

/**
 * Command-line entry point for building outdoor Street Preview manifests, running SAM 3.1 + Gemini Flash
 * panorama perception, and scoring entrance detection against OSM ground truth.
 *
 * Usage:
 *   `build-area --lat <deg> --lon <deg> --radius <m> --out <file> [--record <dir>] [--replay <dir>] [--max-sam-calls <n>] [--max-verifier-calls <n>] [--dry-run] [--perceive|--no-perceive] [--metrics-out <file>]`
 */
fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty() || args[0] == "--help" || args[0] != "build-area") {
        println(
            "Commands:\n" +
                "  build-area --lat <deg> --lon <deg> --radius <m> --out <file> " +
                "[--record <dir>] [--replay <dir>] [--max-sam-calls <n>] [--max-verifier-calls <n>] " +
                "[--dry-run] [--perceive|--no-perceive] [--metrics-out <file>]"
        )
        return@runBlocking
    }

    var lat = 37.79545
    var lon = -122.39373
    var radius = 75.0
    var outPath = "samples/ferry-building.street.json"
    var recordDir: String? = null
    var replayDir: String? = null
    var maxSamCalls = 60
    var maxVerifierCalls = 120
    var dryRun = false
    var perceive = true
    var metricsOutPath: String? = null

    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--lat" -> { lat = args[++i].toDouble() }
            "--lon" -> { lon = args[++i].toDouble() }
            "--radius" -> { radius = args[++i].toDouble() }
            "--out" -> { outPath = args[++i] }
            "--record" -> { recordDir = args[++i] }
            "--replay" -> { replayDir = args[++i] }
            "--max-sam-calls" -> { maxSamCalls = args[++i].toInt() }
            "--max-verifier-calls" -> { maxVerifierCalls = args[++i].toInt() }
            "--dry-run" -> { dryRun = true }
            "--perceive" -> { perceive = true }
            "--no-perceive" -> { perceive = false }
            "--metrics-out" -> { metricsOutPath = args[++i] }
        }
        i++
    }

    val budget = Budget(
        maxMapillaryCalls = 2000,
        maxSamCalls = maxSamCalls,
        maxGeminiCalls = maxVerifierCalls
    )
    val gateway = HttpGateway(
        replayDir = replayDir?.let { File(it) },
        recordDir = recordDir?.let { File(it) },
        replayOnly = false
    )

    val mapillaryClient = HttpMapillaryClient(gateway = gateway, budget = budget)
    val overpassClient = HttpOverpassClient(gateway = gateway)
    val panoSource = JvmPanoramaImageSource(mapillarySource = mapillaryClient, gateway = gateway)
    val samClient = MetaSam31Client(gateway = gateway, budget = budget)
    val geminiClient = GeminiFlashClient(gateway = gateway, budget = budget)

    val builder = StreetBuilder(
        mapillarySource = mapillaryClient,
        overpassSource = overpassClient,
        panoramaImageSource = panoSource,
        segmentationClient = samClient,
        verifier = geminiClient,
        textReader = geminiClient,
        budget = budget
    )

    val config = StreetBuildConfig(
        center = LatLon(lat, lon),
        radiusMeters = radius,
        areaName = "Ferry Building Street Preview",
        nowEpochMs = System.currentTimeMillis(),
        dryRun = dryRun,
        enablePerception = perceive
    )

    var finalEnv: SimEnvironment? = null
    var perceptionOnlyObjects: List<SimObject> = emptyList()

    builder.buildArea(config).collect { event ->
        when (event) {
            is BuildEvent.FetchingSources ->
                println("[build-area] Fetching Mapillary & Overpass sources around (${event.center.latitude}, ${event.center.longitude}), r=${event.radiusMeters}m...")
            is BuildEvent.StationsSelected ->
                println("[build-area] Selected ${event.stations.size} stations out of ${event.totalImagesFound} images.")
            is BuildEvent.ViewsPlanned ->
                println("[build-area] Planned ${event.views.size} facade-facing perspective views.")
            is BuildEvent.DryRunPlan ->
                println(
                    "[build-area] DRY-RUN PLAN: views=${event.plannedViews}, " +
                        "plannedSamCalls=${event.plannedSamCalls} (cap=$maxSamCalls), " +
                        "plannedVerifierCalls=${event.plannedVerifierCalls} (cap=$maxVerifierCalls), " +
                        "estimatedCostUsd=$${event.estimatedCostUsd}"
                )
            is BuildEvent.PerceptionProgress ->
                println("[build-area] Perception progress: ${event.completedViews}/${event.totalViews} views (${event.sightingsSoFar} sightings).")
            is BuildEvent.Feature -> {}
            is BuildEvent.Sign -> {}
            is BuildEvent.CacheHit ->
                println("[build-area] Cache hit for geohash ${event.geohash7}.")
            is BuildEvent.Completed -> {
                finalEnv = event.environment
                perceptionOnlyObjects = event.perceptionOnlyObjects
                println("[build-area] Completed manifest with ${event.environment.objects?.size ?: 0} objects, ${event.environment.signs?.size ?: 0} signs, ${event.environment.stations?.size ?: 0} stations.")
            }
            is BuildEvent.Failed ->
                println("[build-area] FAILED: ${event.message}")
        }
    }

    if (dryRun) {
        return@runBlocking
    }

    val env = finalEnv ?: error("StreetBuilder did not emit Completed event")
    val outFile = File(outPath)
    outFile.parentFile?.mkdirs()
    outFile.writeText(env.toJson())
    println("[build-area] Wrote manifest to ${outFile.absolutePath}. Budget summary: ${budget.summary()}")

    if (metricsOutPath != null) {
        val overpassData = overpassClient.queryArea(LatLon(lat, lon), radius)
        val localEnu = LocalEnu(lat, lon)
        val walledBuildings = overpassData.buildings.filter { it.tags["building"] != "roof" }
            .ifEmpty { overpassData.buildings }
        val footprintPolys = walledBuildings.map { b -> b.polygon.map { localEnu.latLonToEnu(it) } }
            .filter { it.size >= 3 }

        val stationEnus = env.stations.orEmpty().map { EnuPoint.fromPositionList(it.position) }

        val allOsmDoors = overpassData.entrances.map { ent ->
            val rawEnu = localEnu.latLonToEnu(ent.location)
            val snappedEnu = footprintPolys.mapNotNull { poly ->
                Geometry2D.snapPointToPolygonEdge(rawEnu, poly, 5.0)
            }.minByOrNull { it.distanceMeters }?.snappedPoint ?: rawEnu
            org.scottishtecharmy.soundscape.sim.eval.EvalLandmark(
                id = "gt-${ent.osmId}",
                category = SimCategory.DOOR,
                position = snappedEnu
            )
        }

        val visibleOsmDoors = allOsmDoors.filter { gt ->
            stationEnus.any { st -> st.distanceTo2D(gt.position) <= 40.0 }
        }

        val percDoors = perceptionOnlyObjects.filter { it.category == SimCategory.DOOR }.map {
            org.scottishtecharmy.soundscape.sim.eval.EvalLandmark(
                id = it.id ?: it.name,
                category = SimCategory.DOOR,
                position = EnuPoint.fromPositionList(it.position)
            )
        }
        val mergedDoors = env.objects.orEmpty().filter { it.category == SimCategory.DOOR }.map {
            org.scottishtecharmy.soundscape.sim.eval.EvalLandmark(
                id = it.id ?: it.name,
                category = SimCategory.DOOR,
                position = EnuPoint.fromPositionList(it.position)
            )
        }

        val percVsVisible = SimEval.evaluateObjects(percDoors, visibleOsmDoors, maxDistanceMeters = 5.0)
        val percVsAll = SimEval.evaluateObjects(percDoors, allOsmDoors, maxDistanceMeters = 5.0)
        val mergedVsAll = SimEval.evaluateObjects(mergedDoors, allOsmDoors, maxDistanceMeters = 5.0)

        val report = Step3EntranceEvalReport(
            location = "Ferry Building, San Francisco",
            centerLatitude = lat,
            centerLongitude = lon,
            radiusMeters = radius,
            distanceThresholdMeters = 5.0,
            osmEntranceCountRaw = allOsmDoors.size,
            osmEntranceCountVisibleWithin40m = visibleOsmDoors.size,
            perceptionDetectedDoorCount = percDoors.size,
            mergedTotalDoorCount = mergedDoors.size,
            perceptionVsVisibleOsmMetrics = percVsVisible,
            perceptionVsAllOsmMetrics = percVsAll,
            mergedVsAllOsmMetrics = mergedVsAll,
            newDoorsFoundLackingInOsm = percVsAll.falsePositives,
            osmEntrancesConfirmedByPerception = percVsAll.truePositives,
            osmEntrancesNotSeenByPerception = percVsAll.falseNegatives,
            osmEntrancesOutOfStationRange40m = allOsmDoors.size - visibleOsmDoors.size,
            baselineComparison = BaselineComparison(
                step3VisiblePrecision = percVsVisible.precision,
                step3VisibleRecall = percVsVisible.recall,
                step3MeanErrorMeters = percVsVisible.meanErrorMeters,
                step3MergedRecall = mergedVsAll.recall
            ),
            budgetUsed = budget.summary()
        )

        val metricsFile = File(metricsOutPath)
        metricsFile.parentFile?.mkdirs()
        metricsFile.writeText(SimJson.encodeToString(Step3EntranceEvalReport.serializer(), report))
        println("[build-area] Wrote Step 3 evaluation metrics to ${metricsFile.absolutePath}")
    }
}
