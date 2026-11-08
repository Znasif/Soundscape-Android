package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.scottishtecharmy.soundscape.sim.builders.BuildEvent
import org.scottishtecharmy.soundscape.sim.builders.StreetBuildConfig
import org.scottishtecharmy.soundscape.sim.builders.StreetBuilder
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.sources.Budget
import java.io.File

/**
 * Command-line entry point for building outdoor Street Preview manifests and recording test fixtures.
 *
 * Usage:
 *   `build-area --lat <deg> --lon <deg> --radius <m> --out <file> [--record <dir>] [--replay <dir>] [--max-sam-calls <n>] [--max-verifier-calls <n>] [--dry-run] [--perceive]`
 */
fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty() || args[0] != "build-area") {
        println("Usage: build-area --lat <deg> --lon <deg> --radius <m> --out <file> [--record <dir>] [--replay <dir>] [--max-sam-calls <n>] [--max-verifier-calls <n>] [--dry-run] [--perceive]")
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
    var perceive = false

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

    val builder = StreetBuilder(
        mapillarySource = mapillaryClient,
        overpassSource = overpassClient,
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
    builder.buildArea(config).collect { event ->
        when (event) {
            is BuildEvent.FetchingSources ->
                println("[build-area] Fetching Mapillary & Overpass sources around (${event.center.latitude}, ${event.center.longitude}), r=${event.radiusMeters}m...")
            is BuildEvent.StationsSelected ->
                println("[build-area] Selected ${event.stations.size} stations out of ${event.totalImagesFound} images.")
            is BuildEvent.ViewsPlanned ->
                println("[build-area] Planned ${event.views.size} facade-facing perspective views.")
            is BuildEvent.PerceptionProgress ->
                println("[build-area] Perception progress: ${event.completedViews}/${event.totalViews} views (${event.sightingsSoFar} sightings).")
            is BuildEvent.CacheHit ->
                println("[build-area] Cache hit for geohash ${event.geohash7}.")
            is BuildEvent.Completed -> {
                finalEnv = event.environment
                println("[build-area] Completed manifest with ${event.environment.objects?.size ?: 0} objects, ${event.environment.signs?.size ?: 0} signs, ${event.environment.stations?.size ?: 0} stations.")
            }
        }
    }

    val env = finalEnv ?: error("StreetBuilder did not emit Completed event")
    val outFile = File(outPath)
    outFile.parentFile?.mkdirs()
    outFile.writeText(env.toJson())
    println("[build-area] Wrote manifest to ${outFile.absolutePath}. Budget summary: ${budget.summary()}")
}
