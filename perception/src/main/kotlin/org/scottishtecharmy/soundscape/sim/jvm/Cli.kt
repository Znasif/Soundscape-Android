package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.scottishtecharmy.soundscape.sim.builders.BuildEvent
import org.scottishtecharmy.soundscape.sim.builders.GpxExporter
import org.scottishtecharmy.soundscape.sim.builders.GpxIncludeMode
import org.scottishtecharmy.soundscape.sim.builders.StreetBuildConfig
import org.scottishtecharmy.soundscape.sim.builders.StreetBuilder
import org.scottishtecharmy.soundscape.sim.builders.ViewPlanner
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
 * panorama perception, scoring entrance detection against OSM ground truth, and exporting GPX routes.
 *
 * Commands:
 *   `build-area --lat <deg> --lon <deg> --radius <m> --out <file> [--name <areaName>] [--record <dir>] [--replay <dir>] [--ledger <file>] [--step <step>] [--max-sam-calls <n>] [--max-verifier-calls <n>] [--crop-size <px>] [--dry-run] [--perceive|--no-perceive] [--metrics-out <file>]`
 *   `export-gpx --in <environment.json> --out <file.gpx> [--include osm|perceived|all] [--route-name <name>]`
 */
fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty() || args[0] == "--help") {
        printUsage()
        return@runBlocking
    }

    when (args[0]) {
        "build-area" -> runBuildAreaCli(args)
        "export-gpx" -> runExportGpxCli(args)
        "build-fillmore" -> runBuildFillmoreCli(args)
        "run-step5-eval" -> runStep5EvalCli(args)
        else -> {
            printUsage()
        }
    }
}

private fun printUsage() {
    println(
        "Commands:\n" +
            "  build-area --lat <deg> --lon <deg> --radius <m> --out <file> " +
            "[--name <areaName>] [--record <dir>] [--replay <dir>] [--ledger <file>] [--step <step>] " +
            "[--max-sam-calls <n>] [--max-verifier-calls <n>] [--crop-size <px>] " +
            "[--dry-run] [--perceive|--no-perceive] [--metrics-out <file>]\n" +
            "  export-gpx --in <environment.json> --out <file.gpx> [--include osm|perceived|all] [--route-name <name>]\n" +
            "  build-fillmore [--replay-only]\n" +
            "  run-step5-eval [--replay-only]"
    )
}

suspend fun runBuildFillmoreCli(args: Array<String>) {
    val replayOnly = "--replay-only" in args
    val ledgerStore = JvmFileBudgetLedgerStore(File("docs/agent/metrics/budget-ledger.json"))
    val budget = Budget(
        maxMapillaryCalls = 5000,
        maxSamCalls = 300,
        maxGeminiCalls = 600,
        step = "step-5",
        command = args.joinToString(" "),
        ledgerStore = ledgerStore,
        clockEpochMs = { System.currentTimeMillis() }
    )
    val fillmoreSpec = Step5OutdoorEvaluator.PRE_REGISTERED_AREAS.first { it.areaId == "fillmore-2318" }
    val exec = Step5OutdoorEvaluator.evaluateSingleArea(
        spec = fillmoreSpec,
        budget = budget,
        fixturesRootDir = File("src/test/resources/fixtures"),
        samplesDir = File("samples"),
        gpxOutDir = File("samples/gpx"),
        metricsOutDir = File("docs/agent/metrics"),
        replayOnly = replayOnly
    )
    println("[build-fillmore] Completed Fillmore corridor: percDoors=${exec.report.perceptionDoorCount}, signs=${exec.report.perceptionSignCount}, budget=${budget.summary()}")
}

