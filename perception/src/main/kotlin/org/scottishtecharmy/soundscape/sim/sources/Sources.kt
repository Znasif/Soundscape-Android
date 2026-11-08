package org.scottishtecharmy.soundscape.sim.sources

import kotlinx.serialization.Serializable
import org.scottishtecharmy.soundscape.sim.geo.BoundingBox
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.geo.PerspectiveCamera
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.VerifierVerdict

/**
 * Exception thrown when an API call would exceed the configured [Budget] cap.
 */
class BudgetExceededException(val service: String, val cap: Int) :
    IllegalStateException("Budget exceeded for $service (cap = $cap)")

/**
 * Enforces hard per-step API call caps across Mapillary Graph API, Meta `sam-3.1`, and Gemini Flash.
 * Pure Kotlin; safe for concurrent single-pipeline execution.
 */
class Budget(
    val maxMapillaryCalls: Int = 2000,
    val maxSamCalls: Int = 80,
    val maxGeminiCalls: Int = 200
) {
    var mapillaryCalls: Int = 0
        private set
    var samCalls: Int = 0
        private set
    var geminiCalls: Int = 0
        private set

    fun consumeMapillary(count: Int = 1) {
        if (mapillaryCalls + count > maxMapillaryCalls) {
            throw BudgetExceededException("Mapillary Graph API", maxMapillaryCalls)
        }
        mapillaryCalls += count
    }

    fun consumeSam(count: Int = 1) {
        if (samCalls + count > maxSamCalls) {
            throw BudgetExceededException("Meta sam-3.1", maxSamCalls)
        }
        samCalls += count
    }

    fun consumeGemini(count: Int = 1) {
        if (geminiCalls + count > maxGeminiCalls) {
            throw BudgetExceededException("Gemini Flash", maxGeminiCalls)
        }
        geminiCalls += count
    }

    fun canConsumeSam(count: Int = 1): Boolean = samCalls + count <= maxSamCalls
    fun canConsumeGemini(count: Int = 1): Boolean = geminiCalls + count <= maxGeminiCalls

    fun summary(): BudgetSummary = BudgetSummary(
        mapillaryCalls = mapillaryCalls,
        maxMapillaryCalls = maxMapillaryCalls,
        samCalls = samCalls,
        maxSamCalls = maxSamCalls,
        geminiCalls = geminiCalls,
        maxGeminiCalls = maxGeminiCalls
    )
}

@Serializable
data class BudgetSummary(
    val mapillaryCalls: Int,
    val maxMapillaryCalls: Int,
    val samCalls: Int,
    val maxSamCalls: Int,
    val geminiCalls: Int,
    val maxGeminiCalls: Int
)

/**
 * Mapillary image metadata record returned by Graph API v4.
 */
@Serializable
data class MapillaryImage(
    val id: String,
    val isPano: Boolean = false,
    val compassAngle: Double = 0.0,
    val computedCompassAngle: Double? = null,
    val capturedAtEpochMs: Long = 0L,
    val sequenceId: String? = null,
    val location: LatLon,
    val computedLocation: LatLon? = null,
    val width: Int? = null,
    val height: Int? = null,
    val thumb1024Url: String? = null,
    val thumb2048Url: String? = null
) {
    val effectiveLocation: LatLon get() = computedLocation ?: location
    val effectiveCompassAngle: Double get() = computedCompassAngle ?: compassAngle
}

/**
 * Mapillary point map feature returned by `/map_features`.
 */
@Serializable
data class MapillaryMapFeature(
    val id: String,
    val objectValue: String,
    val objectType: String? = null,
    val alignedDirection: Double? = null,
    val firstSeenAtEpochMs: Long? = null,
    val lastSeenAtEpochMs: Long? = null,
    val location: LatLon,
    val imageIds: List<String> = emptyList()
)

/**
 * Mapillary per-image detection returned by `/:image_id/detections`.
 */
@Serializable
data class MapillaryDetection(
    val id: String,
    val imageId: String,
    val value: String,
    val createdAtEpochMs: Long? = null,
    val geometryBase64: String? = null
)

/**
 * Source interface for Mapillary Graph API v4.
 */
interface MapillarySource {
    suspend fun getImagesInBbox(bbox: BoundingBox, limit: Int = 2000): List<MapillaryImage>
    suspend fun getImageById(imageId: String): MapillaryImage?
    suspend fun getMapFeaturesInBbox(bbox: BoundingBox, limit: Int = 2000): List<MapillaryMapFeature>
    suspend fun getImageDetections(imageId: String): List<MapillaryDetection>
}

/**
 * Building polygon returned by Overpass / OSM.
 */
@Serializable
data class OsmBuilding(
    val osmId: String,
    val name: String? = null,
    val polygon: List<LatLon>,
    val tags: Map<String, String> = emptyMap()
)

/**
 * Entrance node (`entrance=*`) returned by Overpass / OSM.
 */
@Serializable
data class OsmEntrance(
    val osmId: String,
    val entranceType: String,
    val name: String? = null,
    val location: LatLon,
    val tags: Map<String, String> = emptyMap()
)

/**
 * Named POI (shop, cafe, restaurant, office, amenity) returned by Overpass / OSM.
 */
@Serializable
data class OsmPoi(
    val osmId: String,
    val name: String,
    val category: String,
    val location: LatLon,
    val polygon: List<LatLon> = emptyList(),
    val tags: Map<String, String> = emptyMap()
)

/**
 * Aggregated Overpass query result for a street area.
 */
