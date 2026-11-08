package org.scottishtecharmy.soundscape.sim.builders

import org.scottishtecharmy.soundscape.sim.eval.SimEval
import org.scottishtecharmy.soundscape.sim.geo.Angles
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.Geometry2D
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import org.scottishtecharmy.soundscape.sim.geo.Ray2D
import org.scottishtecharmy.soundscape.sim.model.BuildingFootprint
import org.scottishtecharmy.soundscape.sim.model.Evidence
import org.scottishtecharmy.soundscape.sim.model.LinkedVenue
import org.scottishtecharmy.soundscape.sim.model.Sign
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimObject
import org.scottishtecharmy.soundscape.sim.model.VerifierVerdict
import org.scottishtecharmy.soundscape.sim.sources.MapillaryMapFeature
import org.scottishtecharmy.soundscape.sim.sources.OsmBuilding
import org.scottishtecharmy.soundscape.sim.sources.OsmEntrance
import org.scottishtecharmy.soundscape.sim.sources.OsmPoi
import kotlin.math.min

/**
 * Single visual sighting of a door, sign, or steps from a panorama station.
 */
data class PerceptionSighting(
    val stationId: String,
    val stationEnu: EnuPoint,
    val category: SimCategory,
    val bearingDegrees: Double,
    val elevationDegrees: Double,
    val box: List<Int>,
    val normalizedBox: List<Double>? = null,
    val detectorConfidence: Double,
    val verifierVerdict: VerifierVerdict = VerifierVerdict.YES,
    val signText: String? = null,
    val signConfidence: Double? = null
)

/**
 * Aggregated output produced by [StreetAggregator].
 */
data class StreetAggregationResult(
    val buildingFootprints: List<BuildingFootprint>,
    val objects: List<SimObject>,
    val signs: List<Sign>,
    val perceptionOnlyObjects: List<SimObject> = emptyList()
)

/**
 * Merges OSM buildings, entrances, and venues, Mapillary map features, and panorama perception sightings
 * into deduplicated, facade-snapped, venue-linked [SimObject] and [Sign] landmarks.
 */
object StreetAggregator {

