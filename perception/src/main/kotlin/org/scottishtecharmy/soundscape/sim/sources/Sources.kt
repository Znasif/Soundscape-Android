package org.scottishtecharmy.soundscape.sim.sources

import kotlinx.serialization.Serializable
import org.scottishtecharmy.soundscape.sim.geo.BoundingBox
import org.scottishtecharmy.soundscape.sim.geo.LatLon
import org.scottishtecharmy.soundscape.sim.geo.PerspectiveCamera
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.VerifierVerdict

/**
 * Exception thrown when an API call or planned run would exceed the configured [Budget] cap.
 */
class BudgetExceededException(val service: String, val cap: Int) :
    IllegalStateException("Budget exceeded for $service (cap = $cap)")

/**
 * Single live API spend record in `budget-ledger.json`.
 */
@Serializable
data class BudgetLedgerEntry(
    val timestampEpochMs: Long,
    val step: String,
    val command: String,
    val api: String,
    val liveCalls: Int = 0,
    val downloadedBytes: Long = 0L
)

/**
 * Per-step aggregated live and replayed API totals in `budget-ledger.json`.
 */
@Serializable
data class StepBudgetTotals(
    val step: String,
    val mapillaryLiveCalls: Int = 0,
    val samLiveCalls: Int = 0,
    val geminiLiveCalls: Int = 0,
    val downloadedBytes: Long = 0L,
    val mapillaryReplayedCalls: Int = 0,
    val samReplayedCalls: Int = 0,
    val geminiReplayedCalls: Int = 0
)

/**
 * Persistent budget ledger structure serialized to `perception/docs/agent/metrics/budget-ledger.json`.
 */
@Serializable
data class BudgetLedgerData(
    val schemaVersion: String = "1.0",
    val updatedAtEpochMs: Long = 0L,
    val stepTotals: Map<String, StepBudgetTotals> = emptyMap(),
    val entries: List<BudgetLedgerEntry> = emptyList()
)

/**
 * Pure-Kotlin storage abstraction for loading and persisting [BudgetLedgerData].
 */
interface BudgetLedgerStore {
    fun load(): BudgetLedgerData?
    fun save(data: BudgetLedgerData)
}

/**
 * In-memory implementation of [BudgetLedgerStore] for portable tests.
 */
class InMemoryBudgetLedgerStore(
    private var current: BudgetLedgerData? = null
) : BudgetLedgerStore {
    override fun load(): BudgetLedgerData? = current
    override fun save(data: BudgetLedgerData) {
        current = data
    }
}

/**
 * Enforces hard per-step API call and download caps across Mapillary Graph API, Meta `sam-3.1`,
 * Gemini Flash, and dataset downloads, backed by a persistent [BudgetLedgerStore].
 *
 * - Loads the ledger at construction time so caps are enforced across process restarts.
 * - Writes the ledger immediately after every live call.
 * - Counts replayed calls (served from fixtures) separately and never charges them against caps.
 */
