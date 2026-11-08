package org.scottishtecharmy.soundscape.sim.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared JSON serializer configured to ignore unknown fields on read and omit default/null values on write.
 * Keeps `SimEnvironment` strictly backward- and forward-compatible with XR Blocks manifests.
 */
@OptIn(ExperimentalSerializationApi::class)
val SimJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    prettyPrint = true
}

/**
 * Environment kind distinguishing outdoor street previews from indoor head-turn room scans.
 */
@Serializable
enum class EnvironmentKind {
    STREET,
    ROOM,
    XRBLOCKS
}

/**
 * Semantic category of a perception-derived or map-derived landmark.
 */
@Serializable
enum class SimCategory {
    DOOR,
    SIGN,
    STEPS,
    STAIRS,
    COUNTER,
    RECEPTION_DESK,
    DESK,
    CHAIR,
    TABLE,
    SOFA,
    LIFT,
    WINDOW,
    FLOOR,
    BENCH,
    TRASH_CAN,
    BIKE_RACK,
    CROSSWALK,
    CURB_CUT,
    PEDESTRIAN_SIGNAL,
    STREET_LIGHT,
    BUS_STOP,
    BUILDING,
    OTHER
}

/**
 * Tri-state crop verification result returned by `Verifier`.
 */
@Serializable
enum class VerifierVerdict {
    YES,
    NO,
    INSUFFICIENT
}

/**
 * Geographic anchor tying local East-North-Up (ENU) coordinates to WGS84 latitude/longitude.
 *
 * @property latitude Anchor latitude in decimal degrees.
 * @property longitude Anchor longitude in decimal degrees.
 * @property yawDegrees Rotation offset (clockwise from true North) of the local frame in degrees.
 * @property altitudeMeters Optional altitude in metres above WGS84 ellipsoid.
 * @property source Optional anchor source (e.g. `"gps_coarse"`, `"building_entrance"`).
 */
@Serializable
data class GeoAnchor(
    val latitude: Double,
    val longitude: Double,
    val yawDegrees: Double = 0.0,
    val altitudeMeters: Double? = null,
    val source: String? = null
)

/**
 * Provenance metadata for cache invalidation and auditability.
 *
 * @property createdAtEpochMs Creation timestamp in epoch milliseconds.
 * @property builderVersion Builder version string (e.g. `"0.1.0"`).
 * @property sources Identifiers of data/model sources used (`"OSM"`, `"MAPILLARY"`, `"SAM_3_1"`, `"GEMINI_FLASH"`).
 * @property newestCaptureEpochMs Newest `captured_at` timestamp (epoch ms) among imagery stations in the area.
 * @property geohash7 Precision-7 geohash of the anchor location.
 */
@Serializable
data class Provenance(
    val createdAtEpochMs: Long,
    val builderVersion: String,
    val sources: List<String> = emptyList(),
    val newestCaptureEpochMs: Long? = null,
    val geohash7: String? = null
)

/**
 * Single observation supporting a localized `SimObject`.
 *
 * @property stationId Mapillary image ID or indoor capture frame ID.
 * @property bearingDegrees World compass bearing (`[0, 360)`) from the station to the object.
 * @property elevationDegrees Elevation angle (`[-90, 90]`) from the horizon to the object contact point.
 * @property box Optional pixel bounding box `[x1, y1, x2, y2]` in the perspective crop.
 * @property detectorConfidence Confidence score in `[0.0, 1.0]` from segmentation/detection.
 * @property verifierResult Optional tri-state verification verdict from `Verifier`.
 */
@Serializable
data class Evidence(
    val stationId: String,
    val bearingDegrees: Double,
    val elevationDegrees: Double? = null,
    val box: List<Int>? = null,
    val normalizedBox: List<Double>? = null,
    val detectorConfidence: Double? = null,
    val verifierResult: VerifierVerdict? = null,
    val localisationMethod: String? = null
)

/**
 * Venue/POI linked to an entrance or sign via spatial proximity or OCR fuzzy matching.
 */
@Serializable
data class LinkedVenue(
    val osmId: String? = null,
    val name: String,
    val category: String? = null,
    val matchScore: Double? = null
)

/**
 * XR Blocks compatible named camera/user spawn location (`SimulatorUserLocation`).
 */
@Serializable
data class SimUserLocation(
    val position: List<Double>,
    val rotation: List<Double>? = null
)

/**
 * XR Blocks compatible lighting configuration (`SimulatorLightingConfig`).
 */
@Serializable
data class SimLightingConfig(
    val ambientIntensity: Double? = null,
    val directionalIntensity: Double? = null,
    val directionalPosition: List<Double>? = null
)