    fun aggregate(
        localEnu: LocalEnu,
        buildings: List<OsmBuilding>,
        entrances: List<OsmEntrance>,
        pois: List<OsmPoi>,
        mapFeatures: List<MapillaryMapFeature>,
        perceptionSightings: List<PerceptionSighting> = emptyList(),
        dedupRadiusMeters: Double = 3.0,
        maxFacadeSnapMeters: Double = 5.0
    ): StreetAggregationResult {
        val footprints = buildings.map { b ->
            val enuVerts = b.polygon.map { localEnu.latLonToEnu(it) }
            BuildingFootprint(
                osmId = b.osmId,
                name = b.name,
                verticesEnu = enuVerts.map { listOf(it.east, it.north) },
                verticesLatLon = b.polygon.map { listOf(it.latitude, it.longitude) }
            )
        }
        val walledBuildings = buildings.filter { it.tags["building"] != "roof" }.ifEmpty { buildings }
        val footprintPolygonsEnu: List<Pair<OsmBuilding, List<EnuPoint>>> = walledBuildings.map { b ->
            b to b.polygon.map { localEnu.latLonToEnu(it) }
        }.filter { it.second.size >= 3 }

        val rawCandidates = mutableListOf<MutableObjectCandidate>()

        // 1. Ingest OSM entrances
        for (ent in entrances) {
            val rawEnu = localEnu.latLonToEnu(ent.location)
            val snappedEnu = snapToNearestBuildingEdge(rawEnu, footprintPolygonsEnu, maxFacadeSnapMeters) ?: rawEnu
            val snappedLatLon = localEnu.enuToLatLon(snappedEnu)
            rawCandidates.add(
                MutableObjectCandidate(
                    id = "osm-entrance-${ent.osmId.replace("/", "-")}",
                    name = ent.name ?: "Entrance (${ent.entranceType})",
                    category = SimCategory.DOOR,
                    subCategory = ent.entranceType,
                    enu = snappedEnu,
                    latLon = snappedLatLon,
                    confidence = 0.85,
                    sources = mutableSetOf("OSM"),
                    text = ent.name,
                    evidence = mutableListOf(
                        Evidence(
                            stationId = ent.osmId,
                            bearingDegrees = 0.0,
                            detectorConfidence = 0.85,
                            localisationMethod = "OSM_NODE_FACADE_SNAP"
                        )
                    )
                )
            )
        }

        // 2. Ingest Mapillary map features
        for (mf in mapFeatures) {
            val mappedCategory = mapMapillaryClass(mf.objectValue) ?: continue
            val rawEnu = localEnu.latLonToEnu(mf.location)
            val finalEnu = if (mappedCategory == SimCategory.SIGN) {
                snapToNearestBuildingEdge(rawEnu, footprintPolygonsEnu, maxFacadeSnapMeters) ?: rawEnu
            } else {
                rawEnu
            }
            val finalLatLon = localEnu.enuToLatLon(finalEnu)
            rawCandidates.add(
                MutableObjectCandidate(
                    id = "mapillary-${mf.id}",
                    name = readableMapillaryName(mf.objectValue, mappedCategory),
                    category = mappedCategory,
                    subCategory = mf.objectValue,
                    enu = finalEnu,
                    latLon = finalLatLon,
                    confidence = 0.75,
                    sources = mutableSetOf("MAPILLARY"),
                    text = null,
                    evidence = mf.imageIds.take(3).map { imgId ->
                        Evidence(
                            stationId = imgId,
                            bearingDegrees = mf.alignedDirection ?: 0.0,
                            detectorConfidence = 0.75,
                            localisationMethod = "MAPILLARY_FEATURE"
                        )
                    }.toMutableList()
                )
            )
        }

        // 3. Localise and ingest perception sightings (doors, signs, steps)
        val localizedSightings = localizeSightings(
            sightings = perceptionSightings.filter { it.verifierVerdict != VerifierVerdict.NO },
            localEnu = localEnu,
            footprintPolygonsEnu = footprintPolygonsEnu,
            maxFacadeSnapMeters = maxFacadeSnapMeters
        )
        val deduplicatedPerceptionOnly = mergeCandidates(
            localizedSightings.map { it.deepCopy() },
            localEnu,
            dedupRadiusMeters
        ).map { cand ->
            SimObject(
                id = cand.id,
                name = cand.name,
                category = cand.category,
                subCategory = cand.subCategory,
                position = cand.enu.toPositionList(),
                latitude = cand.latLon.latitude,
                longitude = cand.latLon.longitude,
                confidence = cand.confidence.coerceIn(0.0, 0.99),
                sources = cand.sources.toList(),
                text = cand.text,
                evidence = cand.evidence
            )
        }
        rawCandidates.addAll(localizedSightings)

        // 4. Deduplicate & merge within dedupRadiusMeters (3 m) per category
        val merged = mergeCandidates(rawCandidates, localEnu, dedupRadiusMeters)

        // 5. Extract Sign records (from SIGN objects and any OCR text on doors/signs)
        val signs = mutableListOf<Sign>()
        var signCounter = 1
        for (obj in merged) {
            if (obj.category == SimCategory.SIGN || !obj.text.isNullOrBlank()) {
                val signText = obj.text ?: obj.name
                val firstEv = obj.evidence.firstOrNull()
                signs.add(
                    Sign(
                        id = "sign-${signCounter++}",
                        text = signText,
                        confidence = obj.confidence,
                        position = obj.enu.copy(up = if (obj.category == SimCategory.SIGN) 2.2 else 0.0).toPositionList(),
                        latitude = obj.latLon.latitude,
                        longitude = obj.latLon.longitude,
                        stationId = firstEv?.stationId,
                        bearingDegrees = firstEv?.bearingDegrees
                    )
                )
            }
        }

        // 6. Link DOOR objects to OSM venues via sign text fuzzy matching or spatial proximity / shared footprint
        val poisWithEnu = pois.map { poi -> poi to localEnu.latLonToEnu(poi.location) }
        val finalObjects = merged.map { cand ->
            var linkedVenue: LinkedVenue? = null
            var bestText: String? = cand.text

            if (cand.category == SimCategory.DOOR) {
                // Find any sign within 8m of this door that has OCR text
                val nearbySigns = merged.filter {
                    (it.category == SimCategory.SIGN || !it.text.isNullOrBlank()) &&
                        !it.text.isNullOrBlank() &&
                        it.enu.distanceTo2D(cand.enu) <= 8.0
                }
                if (bestText.isNullOrBlank() && nearbySigns.isNotEmpty()) {
                    bestText = nearbySigns.minByOrNull { it.enu.distanceTo2D(cand.enu) }?.text
                }

                // Try matching sign/door text against nearby OSM POIs (within 40m) using Soundscape fuzzyCompare
                if (!bestText.isNullOrBlank()) {
                    val nearbyPois = poisWithEnu.filter { (_, pEnu) -> pEnu.distanceTo2D(cand.enu) <= 40.0 }
                    var bestPoi: OsmPoi? = null
                    var bestScore = Double.POSITIVE_INFINITY
                    for ((poi, _) in nearbyPois) {
                        val fDist = min(
                            SimEval.fuzzyCompare(bestText, poi.name, needleCanBeShorter = true),
                            SimEval.fuzzyCompare(poi.name, bestText, needleCanBeShorter = true)
                        )
                        if (fDist <= 0.35 && fDist < bestScore) {
                            bestScore = fDist
                            bestPoi = poi
                        }
                    }
                    if (bestPoi != null) {
                        linkedVenue = LinkedVenue(
                            osmId = bestPoi.osmId,
                            name = bestPoi.name,
                            category = bestPoi.category,
                            matchScore = 1.0 - bestScore
                        )
                    }
                }

                // Fallback: nearest OSM POI within 15m or enclosing building name
                if (linkedVenue == null) {
                    val closestPoi = poisWithEnu
                        .map { (poi, pEnu) -> poi to pEnu.distanceTo2D(cand.enu) }
                        .filter { it.second <= 15.0 }
                        .minByOrNull { it.second }
                    if (closestPoi != null) {
                        val (poi, dist) = closestPoi
                        linkedVenue = LinkedVenue(
                            osmId = poi.osmId,
                            name = poi.name,
                            category = poi.category,
                            matchScore = (1.0 - (dist / 20.0)).coerceIn(0.5, 0.95)
                        )
                    } else {
                        val closestBuilding = footprintPolygonsEnu
                            .mapNotNull { (b, poly) ->
                                val snap = Geometry2D.snapPointToPolygonEdge(cand.enu, poly, maxFacadeSnapMeters)
                                if (snap != null && !b.name.isNullOrBlank()) b to snap.distanceMeters else null
                            }
                            .minByOrNull { it.second }
                        if (closestBuilding != null) {
                            val (b, _) = closestBuilding
                            linkedVenue = LinkedVenue(
                                osmId = b.osmId,
                                name = b.name!!,
                                category = "building",
                                matchScore = 0.75
                            )
                        }
                    }
                }
            }

            val displayName = when {
                linkedVenue != null && cand.category == SimCategory.DOOR -> "${linkedVenue.name} Entrance"
                !bestText.isNullOrBlank() && cand.category == SimCategory.SIGN -> "Sign: $bestText"
                else -> cand.name
            }

            SimObject(
                id = cand.id,
                name = displayName,
                category = cand.category,
                subCategory = cand.subCategory,
                position = cand.enu.toPositionList(),
                latitude = cand.latLon.latitude,
                longitude = cand.latLon.longitude,
                confidence = cand.confidence.coerceIn(0.0, 0.99),
                sources = cand.sources.toList(),
                text = bestText,
                linkedVenue = linkedVenue,
                evidence = cand.evidence
            )
        }

        return StreetAggregationResult(
            buildingFootprints = footprints,
            objects = finalObjects,
            signs = signs,
            perceptionOnlyObjects = deduplicatedPerceptionOnly
        )
    }

