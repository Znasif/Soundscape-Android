package org.scottishtecharmy.soundscape.sim.builders

import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.Geometry2D
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import org.scottishtecharmy.soundscape.sim.geo.PerspectiveCamera
import org.scottishtecharmy.soundscape.sim.geo.Ray2D
import org.scottishtecharmy.soundscape.sim.model.BuildingFootprint
import org.scottishtecharmy.soundscape.sim.model.Station
import org.scottishtecharmy.soundscape.sim.sources.MapillaryImage

/**
 * Planned perspective camera view from a selected [Station] toward a nearby building facade.
 */
data class PlannedView(
    val station: Station,
    val stationEnu: EnuPoint,
    val yawDegrees: Double,
    val hFovDegrees: Double = 90.0,
    val hitBuildingId: String,
    val hitDistanceMeters: Double,
    val camera: PerspectiveCamera = PerspectiveCamera(
        yawDegrees = yawDegrees,
        pitchDegrees = 0.0,
        hFovDegrees = hFovDegrees,
        width = 640,
        height = 640
    )
)

/**
 * Selects up to [maxStations] (default `12`) panorama stations spaced at least [minSpacingMeters] (default `8.0 m`)
 * apart, prioritizing `is_pano == true` first and newer `captured_at` timestamps second.
 */
object StationSelector {
    fun selectStations(
        images: List<MapillaryImage>,
        localEnu: LocalEnu,
        maxStations: Int = 12,
        minSpacingMeters: Double = 8.0,
        preferCloseToFootprints: List<List<EnuPoint>> = emptyList(),
        maxDistanceToBuildingMeters: Double = 45.0,
        onlyPanosWhenAvailable: Boolean = true
    ): List<Station> {
        if (images.isEmpty() || maxStations <= 0) return emptyList()

        data class ScoredCandidate(
            val image: MapillaryImage,
            val enu: EnuPoint,
            val minBuildingDist: Double
        )

        val candidates = images.map { img ->
            val enu = localEnu.latLonToEnu(img.effectiveLocation)
            val bDist = if (preferCloseToFootprints.isEmpty()) {
                0.0
            } else {
                preferCloseToFootprints.minOfOrNull { poly ->
                    Geometry2D.snapPointToPolygonEdge(enu, poly, maxDistanceMeters = 1000.0)?.distanceMeters
                        ?: Double.POSITIVE_INFINITY
                } ?: Double.POSITIVE_INFINITY
            }
            ScoredCandidate(img, enu, bDist)
        }

        val panoCandidates = if (onlyPanosWhenAvailable) {
            candidates.filter { it.image.isPano }.ifEmpty { candidates }
        } else {
            candidates
        }

        // Sort: isPano descending first, then within maxDistanceToBuildingMeters, then capturedAtEpochMs descending, then id ascending
        val sorted = panoCandidates.sortedWith(
            compareByDescending<ScoredCandidate> { it.image.isPano }
                .thenBy { it.minBuildingDist > maxDistanceToBuildingMeters }
                .thenByDescending { it.image.capturedAtEpochMs }
                .thenBy { it.image.id }
        )

        val selected = mutableListOf<ScoredCandidate>()
        for (cand in sorted) {
            if (selected.size >= maxStations) break
            val tooClose = selected.any { existing ->
                existing.enu.distanceTo2D(cand.enu) < minSpacingMeters
            }
            if (!tooClose) {
                selected.add(cand)
            }
        }

        return selected.map { c ->
            val loc = c.image.effectiveLocation
            Station(
                id = c.image.id,
                sequenceId = c.image.sequenceId,
                latitude = loc.latitude,
                longitude = loc.longitude,
                compassAngle = c.image.effectiveCompassAngle,
                capturedAtEpochMs = c.image.capturedAtEpochMs,
                isPano = c.image.isPano,
                position = c.enu.toPositionList()
            )
        }
    }
}

/**
 * Plans facade-facing 90° perspective views for each selected [Station].
 *
 * Considers the 8 cardinal/intercardinal yaw directions (`0°, 45°, 90°, 135°, 180°, 225°, 270°, 315°`)
 * and keeps only views whose optical ray (or left/right quarter-FOV rays) intersects a building footprint
 * within [maxDistanceMeters] (default `40.0 m`).
 */
object ViewPlanner {
    val EIGHT_YAWS: List<Double> = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)

    fun planViews(
        stations: List<Station>,
        buildingFootprints: List<BuildingFootprint>,
        maxDistanceMeters: Double = 40.0,
        hFovDegrees: Double = 90.0
    ): List<PlannedView> {
        if (stations.isEmpty() || buildingFootprints.isEmpty()) return emptyList()

        val footprintsEnu = buildingFootprints.map { bf ->
            bf.osmId to bf.verticesEnu.map { v -> EnuPoint(east = v[0], north = v[1], up = 0.0) }
        }.filter { it.second.size >= 3 }

        val planned = mutableListOf<PlannedView>()
        for (station in stations) {
            val stationEnu = EnuPoint.fromPositionList(station.position)
            for (yaw in EIGHT_YAWS) {
                val ray = Ray2D(stationEnu, yaw)
                var bestBuildingId: String? = null
                var bestDist = Double.POSITIVE_INFINITY

                for ((bId, poly) in footprintsEnu) {
                    val hit = Geometry2D.intersectRayWithPolygon(
                        ray = ray,
                        polygonVertices = poly,
                        maxDistanceMeters = maxDistanceMeters
                    )
                    if (hit != null && hit.distanceMeters < bestDist) {
                        bestDist = hit.distanceMeters
                        bestBuildingId = bId
                    }
                }

                if (bestBuildingId != null) {
                    planned.add(
                        PlannedView(
                            station = station,
                            stationEnu = stationEnu,
                            yawDegrees = yaw,
                            hFovDegrees = hFovDegrees,
                            hitBuildingId = bestBuildingId,
                            hitDistanceMeters = bestDist
                        )
                    )
                }
            }
        }
        return planned
    }
}