class Budget(
    val maxMapillaryCalls: Int = 2000,
    val maxSamCalls: Int = 80,
    val maxGeminiCalls: Int = 200,
    val maxDownloadBytes: Long = 15L * 1024L * 1024L * 1024L,
    val step: String = "step-5",
    val command: String = "cli",
    private val ledgerStore: BudgetLedgerStore? = null,
    private val clockEpochMs: () -> Long = { 1730000000000L }
) {
    private var ledgerData: BudgetLedgerData = ledgerStore?.load() ?: BudgetLedgerData()

    private val baseMapillaryLive: Int = ledgerData.stepTotals[step]?.mapillaryLiveCalls ?: 0
    private val baseSamLive: Int = ledgerData.stepTotals[step]?.samLiveCalls ?: 0
    private val baseGeminiLive: Int = ledgerData.stepTotals[step]?.geminiLiveCalls ?: 0
    private val baseDownloadedBytes: Long = ledgerData.stepTotals[step]?.downloadedBytes ?: 0L

    var runMapillaryCalls: Int = 0
        private set
    var runSamCalls: Int = 0
        private set
    var runGeminiCalls: Int = 0
        private set
    var runDownloadedBytes: Long = 0L
        private set

    var replayedMapillaryCalls: Int = 0
        private set
    var replayedSamCalls: Int = 0
        private set
    var replayedGeminiCalls: Int = 0
        private set

    val mapillaryCalls: Int get() = baseMapillaryLive + runMapillaryCalls
    val samCalls: Int get() = baseSamLive + runSamCalls
    val geminiCalls: Int get() = baseGeminiLive + runGeminiCalls
    val downloadedBytes: Long get() = baseDownloadedBytes + runDownloadedBytes

    val remainingMapillaryAllowance: Int get() = (maxMapillaryCalls - mapillaryCalls).coerceAtLeast(0)
    val remainingSamAllowance: Int get() = (maxSamCalls - samCalls).coerceAtLeast(0)
    val remainingGeminiAllowance: Int get() = (maxGeminiCalls - geminiCalls).coerceAtLeast(0)
    val remainingDownloadBytesAllowance: Long get() = (maxDownloadBytes - downloadedBytes).coerceAtLeast(0L)

    /**
     * Verifies before starting a run that its planned live calls will not push the step total past any cap.
     */
    fun checkPlannedCalls(
        plannedMapillary: Int = 0,
        plannedSam: Int = 0,
        plannedGemini: Int = 0,
        plannedDownloadBytes: Long = 0L
    ) {
        if (mapillaryCalls + plannedMapillary > maxMapillaryCalls) {
            throw BudgetExceededException("Mapillary Graph API", maxMapillaryCalls)
        }
        if (samCalls + plannedSam > maxSamCalls) {
            throw BudgetExceededException("Meta sam-3.1", maxSamCalls)
        }
        if (geminiCalls + plannedGemini > maxGeminiCalls) {
            throw BudgetExceededException("Gemini Flash", maxGeminiCalls)
        }
        if (downloadedBytes + plannedDownloadBytes > maxDownloadBytes) {
            throw BudgetExceededException("Download Bytes (MB)", (maxDownloadBytes / (1024L * 1024L)).toInt())
        }
    }

    fun consumeMapillary(count: Int = 1, isReplay: Boolean = false) {
        if (isReplay) {
            replayedMapillaryCalls += count
            persistLedger(api = null, liveCalls = 0, bytes = 0L)
            return
        }
        if (mapillaryCalls + count > maxMapillaryCalls) {
            throw BudgetExceededException("Mapillary Graph API", maxMapillaryCalls)
        }
        runMapillaryCalls += count
        persistLedger(api = "mapillary", liveCalls = count, bytes = 0L)
    }

    fun consumeSam(count: Int = 1, isReplay: Boolean = false) {
        if (isReplay) {
            replayedSamCalls += count
            persistLedger(api = null, liveCalls = 0, bytes = 0L)
            return
        }
        if (samCalls + count > maxSamCalls) {
            throw BudgetExceededException("Meta sam-3.1", maxSamCalls)
        }
        runSamCalls += count
        persistLedger(api = "sam-3.1", liveCalls = count, bytes = 0L)
    }

    fun consumeGemini(count: Int = 1, isReplay: Boolean = false) {
        if (isReplay) {
            replayedGeminiCalls += count
            persistLedger(api = null, liveCalls = 0, bytes = 0L)
            return
        }
        if (geminiCalls + count > maxGeminiCalls) {
            throw BudgetExceededException("Gemini Flash", maxGeminiCalls)
        }
        runGeminiCalls += count
        persistLedger(api = "gemini-flash", liveCalls = count, bytes = 0L)
    }

    fun consumeDownloadBytes(bytes: Long, isReplay: Boolean = false) {
        if (isReplay || bytes <= 0L) return
        if (downloadedBytes + bytes > maxDownloadBytes) {
            throw BudgetExceededException("Download Bytes (MB)", (maxDownloadBytes / (1024L * 1024L)).toInt())
        }
        runDownloadedBytes += bytes
        persistLedger(api = "download-bytes", liveCalls = 0, bytes = bytes)
    }

    fun canConsumeSam(count: Int = 1): Boolean = samCalls + count <= maxSamCalls
    fun canConsumeGemini(count: Int = 1): Boolean = geminiCalls + count <= maxGeminiCalls
    fun canConsumeMapillary(count: Int = 1): Boolean = mapillaryCalls + count <= maxMapillaryCalls

    private fun persistLedger(api: String?, liveCalls: Int, bytes: Long) {
        val store = ledgerStore ?: return
        val latest = store.load() ?: ledgerData
        val prevStep = latest.stepTotals[step] ?: StepBudgetTotals(step = step)
        val now = clockEpochMs()

        val updatedStepTotals = prevStep.copy(
            mapillaryLiveCalls = baseMapillaryLive + runMapillaryCalls,
            samLiveCalls = baseSamLive + runSamCalls,
            geminiLiveCalls = baseGeminiLive + runGeminiCalls,
            downloadedBytes = baseDownloadedBytes + runDownloadedBytes,
            mapillaryReplayedCalls = prevStep.mapillaryReplayedCalls + (if (api == null && replayedMapillaryCalls > 0) 1 else 0),
            samReplayedCalls = prevStep.samReplayedCalls + (if (api == null && replayedSamCalls > 0) 1 else 0),
            geminiReplayedCalls = prevStep.geminiReplayedCalls + (if (api == null && replayedGeminiCalls > 0) 1 else 0)
        )

        val newEntries = if (api != null) {
            latest.entries + BudgetLedgerEntry(
                timestampEpochMs = now,
                step = step,
                command = command,
                api = api,
                liveCalls = liveCalls,
                downloadedBytes = bytes
            )
        } else {
            latest.entries
        }

        ledgerData = latest.copy(
            updatedAtEpochMs = now,
            stepTotals = latest.stepTotals + (step to updatedStepTotals),
            entries = newEntries
        )
        store.save(ledgerData)
    }

    fun summary(): BudgetSummary = BudgetSummary(
        mapillaryCalls = mapillaryCalls,
        maxMapillaryCalls = maxMapillaryCalls,
        samCalls = samCalls,
        maxSamCalls = maxSamCalls,
        geminiCalls = geminiCalls,
        maxGeminiCalls = maxGeminiCalls,
        replayedMapillaryCalls = replayedMapillaryCalls,
        replayedSamCalls = replayedSamCalls,
        replayedGeminiCalls = replayedGeminiCalls,
        runMapillaryCalls = runMapillaryCalls,
        runSamCalls = runSamCalls,
        runGeminiCalls = runGeminiCalls,
        downloadedBytes = downloadedBytes
    )
}

