package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import org.scottishtecharmy.soundscape.sim.sources.OsmBuilding
import org.scottishtecharmy.soundscape.sim.sources.OsmEntrance
import org.scottishtecharmy.soundscape.sim.sources.OsmPoi
import org.scottishtecharmy.soundscape.sim.sources.OverpassAreaData
import org.scottishtecharmy.soundscape.sim.sources.OverpassSource
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * JVM implementation of [OverpassSource] backed by [HttpGateway].
 *
 * Uses the scoped Overpass QL query (D-07) to fetch buildings with footprint geometry,
 * `entrance=*` nodes, and named POIs, and automatically falls back to OpenStreetMap API v0.6
 * (`https://api.openstreetmap.org/api/0.6/map.json?bbox=...`) if public Overpass servers time out or return 5xx,
 * compacting the response into the standard Overpass `elements` JSON structure before recording fixtures.
 */
class HttpOverpassClient(
    private val gateway: HttpGateway,
    private val endpoints: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )
) : OverpassSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun queryArea(center: LatLon, radiusMeters: Double): OverpassAreaData {
        val fKey = "overpass_area_query"
        if (gateway.hasReplayFixture(fKey)) {
            val replay = gateway.post(
                url = endpoints.first(),
                body = "",
                fixtureKey = fKey
            )
            return parseOverpassJson(replay.body)
        }

        // First try fast OSM API v0.6 map.json bbox endpoint when radius <= 400m
        val bbox = LocalEnu(center.latitude, center.longitude).boundingBox(radiusMeters)
        val osmMapUrl = "https://api.openstreetmap.org/api/0.6/map.json?bbox=${bbox.toMapillaryBboxString()}"
        try {
            val osmRaw = gateway.get(
                url = osmMapUrl,
                headers = mapOf("User-Agent" to "Soundscape-Argon/1.0"),
                fixtureKey = null
            )
            val compactedBody = compactOsmMapJsonToOverpassFormat(osmRaw.body)
            if (gateway.recordDir != null) {
                gateway.recordDir.mkdirs()
                val fixture = RecordedHttpFixture(
                    fixtureKey = fKey,
                    method = "GET",
                    url = osmMapUrl,
                    statusCode = 200,
                    headers = emptyMap(),
                    body = compactedBody
                )
                val prettyJson = Json { prettyPrint = true }
                File(gateway.recordDir, "$fKey.json").writeText(
                    prettyJson.encodeToString(RecordedHttpFixture.serializer(), fixture)
                )
            }
            return parseOverpassJson(compactedBody)
        } catch (t: Throwable) {
            if (gateway.replayOnly) throw t
        }

        val rInt = radiusMeters.toInt().coerceAtLeast(10)
        val lat = center.latitude
        val lon = center.longitude
        val query = """
            [out:json][timeout:30];
            (
              way["building"](around:$rInt,$lat,$lon);
              relation["building"](around:$rInt,$lat,$lon);
              node["entrance"](around:$rInt,$lat,$lon);
              node["name"](around:$rInt,$lat,$lon);
              way["name"]["highway"!~".*"]["railway"!~".*"](around:$rInt,$lat,$lon);
            );
            out body geom;
        """.trimIndent()

        val formBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
        var lastError: Throwable? = null
        var respBody: String? = null

        for (endpoint in endpoints) {
            try {
                val fixture = gateway.post(
                    url = endpoint,
                    body = formBody,
                    contentType = "application/x-www-form-urlencoded",
                    fixtureKey = fKey
                )
                respBody = fixture.body
                break
            } catch (t: Throwable) {
                lastError = t
                if (gateway.replayOnly) throw t
            }
        }

        val body = respBody ?: throw (lastError ?: IllegalStateException("Overpass query failed"))
        return parseOverpassJson(body)
    }

    /**
     * Converts a raw OSM API v0.6 `map.json` response into a compact Overpass `out body geom` JSON string,
     * resolving way `nodes` into inline `geometry` and discarding bare untagged vertex nodes so fixtures stay small.
     */
    fun compactOsmMapJsonToOverpassFormat(rawJson: String): String {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val elements = root["elements"]?.jsonArray ?: return rawJson

        val nodeCoords = mutableMapOf<Long, Pair<Double, Double>>()
        for (elem in elements) {
            val obj = elem.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "node") {
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
                val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
                nodeCoords[id] = lat to lon
            }
        }

        val waysCoords = mutableMapOf<Long, List<Pair<Double, Double>>>()
        for (elem in elements) {
            val obj = elem.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "way") {
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                val nIds = obj["nodes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty()
                val pts = nIds.mapNotNull { nodeCoords[it] }
                if (pts.isNotEmpty()) {
                    waysCoords[id] = pts
                }
            }
        }

        val keptElements = buildJsonArray {
            for (elem in elements) {
                val obj = elem.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content ?: continue
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                val tagsObj = obj["tags"]?.jsonObject ?: continue
                if (tagsObj.isEmpty()) continue

                val hasBuilding = "building" in tagsObj
                val hasEntrance = "entrance" in tagsObj
                val hasName = "name" in tagsObj
                val hasBusStop = tagsObj["highway"]?.jsonPrimitive?.content == "bus_stop" ||
                    tagsObj["public_transport"]?.jsonPrimitive?.content == "platform"
                val hasCrossing = tagsObj["highway"]?.jsonPrimitive?.content == "crossing"

                if (!hasBuilding && !hasEntrance && !hasName && !hasBusStop && !hasCrossing) continue

                if (type == "node") {
                    add(obj)
                } else if (type == "way") {
                    val pts = waysCoords[id].orEmpty()
                    if (pts.isEmpty()) continue
                    add(buildJsonObject {
                        put("type", "way")
                        put("id", id)
                        put("tags", tagsObj)
                        put("geometry", buildJsonArray {
                            for ((lat, lon) in pts) {
                                add(buildJsonObject {
                                    put("lat", lat)
                                    put("lon", lon)
                                })
                            }
                        })
                    })
                } else if (type == "relation" && hasBuilding) {
                    val members = obj["members"]?.jsonArray.orEmpty()
                    var outerPts: List<Pair<Double, Double>> = emptyList()
                    for (m in members) {
                        val mObj = m.jsonObject
                        val mType = mObj["type"]?.jsonPrimitive?.content
                        val mRef = mObj["ref"]?.jsonPrimitive?.longOrNull
                        val role = mObj["role"]?.jsonPrimitive?.content ?: ""
                        if (mType == "way" && mRef != null && (role == "outer" || role.isEmpty())) {
                            val pts = waysCoords[mRef].orEmpty()
                            if (pts.size >= 3) {
                                outerPts = pts
                                break
                            }
                        }
                    }
                    if (outerPts.size >= 3) {
                        add(buildJsonObject {
                            put("type", "relation")
                            put("id", id)
                            put("tags", tagsObj)
                            put("geometry", buildJsonArray {
                                for ((lat, lon) in outerPts) {
                                    add(buildJsonObject {
                                        put("lat", lat)
                                        put("lon", lon)
                                    })
                                }
                            })
                        })
                    }
                }
            }
        }

        return buildJsonObject {
            put("elements", keptElements)
        }.toString()
    }

    fun parseOverpassJson(rawJson: String): OverpassAreaData {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val elements = root["elements"]?.jsonArray ?: return OverpassAreaData()

        val nodeCoords = mutableMapOf<Long, LatLon>()
        for (elem in elements) {
            val obj = elem.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "node") {
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
                val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
                val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
                nodeCoords[id] = LatLon(lat, lon)
            }
        }

        val buildings = mutableListOf<OsmBuilding>()
        val entrances = mutableListOf<OsmEntrance>()
        val pois = mutableListOf<OsmPoi>()

        for (elem in elements) {
            val obj = elem.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: continue
            val idNum = obj["id"]?.jsonPrimitive?.longOrNull ?: continue
            val osmId = "$type/$idNum"
            val tagsObj = obj["tags"]?.jsonObject
            val tags = tagsObj?.entries?.associate { (k, v) -> k to v.jsonPrimitive.content } ?: emptyMap()
            val name = tags["name"]

            // 1. Building footprint
            if ("building" in tags && (type == "way" || type == "relation")) {
                val poly = extractPolygon(obj, nodeCoords)
                if (poly.size >= 3) {
                    buildings.add(
                        OsmBuilding(
                            osmId = osmId,
                            name = name,
                            polygon = poly,
                            tags = tags
                        )
                    )
                }
            }

            // 2. Entrance node
            val entranceType = tags["entrance"]
            if (entranceType != null && type == "node") {
                val loc = extractPoint(obj)
                if (loc != null) {
                    entrances.add(
                        OsmEntrance(
                            osmId = osmId,
                            entranceType = entranceType,
                            name = name,
                            location = loc,
                            tags = tags
                        )
                    )
                }
            }

            // 3. Named POI (excluding bare buildings, highways, and railways, except bus_stop)
            val isBusStop = tags["highway"] == "bus_stop" || tags["public_transport"] == "platform"
            if (!name.isNullOrBlank() && "building" !in tags && ("highway" !in tags || isBusStop) && "railway" !in tags) {
                val category = if (isBusStop) {
                    "bus_stop"
                } else {
                    tags["shop"]
                        ?: tags["amenity"]
                        ?: tags["tourism"]
                        ?: tags["office"]
                        ?: tags["craft"]
                        ?: tags["leisure"]
                        ?: tags["ferry"]
                        ?: "poi"
                }
                val poly = if (type != "node") extractPolygon(obj, nodeCoords) else emptyList()
                val loc = extractPoint(obj) ?: poly.firstOrNull()
                if (loc != null) {
                    pois.add(
                        OsmPoi(
                            osmId = osmId,
                            name = name,
                            category = category,
                            location = loc,
                            polygon = poly,
                            tags = tags
                        )
                    )
                }
            }
        }

        return OverpassAreaData(
            buildings = buildings,
            entrances = entrances,
            pois = pois
        )
    }

    private fun extractPoint(obj: JsonObject): LatLon? {
        val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull
        val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull
        if (lat != null && lon != null) return LatLon(lat, lon)
        val center = obj["center"]?.jsonObject
        if (center != null) {
            val cLat = center["lat"]?.jsonPrimitive?.doubleOrNull
            val cLon = center["lon"]?.jsonPrimitive?.doubleOrNull
            if (cLat != null && cLon != null) return LatLon(cLat, cLon)
        }
        return null
    }

    private fun extractPolygon(obj: JsonObject, nodeCoords: Map<Long, LatLon>): List<LatLon> {
        val geom = obj["geometry"]?.jsonArray
        if (geom != null && geom.isNotEmpty()) {
            return geom.mapNotNull { pt ->
                val pObj = pt.jsonObject
                val lat = pObj["lat"]?.jsonPrimitive?.doubleOrNull
                val lon = pObj["lon"]?.jsonPrimitive?.doubleOrNull
                if (lat != null && lon != null) LatLon(lat, lon) else null
            }
        }
        val nodesArr = obj["nodes"]?.jsonArray
        if (nodesArr != null && nodesArr.isNotEmpty()) {
            val pts = nodesArr.mapNotNull { n -> nodeCoords[n.jsonPrimitive.longOrNull] }
            if (pts.size >= 3) return pts
        }
        // Relation members outer ways fallback
        val members = obj["members"]?.jsonArray ?: return emptyList()
        for (m in members) {
            val mObj = m.jsonObject
            val role = mObj["role"]?.jsonPrimitive?.content ?: ""
            if (role == "outer" || role.isEmpty()) {
                val mGeom = mObj["geometry"]?.jsonArray
                if (mGeom != null && mGeom.size >= 3) {
                    return mGeom.mapNotNull { pt ->
                        val pObj = pt.jsonObject
                        val lat = pObj["lat"]?.jsonPrimitive?.doubleOrNull
                        val lon = pObj["lon"]?.jsonPrimitive?.doubleOrNull
                        if (lat != null && lon != null) LatLon(lat, lon) else null
                    }
                }
            }
        }
        return emptyList()
    }
}
