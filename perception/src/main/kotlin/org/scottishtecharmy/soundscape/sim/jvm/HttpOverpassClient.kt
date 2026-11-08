package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.sources.OsmBuilding
import org.scottishtecharmy.soundscape.sim.sources.OsmEntrance
import org.scottishtecharmy.soundscape.sim.sources.OsmPoi
import org.scottishtecharmy.soundscape.sim.sources.OverpassAreaData
import org.scottishtecharmy.soundscape.sim.sources.OverpassSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * JVM implementation of [OverpassSource] backed by [HttpGateway].
 *
 * Uses the scoped Overpass QL query (D-07) to fetch buildings with footprint geometry,
 * `entrance=*` nodes, and named POIs without pulling unbounded regional route relations.
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
                    fixtureKey = "overpass_area_query"
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

    fun parseOverpassJson(rawJson: String): OverpassAreaData {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val elements = root["elements"]?.jsonArray ?: return OverpassAreaData()

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
                val poly = extractPolygon(obj)
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

            // 3. Named POI (excluding bare buildings, highways, and railways)
            if (!name.isNullOrBlank() && "building" !in tags && "highway" !in tags && "railway" !in tags) {
                val category = tags["shop"]
                    ?: tags["amenity"]
                    ?: tags["tourism"]
                    ?: tags["office"]
                    ?: tags["craft"]
                    ?: tags["leisure"]
                    ?: tags["ferry"]
                    ?: "poi"
                val poly = if (type != "node") extractPolygon(obj) else emptyList()
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

    private fun extractPolygon(obj: JsonObject): List<LatLon> {
        val geom = obj["geometry"]?.jsonArray
        if (geom != null && geom.isNotEmpty()) {
            return geom.mapNotNull { pt ->
                val pObj = pt.jsonObject
                val lat = pObj["lat"]?.jsonPrimitive?.doubleOrNull
                val lon = pObj["lon"]?.jsonPrimitive?.doubleOrNull
                if (lat != null && lon != null) LatLon(lat, lon) else null
            }
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