suspend fun runStep5EvalCli(args: Array<String>) {
    val replayOnly = "--replay-only" in args
    val ledgerStore = JvmFileBudgetLedgerStore(File("docs/agent/metrics/budget-ledger.json"))
    val budget = Budget(
        maxMapillaryCalls = 5000,
        maxSamCalls = 300,
        maxGeminiCalls = 600,
        step = "step-5",
        command = args.joinToString(" "),
        ledgerStore = ledgerStore,
        clockEpochMs = { System.currentTimeMillis() }
    )
    val report = Step5OutdoorEvaluator.runFullStep5Evaluation(
        budget = budget,
        fixturesRootDir = File("src/test/resources/fixtures"),
        samplesDir = File("samples"),
        gpxOutDir = File("samples/gpx"),
        metricsOutDir = File("docs/agent/metrics"),
        replayOnly = replayOnly
    )
    println(
        "[run-step5-eval] Completed all 10 areas! Held-out Precision=${report.heldOutTotalsWithBootstrap95Ci.precision.pointEstimate}, " +
            "Recall=${report.heldOutTotalsWithBootstrap95Ci.recall.pointEstimate}, " +
            "F1=${report.heldOutTotalsWithBootstrap95Ci.f1.pointEstimate}, " +
            "MedianError=${report.heldOutTotalsWithBootstrap95Ci.medianErrorMeters}m, " +
            "Budget=${report.budgetSummary}"
    )
}

fun runExportGpxCli(args: Array<String>) {
    var inPath: String? = null
    var outPath: String? = null
    var includeMode = GpxIncludeMode.PERCEIVED
    var routeNameOverride: String? = null

    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--in" -> { inPath = args[++i] }
            "--out" -> { outPath = args[++i] }
            "--include" -> { includeMode = GpxIncludeMode.fromArg(args[++i]) }
            "--route-name" -> { routeNameOverride = args[++i] }
        }
        i++
    }

    val inputFile = File(requireNotNull(inPath) { "Missing --in <environment.json>" })
    val outputFile = File(requireNotNull(outPath) { "Missing --out <file.gpx>" })
    val env = SimEnvironment.fromJson(inputFile.readText())
    val gpxXml = GpxExporter.exportEnvironmentToGpx(
        env = env,
        includeMode = includeMode,
        routeNameOverride = routeNameOverride
    )
    outputFile.parentFile?.mkdirs()
    outputFile.writeText(gpxXml)
    println("[export-gpx] Exported GPX (--include ${includeMode.name.lowercase()}) to ${outputFile.absolutePath}")
}

