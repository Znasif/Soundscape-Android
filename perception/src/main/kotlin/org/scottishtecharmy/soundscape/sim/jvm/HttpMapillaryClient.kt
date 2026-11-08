package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.scottishtecharmy.soundscape.sim.geo.BoundingBox
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.MapillaryDetection
import org.scottishtecharmy.soundscape.sim.sources.MapillaryImage
import org.scottishtecharmy.soundscape.sim.sources.MapillaryMapFeature
import org.scottishtecharmy.soundscape.sim.sources.MapillarySource

/**
 * JVM implementation of [MapillarySource] backed by [HttpGateway] and gated by [Budget].
 *
 * Uses header `Authorization: OAuth $MAPILLARY_TOKEN` exclusively (never putting tokens in query strings).
 */
class HttpMapillaryClient(
    private val gateway: HttpGateway,
    private val budget: Budget = Budget(),
    private val tokenProvider: () -> String = { System.getenv("MAPILLARY_TOKEN") ?: "" }
) : MapillarySource {

    private val json = Json { ignoreUnknownKeys = true }

    private fun authHeaders(): Map<String, String> {
        val token = tokenProvider()
        return if (token.isNotBlank()) mapOf("Authorization" to "OAuth $token") else emptyMap()
    }

    override suspend fun getImagesInBbox(bbox: BoundingBox, limit: Int): List<MapillaryImage> {
        val fKey = "mapillary_images_bbox"
        budget.consumeMapillary(1, isReplay = gateway.hasReplayFixture(fKey))
        val effectiveLimit = minOf(limit, 350)
        val fields = "id,is_pano,compass_angle,computed_compass_angle,captured_at,sequence,geometry,computed_geometry"
        val url = "https://graph.mapillary.com/images?bbox=${bbox.toMapillaryBboxString()}&limit=$effectiveLimit&fields=$fields"
        val resp = gateway.get(
            url = url,
            headers = authHeaders(),
            fixtureKey = fKey
        )
        val root = json.parseToJsonElement(resp.body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { parseImage(it.jsonObject) }
    }

    override suspend fun getImageById(imageId: String): MapillaryImage? {
        val fKey = "mapillary_image_$imageId"
        budget.consumeMapillary(1, isReplay = gateway.hasReplayFixture(fKey))
        val fields = "id,is_pano,compass_angle,computed_compass_angle,captured_at,sequence,geometry,computed_geometry,width,height,thumb_1024_url,thumb_2048_url"
        val url = "https://graph.mapillary.com/$imageId?fields=$fields"
        val resp = gateway.get(
            url = url,
            headers = authHeaders(),
            fixtureKey = fKey
        )
        val obj = json.parseToJsonElement(resp.body).jsonObject
        return parseImage(obj)
    }

    override suspend fun getMapFeaturesInBbox(bbox: BoundingBox, limit: Int): List<MapillaryMapFeature> {
        val fKey = "mapillary_map_features_bbox"
        budget.consumeMapillary(1, isReplay = gateway.hasReplayFixture(fKey))
        val effectiveLimit = minOf(limit, 150)
        val fields = "id,aligned_direction,first_seen_at,last_seen_at,object_value,object_type,geometry"
        val url = "https://graph.mapillary.com/map_features?bbox=${bbox.toMapillaryBboxString()}&limit=$effectiveLimit&fields=$fields"
        val resp = gateway.get(
            url = url,
            headers = authHeaders(),
            fixtureKey = fKey
        )
        val root = json.parseToJsonElement(resp.body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { elem ->
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val objectValue = obj["object_value"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val loc = parseGeoJsonPoint(obj["geometry"]?.jsonObject) ?: return@mapNotNull null
            val imageIds = obj["images"]?.jsonObject?.get("data")?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                ?: emptyList()
            MapillaryMapFeature(
                id = id,
                objectValue = objectValue,
                objectType = obj["object_type"]?.jsonPrimitive?.content,
                alignedDirection = obj["aligned_direction"]?.jsonPrimitive?.doubleOrNull,
                firstSeenAtEpochMs = obj["first_seen_at"]?.jsonPrimitive?.longOrNull,
                lastSeenAtEpochMs = obj["last_seen_at"]?.jsonPrimitive?.longOrNull,
                location = loc,
                imageIds = imageIds
            )
        }
    }

    override suspend fun getImageDetections(imageId: String): List<MapillaryDetection> {
        val fKey = "mapillary_detections_$imageId"
        budget.consumeMapillary(1, isReplay = gateway.hasReplayFixture(fKey))
        val fields = "id,value,geometry,created_at"
        val url = "https://graph.mapillary.com/$imageId/detections?fields=$fields"
        val resp = gateway.get(
            url = url,
            headers = authHeaders(),
            fixtureKey = fKey
        )
        val root = json.parseToJsonElement(resp.body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { elem ->
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val value = obj["value"]?.jsonPrimitive?.content ?: return@mapNotNull null
            MapillaryDetection(
                id = id,
                imageId = imageId,
                value = value,
                createdAtEpochMs = obj["created_at"]?.jsonPrimitive?.longOrNull,
                geometryBase64 = obj["geometry"]?.jsonPrimitive?.content
            )
        }
    }

    private fun parseImage(obj: JsonObject): MapillaryImage? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val loc = parseGeoJsonPoint(obj["geometry"]?.jsonObject)
            ?: parseGeoJsonPoint(obj["computed_geometry"]?.jsonObject)
            ?: return null
        val compLoc = parseGeoJsonPoint(obj["computed_geometry"]?.jsonObject)
        return MapillaryImage(
            id = id,
            isPano = obj["is_pano"]?.jsonPrimitive?.booleanOrNull ?: false,
            compassAngle = obj["compass_angle"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            computedCompassAngle = obj["computed_compass_angle"]?.jsonPrimitive?.doubleOrNull,
            capturedAtEpochMs = obj["captured_at"]?.jsonPrimitive?.longOrNull ?: 0L,
            sequenceId = obj["sequence"]?.jsonPrimitive?.content,
            location = loc,
            computedLocation = compLoc,
            width = obj["width"]?.jsonPrimitive?.intOrNull,
            height = obj["height"]?.jsonPrimitive?.intOrNull,
            thumb1024Url = obj["thumb_1024_url"]?.jsonPrimitive?.content,
            thumb2048Url = obj["thumb_2048_url"]?.jsonPrimitive?.content
        )
    }

    private fun parseGeoJsonPoint(geom: JsonObject?): LatLon? {
        if (geom == null) return null
        val coords = geom["coordinates"]?.jsonArray ?: return null
        if (coords.size < 2) return null
        val lon = coords[0].jsonPrimitive.doubleOrNull ?: return null
        val lat = coords[1].jsonPrimitive.doubleOrNull ?: return null
        return LatLon(latitude = lat, longitude = lon)
    }
}