@Serializable
data class BudgetSummary(
    val mapillaryCalls: Int,
    val maxMapillaryCalls: Int,
    val samCalls: Int,
    val maxSamCalls: Int,
    val geminiCalls: Int,
    val maxGeminiCalls: Int,
    val replayedMapillaryCalls: Int = 0,
    val replayedSamCalls: Int = 0,
    val replayedGeminiCalls: Int = 0,
    val runMapillaryCalls: Int = 0,
    val runSamCalls: Int = 0,
    val runGeminiCalls: Int = 0,
    val downloadedBytes: Long = 0L
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
 */
@Serializable
data class SegmentedInstance(
    val prompt: String,
    val category: SimCategory,
    val box: List<Int>,
    val imageWidth: Int,
    val imageHeight: Int,
    val confidence: Double,
    val normalizedBox: List<Double> = if (box.size == 4 && imageWidth > 0 && imageHeight > 0) {
        listOf(
            box[0].toDouble() / imageWidth,
            box[1].toDouble() / imageHeight,
            box[2].toDouble() / imageWidth,
            box[3].toDouble() / imageHeight
        )
    } else emptyList(),
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
        category: SimCategory,
        contextKey: String? = null
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
        promptLabel: String,
        contextKey: String? = null
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
    suspend fun readText(
        cropJpegBytes: ByteArray,
        contextKey: String? = null
    ): OcrResult?
}

/**
 * Multimodal image/text embedding client interface for Step 4 room recognition.
 */
interface Embedder {
    suspend fun embedImage(jpegBytes: ByteArray, contextKey: String? = null): List<Double>
}

/**
 * Precheck interface that inspects 4-6 keyframes spread around a room and returns which
 * indoor categories from `{door, desk, reception desk, counter, chair, table, sofa, sign, stairs, lift, window}`
 * are visible so `SegmentationClient` is called only for present categories (plus `floor`).
 */
interface CategoryPrecheck {
    suspend fun detectPresentCategories(keyframeJpegs: List<ByteArray>, contextKey: String? = null): Set<SimCategory>
}