    private fun localizeSightings(
        sightings: List<PerceptionSighting>,
        localEnu: LocalEnu,
        footprintPolygonsEnu: List<Pair<OsmBuilding, List<EnuPoint>>>,
        maxFacadeSnapMeters: Double
    ): List<MutableObjectCandidate> {
        if (sightings.isEmpty()) return emptyList()

        // First project each sighting individually via building footprint ray hit or ground-plane fallback
        data class ProjectedSighting(
            val sighting: PerceptionSighting,
            val ray2D: Ray2D,
            val initialPoint: EnuPoint,
            val usedFootprintHit: Boolean
        )

        val projected = mutableListOf<ProjectedSighting>()
        for (s in sightings) {
            val ray = Ray2D(s.stationEnu, s.bearingDegrees)
            var bestHitDist = Double.POSITIVE_INFINITY
            var bestHitPoint: EnuPoint? = null

            for ((_, poly) in footprintPolygonsEnu) {
                val hit = Geometry2D.intersectRayWithPolygon(ray, poly, maxDistanceMeters = 55.0)
                if (hit != null && hit.distanceMeters < bestHitDist) {
                    bestHitDist = hit.distanceMeters
                    bestHitPoint = hit.point
                }
            }

            val usedFootprint = bestHitPoint != null
            val point = bestHitPoint
                ?: Geometry2D.groundPlanePoint(
                    origin = s.stationEnu,
                    bearingDegrees = s.bearingDegrees,
                    elevationDegrees = s.elevationDegrees.coerceAtMost(-5.0),
                    cameraHeightMeters = 2.0,
                    maxDistanceMeters = 35.0
                )?.let { gp ->
                    snapToNearestBuildingEdge(gp, footprintPolygonsEnu, maxFacadeSnapMeters) ?: gp
                }
                ?: continue

            projected.add(ProjectedSighting(s, ray, point, usedFootprint))
        }

        // Group projected sightings of the same category from different stations within 4.5m to attempt multi-ray triangulation
        val used = BooleanArray(projected.size)
        val result = mutableListOf<MutableObjectCandidate>()
        var idxCounter = 1

        for (i in projected.indices) {
            if (used[i]) continue
            used[i] = true
            val seed = projected[i]
            val cluster = mutableListOf(seed)

            for (j in i + 1 until projected.size) {
                if (used[j]) continue
                val other = projected[j]
                if (other.sighting.category != seed.sighting.category) continue
                if (other.initialPoint.distanceTo2D(seed.initialPoint) <= 4.5) {
                    used[j] = true
                    cluster.add(other)
                }
            }

            val distinctStations = cluster.map { it.sighting.stationId }.distinct()
            var locMethod: String
            val finalEnu: EnuPoint = if (distinctStations.size >= 2) {
                val triangulated = Geometry2D.triangulateRays(
                    rays = cluster.map { it.ray2D },
                    minParallaxDegrees = 6.0,
                    maxDistanceMeters = 45.0
                )?.point
                locMethod = if (triangulated != null) {
                    "MULTI_RAY_TRIANGULATION_FACADE_SNAP"
                } else if (seed.usedFootprintHit) {
                    "SINGLE_RAY_FOOTPRINT_FACADE_SNAP"
                } else {
                    "GROUND_PLANE_FACADE_SNAP"
                }
                val candidatePt = triangulated ?: seed.initialPoint
                snapToNearestBuildingEdge(candidatePt, footprintPolygonsEnu, maxFacadeSnapMeters) ?: candidatePt
            } else {
                locMethod = if (seed.usedFootprintHit) {
                    "SINGLE_RAY_FOOTPRINT_FACADE_SNAP"
                } else {
                    "GROUND_PLANE_FACADE_SNAP"
                }
                snapToNearestBuildingEdge(seed.initialPoint, footprintPolygonsEnu, maxFacadeSnapMeters) ?: seed.initialPoint
            }

            val finalLatLon = localEnu.enuToLatLon(finalEnu)
            val baseConf = cluster.maxOf { it.sighting.detectorConfidence }
            val multiViewBoost = if (distinctStations.size >= 2) 0.12 else 0.0
            val verifierBoost = if (cluster.any { it.sighting.verifierVerdict == VerifierVerdict.YES }) 0.08 else 0.0
            val bestText = cluster.mapNotNull { it.sighting.signText?.takeIf { t -> t.isNotBlank() } }.firstOrNull()

            val sources = mutableSetOf("MAPILLARY", "SAM_3_1")
            if (cluster.any { it.sighting.verifierVerdict == VerifierVerdict.YES } || bestText != null) {
                sources.add("GEMINI_FLASH")
            }

            val catName = when (seed.sighting.category) {
                SimCategory.DOOR -> "Detected Entrance"
                SimCategory.SIGN -> if (bestText != null) "Sign: $bestText" else "Shop Sign"
                SimCategory.STEPS -> "Steps"
                else -> seed.sighting.category.name
            }

            result.add(
                MutableObjectCandidate(
                    id = "perc-${seed.sighting.category.name.lowercase()}-${idxCounter++}",
                    name = catName,
                    category = seed.sighting.category,
                    subCategory = null,
                    enu = finalEnu,
                    latLon = finalLatLon,
                    confidence = (baseConf + multiViewBoost + verifierBoost).coerceIn(0.1, 0.99),
                    sources = sources,
                    text = bestText,
                    evidence = cluster.map {
                        Evidence(
                            stationId = it.sighting.stationId,
                            bearingDegrees = it.sighting.bearingDegrees,
                            elevationDegrees = it.sighting.elevationDegrees,
                            box = it.sighting.box,
                            normalizedBox = it.sighting.normalizedBox,
                            detectorConfidence = it.sighting.detectorConfidence,
                            verifierResult = it.sighting.verifierVerdict,
                            localisationMethod = locMethod
                        )
                    }.toMutableList()
                )
            )
        }

        return result
    }