/**
 * Object in the simulated environment.
 *
 * Superset of XR Blocks `SimulatorObjectPlacement` (`name`, `scenePath`, `position`, `quaternion`, `scale`)
 * enriched with Soundscape perception fields (`id`, `category`, `latitude`, `longitude`, `confidence`, etc.).
 *
 * Local coordinate convention: `position = [east, up, north]` in metres relative to `geoAnchor`.
 */
@Serializable
data class SimObject(
    val id: String? = null,
    val name: String,
    val scenePath: String? = null,
    val category: SimCategory = SimCategory.OTHER,
    val subCategory: String? = null,
    val position: List<Double>,
    val quaternion: List<Double>? = null,
    val scale: List<Double>? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val yawDegrees: Double? = null,
    val widthMeters: Double? = null,
    val confidence: Double = 1.0,
    val sources: List<String> = emptyList(),
    val text: String? = null,
    val linkedVenue: LinkedVenue? = null,
    val evidence: List<Evidence> = emptyList()
)

/**
 * Dedicated sign record with OCR text, confidence, and world/local coordinates.
 */
@Serializable
data class Sign(
    val id: String,
    val text: String,
    val confidence: Double = 1.0,
    val position: List<Double>,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val stationId: String? = null,
    val bearingDegrees: Double? = null,
    val linkedDoorId: String? = null
)

/**
 * Panorama or indoor capture station used to build and navigate the environment.
 */
@Serializable
data class Station(
    val id: String,
    val sequenceId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val compassAngle: Double = 0.0,
    val capturedAtEpochMs: Long = 0L,
    val isPano: Boolean = true,
    val position: List<Double> = listOf(0.0, 0.0, 0.0)
)

/**
 * Walkable surface definition (2D `[east, north]` floor polygon vertices in metres).
 */
@Serializable
data class Walkable(
    val floorPolygon: List<List<Double>> = emptyList(),
    val buildingFootprints: List<BuildingFootprint> = emptyList()
)

/**
 * Building footprint polygon in both WGS84 and local ENU `[east, north]` coordinates.
 */
@Serializable
data class BuildingFootprint(
    val osmId: String,
    val name: String? = null,
    val verticesEnu: List<List<Double>> = emptyList(),
    val verticesLatLon: List<List<Double>> = emptyList()
)

/**
 * Compact room recognition signature combining normalized sign/placard texts, structural summary,
 * and 3-5 representative keyframe embedding vectors.
 */
@Serializable
data class Fingerprint(
    val normalizedTexts: List<String> = emptyList(),
    val distinctiveTokens: List<String> = emptyList(),
    val embeddings: List<List<Double>> = emptyList(),
    val objectBearings: List<ObjectBearingSignature> = emptyList()
)

/**
 * Bearing and category of a static structural landmark (door, sign, counter, steps) used to re-anchor yaw
 * when a user revisits a recognized room.
 */
@Serializable
data class ObjectBearingSignature(
    val objectId: String,
    val category: SimCategory,
    val bearingDegrees: Double,
    val distanceMeters: Double,
    val text: String? = null
)

/**
 * Top-level simulator environment manifest.
 *
 * Strictly a superset of XR Blocks `SimulatorEnvironmentManifest` so existing XR Blocks JSON files
 * load cleanly while supporting outdoor street preview and indoor room preview.
 */
@Serializable
data class SimEnvironment(
    val schemaVersion: String = "1.0",
    val name: String,
    val kind: EnvironmentKind? = null,
    val scenePath: String? = null,
    val videoPath: String? = null,
    val scenePlanesPath: String? = null,
    val navMeshPath: String? = null,
    val position: List<Double>? = null,
    val quaternion: List<Double>? = null,
    val scale: List<Double>? = null,
    val locations: Map<String, SimUserLocation>? = null,
    val objects: List<SimObject>? = null,
    val lighting: SimLightingConfig? = null,
    val geoAnchor: GeoAnchor? = null,
    val provenance: Provenance? = null,
    val attribution: String? = null,
    val signs: List<Sign>? = null,
    val stations: List<Station>? = null,
    val walkable: Walkable? = null,
    val fingerprint: Fingerprint? = null
) {
    companion object {
        const val DEFAULT_ATTRIBUTION =
            "Imagery (c) Mapillary contributors (CC-BY-SA 4.0); Map data (c) OpenStreetMap contributors (ODbL)."

        fun fromJson(jsonString: String): SimEnvironment =
            SimJson.decodeFromString(serializer(), jsonString)
    }

    fun toJson(): String = SimJson.encodeToString(serializer(), this)
}