suspend fun runBuildAreaCli(args: Array<String>) {
    var lat = 37.79545
    var lon = -122.39373
    var radius = 75.0
    var areaName = "Ferry Building Street Preview"
    var outPath = "samples/ferry-building.street.json"
    var recordDir: String? = null
    var replayDir: String? = null
    var ledgerPath = "docs/agent/metrics/budget-ledger.json"
    var stepName = "step-5"
    var maxSamCalls = 300
    var maxVerifierCalls = 600
    var maxMapillaryCalls = 5000
    var cropSize = 640
    var maxViews = 24
    var dryRun = false
    var perceive = true
    var metricsOutPath: String? = null

    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--lat" -> { lat = args[++i].toDouble() }
            "--lon" -> { lon = args[++i].toDouble() }
            "--radius" -> { radius = args[++i].toDouble() }
            "--name" -> { areaName = args[++i] }
            "--out" -> { outPath = args[++i] }
            "--record" -> { recordDir = args[++i] }
            "--replay" -> { replayDir = args[++i] }
            "--ledger" -> { ledgerPath = args[++i] }
            "--step" -> { stepName = args[++i] }
            "--max-sam-calls" -> { maxSamCalls = args[++i].toInt() }
            "--max-verifier-calls" -> { maxVerifierCalls = args[++i].toInt() }
            "--max-mapillary-calls" -> { maxMapillaryCalls = args[++i].toInt() }
            "--crop-size" -> { cropSize = args[++i].toInt() }
            "--max-views" -> { maxViews = args[++i].toInt() }
            "--dry-run" -> { dryRun = true }
            "--perceive" -> { perceive = true }
            "--no-perceive" -> { perceive = false }
            "--metrics-out" -> { metricsOutPath = args[++i] }
        }
        i++
    }

    val ledgerFile = File(ledgerPath)
    val ledgerStore = JvmFileBudgetLedgerStore(ledgerFile)
    val budget = Budget(
        maxMapillaryCalls = maxMapillaryCalls,
        maxSamCalls = maxSamCalls,
        maxGeminiCalls = maxVerifierCalls,
        step = stepName,
        command = args.joinToString(" "),
        ledgerStore = ledgerStore,
        clockEpochMs = { System.currentTimeMillis() }
    )
    val gateway = HttpGateway(
        replayDir = replayDir?.let { File(it) },
        recordDir = recordDir?.let { File(it) },
        replayOnly = false
    )

    // Pre-run budget check: refuse to start if planned live calls would push the step total past the cap
    val isReplayingAll = replayDir != null && File(replayDir, "mapillary_images_bbox.json").exists()
    if (!dryRun && !isReplayingAll) {
        budget.checkPlannedCalls(
            plannedMapillary = 2,
            plannedSam = if (perceive) 1 else 0,
            plannedGemini = 0
        )
    }

    val mapillaryClient = HttpMapillaryClient(gateway = gateway, budget = budget)
    val overpassClient = HttpOverpassClient(gateway = gateway)
    val panoSource = JvmPanoramaImageSource(mapillarySource = mapillaryClient, gateway = gateway, maxDimension = maxOf(1024, cropSize))
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
        areaName = areaName,
        nowEpochMs = System.currentTimeMillis(),
        cropDimension = cropSize,
        maxPlannedViews = maxViews,
        dryRun = dryRun,
        enablePerception = perceive
    )

    var finalEnv: SimEnvironment? = null
    var perceptionOnlyObjects: List<SimObject> = emptyList()
    var plannedViewsCount = 0

    builder.buildArea(config).collect { event ->
        when (event) {
            is BuildEvent.FetchingSources ->
                println("[build-area] Fetching Mapillary & Overpass sources around (${event.center.latitude}, ${event.center.longitude}), r=${event.radiusMeters}m...")
            is BuildEvent.StationsSelected ->
                println("[build-area] Selected ${event.stations.size} stations out of ${event.totalImagesFound} images.")
            is BuildEvent.ViewsPlanned -> {
                plannedViewsCount = event.views.size
                println("[build-area] Planned ${event.views.size} facade-facing perspective views.")
            }
            is BuildEvent.DryRunPlan ->
                println(
                    "[build-area] DRY-RUN PLAN: views=${event.plannedViews}, " +
                        "plannedSamCalls=${event.plannedSamCalls} (remainingSamAllowance=${event.remainingSamAllowance}), " +
                        "plannedVerifierCalls=${event.plannedVerifierCalls} (remainingGeminiAllowance=${event.remainingGeminiAllowance}), " +
                        "remainingMapillaryAllowance=${event.remainingMapillaryAllowance}, " +
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
        return
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

        val stations = env.stations.orEmpty()
        val initialFootprints = walledBuildings.map { b ->
            val enuVerts = b.polygon.map { localEnu.latLonToEnu(it) }
            org.scottishtecharmy.soundscape.sim.model.BuildingFootprint(
                osmId = b.osmId,
                name = b.name,
                verticesEnu = enuVerts.map { listOf(it.east, it.north) },
                verticesLatLon = b.polygon.map { listOf(it.latitude, it.longitude) }
            )
        }
        val plannedViews = ViewPlanner.planViews(stations, initialFootprints, maxDistanceMeters = 40.0, cropDimension = cropSize)

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
            ViewPlanner.isPointVisibleInPlannedViews(gt.position, plannedViews, stations, maxDistanceMeters = 40.0)
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
            location = areaName,
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
        println("[build-area] Wrote evaluation metrics to ${metricsFile.absolutePath}")
    }
}