    private fun mergeCandidates(
        candidates: List<MutableObjectCandidate>,
        localEnu: LocalEnu,
        dedupRadiusMeters: Double
    ): List<MutableObjectCandidate> {
        // Sort so OSM and higher-confidence candidates anchor clusters first
        val sorted = candidates.sortedWith(
            compareByDescending<MutableObjectCandidate> { "OSM" in it.sources }
                .thenByDescending { it.confidence }
        )
        val merged = mutableListOf<MutableObjectCandidate>()

        for (cand in sorted) {
            val match = merged.firstOrNull { existing ->
                existing.category == cand.category &&
                    existing.enu.distanceTo2D(cand.enu) <= dedupRadiusMeters
            }
            if (match == null) {
                merged.add(cand)
            } else {
                // Combine sources, evidence, text, and boost confidence
                match.sources.addAll(cand.sources)
                match.evidence.addAll(cand.evidence)
                if (match.text.isNullOrBlank() && !cand.text.isNullOrBlank()) {
                    match.text = cand.text
                }
                // Weighted average position if neither is OSM, otherwise keep OSM-snapped position
                if ("OSM" !in match.sources) {
                    val w1 = match.confidence
                    val w2 = cand.confidence
                    val wSum = (w1 + w2).coerceAtLeast(1e-6)
                    val newEnu = EnuPoint(
                        east = (match.enu.east * w1 + cand.enu.east * w2) / wSum,
                        north = (match.enu.north * w1 + cand.enu.north * w2) / wSum,
                        up = match.enu.up
                    )
                    match.enu = newEnu
                    match.latLon = localEnu.enuToLatLon(newEnu)
                }
                match.confidence = (maxOf(match.confidence, cand.confidence) + 0.08).coerceAtMost(0.99)
            }
        }
        return merged
    }