@Serializable
data class OverpassAreaData(
    val buildings: List<OsmBuilding> = emptyList(),
    val entrances: List<OsmEntrance> = emptyList(),
    val pois: List<OsmPoi> = emptyList()
)

/**
 * Source interface for OpenStreetMap Overpass API.
 */
interface OverpassSource {
    suspend fun queryArea(center: LatLon, radiusMeters: Double): OverpassAreaData
}

/**
 * Overture Maps place record.
 */
@Serializable
data class OverturePlace(
    val gersId: String,
    val name: String,
    val category: String,
    val location: LatLon,
    val confidence: Double = 1.0
)

/**
 * Interface for Overture Maps Places & Buildings queries.
 *
 * Per `DECISIONS.md` (D-02): Overture distributes multi-GB cloud GeoParquet files on S3/Azure indexed by STAC.
 * Fetching a small bounding box over HTTP Range requests without DuckDB or heavy Apache Parquet/Arrow/Hadoop
 * dependencies is impractical for a lightweight mobile library, and standard Overture `places`/`buildings`
 * schemas (`v2.0.0`) do not expose separate entrance point coordinates.
 */
interface OvertureClient {
    suspend fun getPlacesInBbox(bbox: BoundingBox): List<OverturePlace>
}

/**
 * Stub implementation of [OvertureClient] documenting why live GeoParquet range fetching is deferred.
 */
class DeferredOvertureClient : OvertureClient {
    override suspend fun getPlacesInBbox(bbox: BoundingBox): List<OverturePlace> {
        // TODO(D-02): Implement lightweight Overture PMTiles / STAC GeoParquet reader once a hosted
        // bbox REST/tile endpoint or KMP Parquet footer reader is available without DuckDB.
        return emptyList()
    }
}

/**
 * Raw RGB/JPEG perspective image crop in memory (`width x height`).
 */
data class PerspectiveCropImage(
    val stationId: String,
    val camera: PerspectiveCamera,
    val jpegBytes: ByteArray,
    val rgbPixels: IntArray? = null
) {
    /**
     * Extracts a sub-crop around bounding box `[x1, y1, x2, y2]` with optional padding.
     */
    fun subBoxWidth(box: List<Int>): Int = (box[2] - box[0] + 1).coerceAtLeast(1)
    fun subBoxHeight(box: List<Int>): Int = (box[3] - box[1] + 1).coerceAtLeast(1)
}

/**
 * Source interface for fetching a panorama and projecting a rectilinear [PerspectiveCamera] view.
 * Kept in memory or temporary storage only; never committed to git.
 */
interface PanoramaImageSource {
    suspend fun extractPerspectiveCrop(
        stationId: String,
        compassAngleDegrees: Double,
        camera: PerspectiveCamera
    ): PerspectiveCropImage?

    fun cropSubImage(
        crop: PerspectiveCropImage,
        box: List<Int>,
        paddingFraction: Double = 0.15
    ): ByteArray
}

/**
 * Single segmented instance returned by `SegmentationClient` (e.g. Meta `sam-3.1`).
 *
 * @property prompt Text prompt used (`"door"`, `"shop sign"`, `"steps"`, `"counter"`, `"floor"`).
 * @property category Mapped [SimCategory].
 * @property box Pixel bounding box `[x1, y1, x2, y2]` in `imageWidth x imageHeight`.
 * @property imageWidth Width of the input crop in pixels.
 * @property imageHeight Height of the input crop in pixels.
 * @property confidence Confidence score in `[0.0, 1.0]`.
 * @property maskWidth Width of the cropped mask (`x2 - x1 + 1`).
 * @property maskHeight Height of the cropped mask (`y2 - y1 + 1`).
 * @property maskData Optional encoded mask string (`!<base85>` or `~<base85>`).
 */
@Serializable
data class SegmentedInstance(
    val prompt: String,
    val category: SimCategory,
    val box: List<Int>,
    val imageWidth: Int,
    val imageHeight: Int,
    val confidence: Double,
    val maskWidth: Int = 0,
    val maskHeight: Int = 0,
    val maskData: String? = null
) {
    val centerX: Double get() = 0.5 * (box[0] + box[2])
    val centerY: Double get() = 0.5 * (box[1] + box[3])
    val bottomCenterX: Double get() = 0.5 * (box[0] + box[2])
    val bottomY: Double get() = box[3].toDouble()
    val topY: Double get() = box[1].toDouble()
}

/**
 * Segmentation client interface (implemented by `MetaSam31Client` and test fakes).
 */
interface SegmentationClient {
    suspend fun segment(
        imageJpegBytes: ByteArray,
        prompt: String,
        category: SimCategory
    ): List<SegmentedInstance>
}

/**
 * Crop verifier interface (implemented by `GeminiFlashClient` and test fakes).
 * Returns tri-state `YES`, `NO`, or `INSUFFICIENT`.
 */
interface Verifier {
    suspend fun verifyCrop(
        cropJpegBytes: ByteArray,
        category: SimCategory,
        promptLabel: String
    ): VerifierVerdict
}

/**
 * OCR result returned by [TextReader].
 */
@Serializable
data class OcrResult(
    val text: String,
    val confidence: Double
)

/**
 * Sign text reader interface (implemented by `GeminiFlashClient` and test fakes).
 */
interface TextReader {
    suspend fun readText(cropJpegBytes: ByteArray): OcrResult?
}

/**
 * Multimodal image/text embedding client interface for Step 4 room recognition.
 */
interface Embedder {
    suspend fun embedImage(jpegBytes: ByteArray): List<Double>
}