    private fun snapToNearestBuildingEdge(
        point: EnuPoint,
        footprintPolygonsEnu: List<Pair<OsmBuilding, List<EnuPoint>>>,
        maxDistanceMeters: Double
    ): EnuPoint? {
        var bestDist = Double.POSITIVE_INFINITY
        var bestPoint: EnuPoint? = null
        for ((_, poly) in footprintPolygonsEnu) {
            val snap = Geometry2D.snapPointToPolygonEdge(point, poly, maxDistanceMeters)
            if (snap != null && snap.distanceMeters < bestDist) {
                bestDist = snap.distanceMeters
                bestPoint = snap.snappedPoint
            }
        }
        return bestPoint
    }

    fun mapMapillaryClass(objectValue: String): SimCategory? = when {
        objectValue == "object--sign--store" || objectValue == "object--sign--information" -> SimCategory.SIGN
        objectValue == "object--bench" -> SimCategory.BENCH
        objectValue == "object--trash-can" -> SimCategory.TRASH_CAN
        objectValue == "object--bike-rack" -> SimCategory.BIKE_RACK
        objectValue == "marking--discrete--crosswalk-zebra" ||
            objectValue == "construction--flat--crosswalk-plain" -> SimCategory.CROSSWALK
        objectValue == "construction--flat--curb-cut" || objectValue == "object--ramp" -> SimCategory.CURB_CUT
        objectValue.startsWith("object--traffic-light--pedestrians") -> SimCategory.PEDESTRIAN_SIGNAL
        objectValue == "object--street-light" -> SimCategory.STREET_LIGHT
        else -> null
    }

    private fun readableMapillaryName(objectValue: String, category: SimCategory): String = when (category) {
        SimCategory.SIGN -> if (objectValue.endsWith("information")) "Information Sign" else "Store Sign"
        SimCategory.BENCH -> "Bench"
        SimCategory.TRASH_CAN -> "Trash Can"
        SimCategory.BIKE_RACK -> "Bike Rack"
        SimCategory.CROSSWALK -> "Crosswalk"
        SimCategory.CURB_CUT -> "Curb Cut / Ramp"
        SimCategory.PEDESTRIAN_SIGNAL -> "Pedestrian Crossing Signal"
        SimCategory.STREET_LIGHT -> "Street Light"
        else -> objectValue
    }

    private class MutableObjectCandidate(
        val id: String,
        val name: String,
        val category: SimCategory,
        val subCategory: String?,
        var enu: EnuPoint,
        var latLon: org.scottishtecharmy.soundscape.sim.geo.LatLon,
        var confidence: Double,
        val sources: MutableSet<String>,
        var text: String?,
        val evidence: MutableList<Evidence>
    ) {
        fun deepCopy(): MutableObjectCandidate = MutableObjectCandidate(
            id = id,
            name = name,
            category = category,
            subCategory = subCategory,
            enu = enu,
            latLon = latLon,
            confidence = confidence,
            sources = sources.toMutableSet(),
            text = text,
            evidence = evidence.toMutableList()
        )
    }
}
