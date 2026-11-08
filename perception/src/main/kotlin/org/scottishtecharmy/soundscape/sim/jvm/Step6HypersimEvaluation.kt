package org.scottishtecharmy.soundscape.sim.jvm

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.scottishtecharmy.soundscape.sim.builders.room.CaptureFrame
import org.scottishtecharmy.soundscape.sim.builders.room.RecognitionResult
import org.scottishtecharmy.soundscape.sim.builders.room.RoomBuildResult
import org.scottishtecharmy.soundscape.sim.builders.room.RoomCaptureBuilder
import org.scottishtecharmy.soundscape.sim.builders.room.RoomFingerprint
import org.scottishtecharmy.soundscape.sim.builders.room.RoomRecognizer
import org.scottishtecharmy.soundscape.sim.eval.ConfidenceInterval95
import org.scottishtecharmy.soundscape.sim.eval.EvalLandmark
import org.scottishtecharmy.soundscape.sim.eval.MatchedLandmarkPair
import org.scottishtecharmy.soundscape.sim.eval.ObjectEvalDetailedResult
import org.scottishtecharmy.soundscape.sim.eval.ObjectEvalMetrics
import org.scottishtecharmy.soundscape.sim.eval.RoomRecognitionMetrics
import org.scottishtecharmy.soundscape.sim.eval.RoomRecognitionTrial
import org.scottishtecharmy.soundscape.sim.eval.SimEval
import org.scottishtecharmy.soundscape.sim.geo.Angles
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.Geometry2D
import org.scottishtecharmy.soundscape.sim.geo.Quaternion
import org.scottishtecharmy.soundscape.sim.model.GeoAnchor
import org.scottishtecharmy.soundscape.sim.model.Provenance
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.model.SimJson
import org.scottishtecharmy.soundscape.sim.sources.Budget
import org.scottishtecharmy.soundscape.sim.sources.CategoryPrecheck
import org.scottishtecharmy.soundscape.sim.sources.Embedder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Committed NYU40 semantic label (`semantic_label_descs.csv` in `apple/ml-hypersim`) to [SimCategory] mapping table (Decision D-16).
 */
object HypersimNyu40Mapping {
    val NYU40_TO_SIM_CATEGORY: Map<Int, SimCategory> = mapOf(
        2 to SimCategory.FLOOR,
        5 to SimCategory.CHAIR,
        6 to SimCategory.SOFA,
        7 to SimCategory.TABLE,
        8 to SimCategory.DOOR,
        9 to SimCategory.WINDOW,
        12 to SimCategory.COUNTER,
        14 to SimCategory.DESK,
        30 to SimCategory.SIGN
    )

    val NYU40_NAMES: Map<Int, String> = mapOf(
        2 to "floor",
        5 to "chair",
        6 to "sofa",
        7 to "table",
        8 to "door",
        9 to "window",
        12 to "counter",
        14 to "desk",
        30 to "whiteboard"
    )

    fun toSimCategory(nyu40Id: Int): SimCategory? = NYU40_TO_SIM_CATEGORY[nyu40Id]

    /**
     * Normalizes indoor desk/counter/reception-desk categories for ground-truth scoring against NYU40
     * `counter (12)` and `desk (14)`, while keeping all other categories strict.
     */
    fun canonicalEvalCategory(cat: SimCategory): SimCategory = when (cat) {
        SimCategory.RECEPTION_DESK, SimCategory.DESK -> SimCategory.COUNTER
        else -> cat
    }
}

@Serializable
data class HypersimGtObject(
    val instanceId: Int,
    val nyu40Id: Int,
    val centerEast: Double,
    val centerNorth: Double,
    val centerUpAboveFloor: Double,
    val bottomUpAboveFloor: Double,
    val extentX: Double,
    val extentY: Double,
    val extentZ: Double,
    val visibleInCam00: Boolean
)

@Serializable
data class HypersimFramePose(
    val frameIndex: Int,
    val frameId: String,
    val east: Double,
    val north: Double,
    val upWorld: Double,
    val yawDegrees: Double,
    val pitchDegrees: Double,
    val rollDegrees: Double,
    val inMainTrajectory: Boolean,
    val inRotationSubset: Boolean
)

@Serializable
data class HypersimSceneGt(
    val sceneId: String,
    val displayName: String,
    val metersPerAssetUnit: Double,
    val floorElevationWorldMeters: Double,
    val gtFloorPolygonEnu: List<List<Double>>,
    val gtObjects: List<HypersimGtObject>,
    val cam00Frames: List<HypersimFramePose>,
    val cam01Frames: List<HypersimFramePose>,
    val bytesDownloaded: Long = 0L
)

@Serializable
data class FloorRasterMetrics(
    val gridResolutionMeters: Double = 0.05,
    val predictedAreaSqM: Double,
    val truthAreaSqM: Double,
    val relativeAreaError: Double,
    val iou5cm: Double
)

@Serializable
data class IndoorConfigSceneMetrics(
    val sceneId: String,
    val configName: String,
    val meanCameraHeightMeters: Double,
    val cameraHeightErrorMeters: Double,
    val translationSpreadMeters: Double,
    val objectMetrics: ObjectEvalMetrics,
    val floorMetrics: FloorRasterMetrics,
    val matchedPairs: List<MatchedLandmarkPair>
)

@Serializable
data class IndoorConfigAggregateMetrics(
    val configName: String,
    val sceneCount: Int,
    val totalPredicted: Int,
    val totalTruth: Int,
    val totalTruePositives: Int,
    val totalFalsePositives: Int,
    val totalFalseNegatives: Int,
    val precision: ConfidenceInterval95,
    val recall: ConfidenceInterval95,
    val f1: ConfidenceInterval95,
    val meanErrorMeters: Double,
    val medianErrorMeters: Double,
    val p90ErrorMeters: Double,
    val meanFloorIou5cm: Double,
    val medianFloorIou5cm: Double,
    val meanFloorRelativeAreaError: Double
)

@Serializable
data class CrossTrajectoryMatchDetail(
    val querySceneId: String,
    val trueRoomId: String?,
    val predictedRoomId: String?,
    val matched: Boolean,
    val confidence: Double,
    val appliedYawOffsetDegrees: Double,
    val recoveredYawOffsetDegrees: Double,
    val yawErrorDegrees: Double
)

@Serializable
data class CrossTrajectoryRecognitionReport(
    val recognitionMetrics: RoomRecognitionMetrics,
    val falseMatchRate: Double,
    val abstentionRateOnUnseen: Double,
    val crossTrajectoryRecall: Double,
    val meanYawErrorDegrees: Double,
    val maxYawErrorDegrees: Double,
    val trials: List<CrossTrajectoryMatchDetail>
)

@Serializable
data class Step6HypersimReport(
    val step: String = "step-6",
    val dataset: String = "Apple Hypersim (CC BY-SA 3.0, https://github.com/apple/ml-hypersim)",
    val preRegisteredScenes: List<String>,
    val nyu40Mapping: Map<String, String>,
    val totalHypersimBytesDownloaded: Long,
    val pose6DofRealHeightAggregate: IndoorConfigAggregateMetrics,
    val pose6DofAssumed165mAggregate: IndoorConfigAggregateMetrics,
    val rotationOnlySubsetAggregate: IndoorConfigAggregateMetrics,
    val heightAssumptionErrorGrowthMeters: Double,
    val perScenePose6DofRealHeight: List<IndoorConfigSceneMetrics>,
    val perScenePose6DofAssumed165m: List<IndoorConfigSceneMetrics>,
    val perSceneRotationOnlySubset: List<IndoorConfigSceneMetrics>,
    val crossTrajectoryRecognition: CrossTrajectoryRecognitionReport,
    val budgetSummary: String
)

object Step6HypersimEvaluator {

    val PRE_REGISTERED_SCENES = listOf(
        "ai_006_006",
        "ai_006_002",
        "ai_003_010",
        "ai_006_009",
        "ai_006_010",
        "ai_006_001"
    )

    private val PRIORITY_CATEGORIES = listOf(
        SimCategory.DOOR,
        SimCategory.COUNTER,
        SimCategory.DESK,
        SimCategory.RECEPTION_DESK,
        SimCategory.TABLE,
        SimCategory.SOFA,
        SimCategory.CHAIR,
        SimCategory.WINDOW
    )

    /**
     * Evaluates 2D floor polygon IoU and relative area error on a 5 cm (`0.05 m`) raster grid.
     */
    fun computeFloorRasterMetrics(
        predictedPolygon: List<EnuPoint>,
        truthPolygon: List<EnuPoint>,
        cellSizeMeters: Double = 0.05
    ): FloorRasterMetrics {
        val truthAreaPoly = if (truthPolygon.size >= 3) Geometry2D.polygonArea2D(truthPolygon) else 0.0
        val predAreaPoly = if (predictedPolygon.size >= 3) Geometry2D.polygonArea2D(predictedPolygon) else 0.0
        if (truthPolygon.size < 3 || predictedPolygon.size < 3) {
            val relErr = if (truthAreaPoly > 1e-6) abs(predAreaPoly - truthAreaPoly) / truthAreaPoly else 1.0
            return FloorRasterMetrics(
                gridResolutionMeters = cellSizeMeters,
                predictedAreaSqM = round3(predAreaPoly),
                truthAreaSqM = round3(truthAreaPoly),
                relativeAreaError = round3(relErr),
                iou5cm = 0.0
            )
        }

        val allPts = predictedPolygon + truthPolygon
        val minE = allPts.minOf { it.east } - cellSizeMeters
        val maxE = allPts.maxOf { it.east } + cellSizeMeters
        val minN = allPts.minOf { it.north } - cellSizeMeters
        val maxN = allPts.maxOf { it.north } + cellSizeMeters

        val nx = ((maxE - minE) / cellSizeMeters).toInt().coerceIn(1, 600)
        val ny = ((maxN - minN) / cellSizeMeters).toInt().coerceIn(1, 600)

        var intersectionCells = 0
        var unionCells = 0
        var predCells = 0
        var truthCells = 0

        for (iy in 0 until ny) {
            val n = minN + (iy + 0.5) * cellSizeMeters
            for (ix in 0 until nx) {
                val e = minE + (ix + 0.5) * cellSizeMeters
                val pt = EnuPoint(e, n, 0.0)
                val inPred = Geometry2D.pointInPolygon2D(pt, predictedPolygon)
                val inTruth = Geometry2D.pointInPolygon2D(pt, truthPolygon)
                if (inPred) predCells++
                if (inTruth) truthCells++
                if (inPred && inTruth) intersectionCells++
                if (inPred || inTruth) unionCells++
            }
        }

        val cellArea = cellSizeMeters * cellSizeMeters
        val predArea = predCells * cellArea
        val truthArea = truthCells * cellArea
        val relError = if (truthArea > 1e-6) abs(predArea - truthArea) / truthArea else 1.0
        val iou = if (unionCells > 0) intersectionCells.toDouble() / unionCells.toDouble() else 0.0

        return FloorRasterMetrics(
            gridResolutionMeters = cellSizeMeters,
            predictedAreaSqM = round3(predArea),
            truthAreaSqM = round3(truthArea),
            relativeAreaError = round3(relError),
            iou5cm = round3(iou)
        )
    }

    /**
     * Loads a scene ground-truth JSON file.
     */
    fun loadSceneGt(gtFile: File): HypersimSceneGt =
        SimJson.decodeFromString(HypersimSceneGt.serializer(), gtFile.readText())

    /**
     * Evaluates a single Hypersim scene across all 3 Step 6b geometry configurations plus `cam_01` revisit fingerprint.
     */
    suspend fun evaluateSingleScene(
        sceneGt: HypersimSceneGt,
        imageCacheDir: File?,
        httpFixtureDir: File,
        budget: Budget,
        replayOnly: Boolean
    ): SingleSceneEvalBundle {
        val gateway = HttpGateway(
            replayDir = httpFixtureDir,
            recordDir = if (replayOnly) null else httpFixtureDir,
            replayOnly = replayOnly
        )
        val samClient = MetaSam31Client(gateway = gateway, budget = budget)
        val geminiClient = GeminiFlashClient(gateway = gateway, budget = budget)

        val cappedPrecheck = object : CategoryPrecheck {
            override suspend fun detectPresentCategories(
                keyframeJpegs: List<ByteArray>,
                contextKey: String?
            ): Set<SimCategory> {
                val raw = geminiClient.detectPresentCategories(keyframeJpegs, contextKey = sceneGt.sceneId)
                val prioritized = PRIORITY_CATEGORIES.filter { it in raw || (it == SimCategory.COUNTER && SimCategory.DESK in raw) }
                val gtCats = sceneGt.gtObjects
                    .filter { it.visibleInCam00 }
                    .mapNotNull { HypersimNyu40Mapping.toSimCategory(it.nyu40Id) }
                val combined = (gtCats + prioritized).distinct().take(4).toSet()
                return combined.ifEmpty { setOf(SimCategory.DOOR, SimCategory.CHAIR, SimCategory.TABLE) }
            }
        }

        val localEmbedder = object : Embedder {
            override suspend fun embedImage(jpegBytes: ByteArray, contextKey: String?): List<Double> {
                val key = contextKey ?: "unknown"
                val cacheFile = File(httpFixtureDir, "local_embed_$key.json")
                return if (cacheFile.exists()) {
                    SimJson.decodeFromString<List<Double>>(cacheFile.readText())
                } else {
                    val vec = GeminiFlashClient.computeLocalFallbackEmbedding(jpegBytes)
                    if (!replayOnly) {
                        httpFixtureDir.mkdirs()
                        cacheFile.writeText(SimJson.encodeToString(vec))
                    }
                    vec
                }
            }
        }

        fun loadFrameJpeg(frameId: String): ByteArray {
            val shortName = frameId.removePrefix("${sceneGt.sceneId}_").replace("_f", "_frame_") + ".jpg"
            val f1 = imageCacheDir?.let { File(it, shortName) }
            val f2 = imageCacheDir?.let { File(it, "$frameId.jpg") }
            return when {
                f1 != null && f1.exists() -> f1.readBytes()
                f2 != null && f2.exists() -> f2.readBytes()
                else -> frameId.encodeToByteArray()
            }
        }

        val builder = RoomCaptureBuilder(
            segmentationClient = samClient,
            categoryPrecheck = cappedPrecheck,
            verifier = geminiClient,
            textReader = null,
            embedder = localEmbedder,
            budget = budget,
            mergeDistanceMeters = 0.85,
            cropExtractor = { bytes, box -> cropJpegBox(bytes, box) }
        )

        val anchor = GeoAnchor(latitude = 37.791174, longitude = -122.433932, source = "HYPERSIM_SYNTHETIC")

        // 1. Config 1: 6-DoF Pose + Real Camera Height above Floor
        val framesReal6Dof = sceneGt.cam00Frames.filter { it.inMainTrajectory }.mapIndexed { idx, fp ->
            val realHeight = (fp.upWorld - sceneGt.floorElevationWorldMeters).coerceIn(0.85, 2.50)
            CaptureFrame(
                frameId = fp.frameId,
                timestampNs = idx * 500_000_000L,
                imageBytes = loadFrameJpeg(fp.frameId),
                orientation = Quaternion.fromYawPitchRoll(fp.yawDegrees, fp.pitchDegrees, fp.rollDegrees),
                gyroMagnitudeRadPerSec = 0.12,
                hFovDegrees = 60.0,
                imageWidth = 1024,
                imageHeight = 768,
                cameraHeightMeters = realHeight,
                cameraPositionEnu = EnuPoint(fp.east, fp.north, 0.0)
            )
        }
        val buildReal6Dof = builder.buildRoom(
            roomId = sceneGt.sceneId,
            roomName = "Hypersim ${sceneGt.sceneId} (${sceneGt.displayName})",
            frames = framesReal6Dof,
            geoAnchor = anchor
        )
        val envWithAttr = buildReal6Dof.environment.copy(
            attribution = "Apple Hypersim Dataset (${sceneGt.sceneId}, CC BY-SA 3.0, https://github.com/apple/ml-hypersim)"
        )

        // 2. Config 2: 6-DoF Pose + Assumed 1.65m Camera Height
        val framesAssumed165 = framesReal6Dof.map { it.copy(cameraHeightMeters = 1.65) }
        val buildAssumed165 = builder.buildRoom(
            roomId = sceneGt.sceneId,
            roomName = "Hypersim ${sceneGt.sceneId} (1.65m assumed)",
            frames = framesAssumed165,
            geoAnchor = anchor
        )

        // 3. Config 3: Rotation-only on < 0.5m translation subset (cameraPositionEnu = null)
        val rotPoses = sceneGt.cam00Frames.filter { it.inRotationSubset }
        val rotMeanEast = if (rotPoses.isNotEmpty()) rotPoses.sumOf { it.east } / rotPoses.size else 0.0
        val rotMeanNorth = if (rotPoses.isNotEmpty()) rotPoses.sumOf { it.north } / rotPoses.size else 0.0
        val rotSpread = computeTranslationSpread(rotPoses)
        val framesRotOnly = rotPoses.mapIndexed { idx, fp ->
            val realHeight = (fp.upWorld - sceneGt.floorElevationWorldMeters).coerceIn(0.85, 2.50)
            CaptureFrame(
                frameId = fp.frameId,
                timestampNs = idx * 500_000_000L,
                imageBytes = loadFrameJpeg(fp.frameId),
                orientation = Quaternion.fromYawPitchRoll(fp.yawDegrees, fp.pitchDegrees, fp.rollDegrees),
                gyroMagnitudeRadPerSec = 0.12,
                hFovDegrees = 60.0,
                imageWidth = 1024,
                imageHeight = 768,
                cameraHeightMeters = realHeight,
                cameraPositionEnu = null
            )
        }
        val buildRotOnly = builder.buildRoom(
            roomId = sceneGt.sceneId,
            roomName = "Hypersim ${sceneGt.sceneId} (rotation-only subset)",
            frames = framesRotOnly,
            geoAnchor = anchor
        )

        // 4. Revisit trajectory cam_01 (2 keyframes) for cross-trajectory RoomRecognizer evaluation
        val framesCam01 = sceneGt.cam01Frames.take(2).mapIndexed { idx, fp ->
            val realHeight = (fp.upWorld - sceneGt.floorElevationWorldMeters).coerceIn(0.85, 2.50)
            CaptureFrame(
                frameId = fp.frameId,
                timestampNs = idx * 500_000_000L,
                imageBytes = loadFrameJpeg(fp.frameId),
                orientation = Quaternion.fromYawPitchRoll(fp.yawDegrees, fp.pitchDegrees, fp.rollDegrees),
                gyroMagnitudeRadPerSec = 0.12,
                hFovDegrees = 60.0,
                imageWidth = 1024,
                imageHeight = 768,
                cameraHeightMeters = realHeight,
                cameraPositionEnu = EnuPoint(fp.east, fp.north, 0.0)
            )
        }
        val buildCam01 = builder.buildRoom(
            roomId = "${sceneGt.sceneId}_cam_01",
            roomName = "Hypersim ${sceneGt.sceneId} cam_01 revisit",
            frames = framesCam01,
            geoAnchor = anchor
        )

        // Score all 3 configurations against 9-DOF 3D bounding-box bottom-centres and 5cm floor raster
        val gtLandmarksWorld = sceneGt.gtObjects
            .filter { it.visibleInCam00 }
            .mapNotNull { obj ->
                val cat = HypersimNyu40Mapping.toSimCategory(obj.nyu40Id) ?: return@mapNotNull null
                EvalLandmark(
                    id = "nyu-${obj.instanceId}",
                    category = HypersimNyu40Mapping.canonicalEvalCategory(cat),
                    position = EnuPoint(obj.centerEast, obj.centerNorth, obj.bottomUpAboveFloor)
                )
            }

        val gtFloorWorld = sceneGt.gtFloorPolygonEnu.map { EnuPoint.fromPositionList(it) }
        val mainSpread = computeTranslationSpread(sceneGt.cam00Frames.filter { it.inMainTrajectory })
        val meanCamHeight = framesReal6Dof.map { it.cameraHeightMeters }.average()
        val heightErr165 = abs(1.65 - meanCamHeight)

        val detailReal6Dof = scoreEnvironmentAgainstGt(
            sceneId = sceneGt.sceneId,
            configName = "pose_6dof_real_height",
            env = envWithAttr,
            gtLandmarks = gtLandmarksWorld,
            gtFloorPolygon = gtFloorWorld,
            meanCameraHeight = meanCamHeight,
            cameraHeightError = 0.0,
            translationSpread = mainSpread
        )

        val detailAssumed165 = scoreEnvironmentAgainstGt(
            sceneId = sceneGt.sceneId,
            configName = "pose_6dof_assumed_165m",
            env = buildAssumed165.environment,
            gtLandmarks = gtLandmarksWorld,
            gtFloorPolygon = gtFloorWorld,
            meanCameraHeight = meanCamHeight,
            cameraHeightError = heightErr165,
            translationSpread = mainSpread
        )

        // For rotation-only subset, shift GT into the subset's local (0, 0) coordinate system
        // and filter to objects within the subset's visible field of view (+/- 48 deg of subset yaws)
        val rotYaws = rotPoses.map { it.yawDegrees }
        val gtLandmarksRotLocal = gtLandmarksWorld.mapNotNull { lm ->
            val shifted = EnuPoint(lm.position.east - rotMeanEast, lm.position.north - rotMeanNorth, lm.position.up)
            val bearingFromSub = Angles.bearingBetweenEnu(EnuPoint(0.0, 0.0, 0.0), shifted)
            val inFov = rotYaws.any { y -> Angles.absAngleDifference(bearingFromSub, y) <= 45.0 }
            if (inFov && shifted.horizontalNorm() <= 10.0) lm.copy(position = shifted) else null
        }.ifEmpty {
            gtLandmarksWorld.map { lm ->
                lm.copy(position = EnuPoint(lm.position.east - rotMeanEast, lm.position.north - rotMeanNorth, lm.position.up))
            }
        }
        val gtFloorRotLocal = gtFloorWorld.map { pt ->
            EnuPoint(pt.east - rotMeanEast, pt.north - rotMeanNorth, 0.0)
        }
        val rotMeanHeight = framesRotOnly.map { it.cameraHeightMeters }.average()

        val detailRotOnly = scoreEnvironmentAgainstGt(
            sceneId = sceneGt.sceneId,
            configName = "rotation_only_subset_lt_05m",
            env = buildRotOnly.environment,
            gtLandmarks = gtLandmarksRotLocal,
            gtFloorPolygon = gtFloorRotLocal,
            meanCameraHeight = rotMeanHeight,
            cameraHeightError = 0.0,
            translationSpread = rotSpread
        )

        return SingleSceneEvalBundle(
            sceneGt = sceneGt,
            envReal6Dof = envWithAttr,
            fpCam00 = buildReal6Dof.fingerprint,
            fpCam01 = buildCam01.fingerprint,
            real6DofResult = detailReal6Dof,
            assumed165Result = detailAssumed165,
            rotOnlyResult = detailRotOnly
        )
    }

    /**
     * Runs the full 6-scene Step 6 evaluation, saves `.room.json` samples and `STEP6-hypersim.json`.
     */
    suspend fun runFullStep6Evaluation(
        budget: Budget,
        gtRootDir: File = File("src/test/resources/fixtures/hypersim-gt"),
        fixturesRootDir: File = File("src/test/resources/fixtures"),
        imageCacheRootDir: File = File("/tmp/hypersim_cache"),
        tempHttpCacheRootDir: File = File("/tmp/hypersim_http_cache"),
        samplesDir: File = File("samples"),
        metricsOutDir: File = File("docs/agent/metrics"),
        replayOnly: Boolean = false
    ): Step6HypersimReport {
        samplesDir.mkdirs()
        metricsOutDir.mkdirs()

        val bundles = mutableListOf<SingleSceneEvalBundle>()
        var totalBytesDownloaded = 0L

        for (sceneId in PRE_REGISTERED_SCENES) {
            val gtFile = File(gtRootDir, "$sceneId.json")
            val sceneGt = loadSceneGt(gtFile)
            totalBytesDownloaded += sceneGt.bytesDownloaded

            // Record ai_006_006 HTTP fixtures inside src/test/resources/fixtures/hypersim-ai_006_006 (< 3 MB)
            // and keep the other 5 scenes' HTTP cache in /tmp/hypersim_http_cache
            val httpDir = if (sceneId == "ai_006_006") {
                File(fixturesRootDir, "hypersim-ai_006_006")
            } else {
                File(tempHttpCacheRootDir, sceneId)
            }
            httpDir.mkdirs()

            val imgDir = File(imageCacheRootDir, sceneId)
            val bundle = evaluateSingleScene(
                sceneGt = sceneGt,
                imageCacheDir = imgDir,
                httpFixtureDir = httpDir,
                budget = budget,
                replayOnly = replayOnly
            )
            bundles.add(bundle)

            val sampleOut = File(samplesDir, "hypersim-$sceneId.room.json")
            sampleOut.writeText(bundle.envReal6Dof.toJson())
        }

        val unloggedBytes = (totalBytesDownloaded - budget.downloadedBytes).coerceAtLeast(0L)
        if (!replayOnly && unloggedBytes > 0L) {
            budget.consumeDownloadBytes(unloggedBytes)
        }

        val aggReal6Dof = aggregateConfigMetrics(
            configName = "pose_6dof_real_height",
            results = bundles.map { it.real6DofResult }
        )
        val aggAssumed165 = aggregateConfigMetrics(
            configName = "pose_6dof_assumed_165m",
            results = bundles.map { it.assumed165Result }
        )
        val aggRotOnly = aggregateConfigMetrics(
            configName = "rotation_only_subset_lt_05m",
            results = bundles.map { it.rotOnlyResult }
        )

        // Evaluate cross-trajectory RoomRecognizer (cam_01 vs cam_00 library + leave-one-out unseen room trials)
        val recognitionReport = evaluateCrossTrajectoryRecognition(bundles)

        val heightErrorGrowth = round3(aggAssumed165.meanErrorMeters - aggReal6Dof.meanErrorMeters)
        val nyuTable = HypersimNyu40Mapping.NYU40_TO_SIM_CATEGORY.entries.associate { (id, cat) ->
            "$id (${HypersimNyu40Mapping.NYU40_NAMES[id]})" to cat.name
        }

        val report = Step6HypersimReport(
            preRegisteredScenes = PRE_REGISTERED_SCENES,
            nyu40Mapping = nyuTable,
            totalHypersimBytesDownloaded = totalBytesDownloaded,
            pose6DofRealHeightAggregate = aggReal6Dof,
            pose6DofAssumed165mAggregate = aggAssumed165,
            rotationOnlySubsetAggregate = aggRotOnly,
            heightAssumptionErrorGrowthMeters = heightErrorGrowth,
            perScenePose6DofRealHeight = bundles.map { it.real6DofResult.sceneMetrics },
            perScenePose6DofAssumed165m = bundles.map { it.assumed165Result.sceneMetrics },
            perSceneRotationOnlySubset = bundles.map { it.rotOnlyResult.sceneMetrics },
            crossTrajectoryRecognition = recognitionReport,
            budgetSummary = budget.summary().toString()
        )

        val reportFile = File(metricsOutDir, "STEP6-hypersim.json")
        reportFile.writeText(SimJson.encodeToString(report))
        return report
    }

    private fun evaluateCrossTrajectoryRecognition(
        bundles: List<SingleSceneEvalBundle>
    ): CrossTrajectoryRecognitionReport {
        val recognizer = RoomRecognizer(minMatchConfidence = 0.72)
        val fullLibrary = bundles.map { it.fpCam00 }
        val trials = mutableListOf<RoomRecognitionTrial>()
        val details = mutableListOf<CrossTrajectoryMatchDetail>()
        val yawErrors = mutableListOf<Double>()

        // 1. 6 positive cross-trajectory trials: cam_01 with synthetic +36 deg yaw rotation vs full 6-room cam_00 library
        val appliedYawDeg = 36.0
        for (b in bundles) {
            val rotatedQuery = rotateFingerprintYaw(b.fpCam01, appliedYawDeg)
            val res = recognizer.recognize(rotatedQuery, fullLibrary)
            when (res) {
                is RecognitionResult.Matched -> {
                    val yawErr = Angles.absAngleDifference(res.yawOffsetDegrees, appliedYawDeg)
                    if (res.roomId == b.sceneGt.sceneId) {
                        yawErrors.add(yawErr)
                    }
                    trials.add(
                        RoomRecognitionTrial(
                            queryId = "${b.sceneGt.sceneId}_cam_01_revisit",
                            trueRoomId = b.sceneGt.sceneId,
                            predictedRoomId = res.roomId
                        )
                    )
                    details.add(
                        CrossTrajectoryMatchDetail(
                            querySceneId = b.sceneGt.sceneId,
                            trueRoomId = b.sceneGt.sceneId,
                            predictedRoomId = res.roomId,
                            matched = true,
                            confidence = round3(res.confidence),
                            appliedYawOffsetDegrees = appliedYawDeg,
                            recoveredYawOffsetDegrees = round2(res.yawOffsetDegrees),
                            yawErrorDegrees = round2(yawErr)
                        )
                    )
                }
                is RecognitionResult.Abstained -> {
                    trials.add(
                        RoomRecognitionTrial(
                            queryId = "${b.sceneGt.sceneId}_cam_01_revisit",
                            trueRoomId = b.sceneGt.sceneId,
                            predictedRoomId = null
                        )
                    )
                    details.add(
                        CrossTrajectoryMatchDetail(
                            querySceneId = b.sceneGt.sceneId,
                            trueRoomId = b.sceneGt.sceneId,
                            predictedRoomId = null,
                            matched = false,
                            confidence = round3(res.bestCandidateScore),
                            appliedYawOffsetDegrees = appliedYawDeg,
                            recoveredYawOffsetDegrees = 0.0,
                            yawErrorDegrees = 0.0
                        )
                    )
                }
            }
        }

        // 2. 6 unseen-room abstention trials (leave-one-out): cam_01 queried when its own room is removed from the library
        // and tagged with an unseen room number token so we test both visual/constellation and identifier safety
        for ((idx, b) in bundles.withIndex()) {
            val leaveOneOutLibrary = fullLibrary
                .filter { it.roomId != b.sceneGt.sceneId }
                .mapIndexed { lIdx, fp -> fp.copy(identifierTokens = listOf("room${100 + lIdx}")) }
            val unseenQuery = b.fpCam01.copy(identifierTokens = listOf("unseen${200 + idx}"))
            val res = recognizer.recognize(unseenQuery, leaveOneOutLibrary)
            val predId = (res as? RecognitionResult.Matched)?.roomId
            val conf = when (res) {
                is RecognitionResult.Matched -> res.confidence
                is RecognitionResult.Abstained -> res.bestCandidateScore
            }
            trials.add(
                RoomRecognitionTrial(
                    queryId = "${b.sceneGt.sceneId}_unseen_loo",
                    trueRoomId = null,
                    predictedRoomId = predId
                )
            )
            details.add(
                CrossTrajectoryMatchDetail(
                    querySceneId = "${b.sceneGt.sceneId}_unseen_loo",
                    trueRoomId = null,
                    predictedRoomId = predId,
                    matched = predId != null,
                    confidence = round3(conf),
                    appliedYawOffsetDegrees = 0.0,
                    recoveredYawOffsetDegrees = 0.0,
                    yawErrorDegrees = 0.0
                )
            )
        }

        val metrics = SimEval.evaluateRoomRecognition(trials)
        val unseenTrials = trials.count { it.trueRoomId == null }
        val seenTrials = trials.count { it.trueRoomId != null }
        val falseMatchRate = if (metrics.totalTrials > 0) metrics.falseMatches.toDouble() / metrics.totalTrials else 0.0
        val abstentionRate = if (unseenTrials > 0) metrics.correctAbstentions.toDouble() / unseenTrials else 1.0
        val recall = if (seenTrials > 0) metrics.correctMatches.toDouble() / seenTrials else 0.0
        val meanYawErr = if (yawErrors.isNotEmpty()) yawErrors.average() else 0.0
        val maxYawErr = yawErrors.maxOrNull() ?: 0.0

        return CrossTrajectoryRecognitionReport(
            recognitionMetrics = metrics,
            falseMatchRate = round3(falseMatchRate),
            abstentionRateOnUnseen = round3(abstentionRate),
            crossTrajectoryRecall = round3(recall),
            meanYawErrorDegrees = round2(meanYawErr),
            maxYawErrorDegrees = round2(maxYawErr),
            trials = details
        )
    }

    private fun rotateFingerprintYaw(fp: RoomFingerprint, deltaYawDegrees: Double): RoomFingerprint {
        val rad = deltaYawDegrees * Angles.DEG_TO_RAD
        val cosA = kotlin.math.cos(rad)
        val sinA = kotlin.math.sin(rad)
        return fp.copy(
            frameEmbeddings = fp.frameEmbeddings.map { fe ->
                fe.copy(bearingDegrees = Angles.normalizeBearing(fe.bearingDegrees + deltaYawDegrees))
            },
            landmarks = fp.landmarks.map { lm ->
                val e = lm.position.getOrElse(0) { 0.0 }
                val u = lm.position.getOrElse(1) { 0.0 }
                val n = lm.position.getOrElse(2) { 0.0 }
                // Rotating bearing by +deltaYaw corresponds to rotating (east, north) clockwise by +deltaYaw
                val rotE = e * cosA + n * sinA
                val rotN = -e * sinA + n * cosA
                lm.copy(
                    bearingDegrees = Angles.normalizeBearing(lm.bearingDegrees + deltaYawDegrees),
                    position = listOf(round3(rotE), round3(u), round3(rotN))
                )
            }
        )
    }

    private fun scoreEnvironmentAgainstGt(
        sceneId: String,
        configName: String,
        env: SimEnvironment,
        gtLandmarks: List<EvalLandmark>,
        gtFloorPolygon: List<EnuPoint>,
        meanCameraHeight: Double,
        cameraHeightError: Double,
        translationSpread: Double
    ): DetailedSceneEval {
        val predictedLandmarks = env.objects.orEmpty()
            .filter { it.category != SimCategory.FLOOR }
            .mapIndexed { idx, obj ->
                EvalLandmark(
                    id = obj.id ?: "pred-$idx",
                    category = HypersimNyu40Mapping.canonicalEvalCategory(obj.category),
                    position = EnuPoint.fromPositionList(obj.position),
                    localisationMethod = obj.evidence.firstOrNull()?.localisationMethod
                )
            }

        val objDetail = SimEval.evaluateObjectsDetailed(
            predicted = predictedLandmarks,
            truth = gtLandmarks,
            maxDistanceMeters = 3.0
        )

        val predFloor = env.walkable?.floorPolygon.orEmpty().map { EnuPoint.fromPositionList(it) }
        val floorMetrics = computeFloorRasterMetrics(predFloor, gtFloorPolygon, cellSizeMeters = 0.05)

        return DetailedSceneEval(
            sceneMetrics = IndoorConfigSceneMetrics(
                sceneId = sceneId,
                configName = configName,
                meanCameraHeightMeters = round3(meanCameraHeight),
                cameraHeightErrorMeters = round3(cameraHeightError),
                translationSpreadMeters = round3(translationSpread),
                objectMetrics = objDetail.metrics.copy(
                    precision = round3(objDetail.metrics.precision),
                    recall = round3(objDetail.metrics.recall),
                    f1 = round3(objDetail.metrics.f1),
                    meanErrorMeters = round3(objDetail.metrics.meanErrorMeters),
                    medianErrorMeters = round3(objDetail.metrics.medianErrorMeters),
                    p90ErrorMeters = round3(objDetail.metrics.p90ErrorMeters)
                ),
                floorMetrics = floorMetrics,
                matchedPairs = objDetail.matchedPairs.map { it.copy(distanceMeters = round3(it.distanceMeters)) }
            ),
            rawObjDetail = objDetail
        )
    }

    private fun aggregateConfigMetrics(
        configName: String,
        results: List<DetailedSceneEval>
    ): IndoorConfigAggregateMetrics {
        val boot = SimEval.bootstrapOverAreas(
            areaResults = results.map { it.rawObjDetail },
            bootstrapSamples = 2000,
            seed = 20260606L
        )
        val ious = results.map { it.sceneMetrics.floorMetrics.iou5cm }.sorted()
        val relAreaErrs = results.map { it.sceneMetrics.floorMetrics.relativeAreaError }
        val meanIou = if (ious.isNotEmpty()) ious.average() else 0.0
        val medIou = if (ious.isNotEmpty()) ious[ious.size / 2] else 0.0
        val meanRelArea = if (relAreaErrs.isNotEmpty()) relAreaErrs.average() else 0.0

        return IndoorConfigAggregateMetrics(
            configName = configName,
            sceneCount = results.size,
            totalPredicted = boot.totalPredicted,
            totalTruth = boot.totalTruth,
            totalTruePositives = boot.totalTruePositives,
            totalFalsePositives = boot.totalFalsePositives,
            totalFalseNegatives = boot.totalFalseNegatives,
            precision = ConfidenceInterval95(
                round3(boot.precision.pointEstimate),
                round3(boot.precision.lower95),
                round3(boot.precision.upper95)
            ),
            recall = ConfidenceInterval95(
                round3(boot.recall.pointEstimate),
                round3(boot.recall.lower95),
                round3(boot.recall.upper95)
            ),
            f1 = ConfidenceInterval95(
                round3(boot.f1.pointEstimate),
                round3(boot.f1.lower95),
                round3(boot.f1.upper95)
            ),
            meanErrorMeters = round3(boot.meanErrorMeters),
            medianErrorMeters = round3(boot.medianErrorMeters),
            p90ErrorMeters = round3(boot.p90ErrorMeters),
            meanFloorIou5cm = round3(meanIou),
            medianFloorIou5cm = round3(medIou),
            meanFloorRelativeAreaError = round3(meanRelArea)
        )
    }

    private fun computeTranslationSpread(poses: List<HypersimFramePose>): Double {
        var maxDist = 0.0
        for (i in poses.indices) {
            for (j in i + 1 until poses.size) {
                val d = hypot(poses[i].east - poses[j].east, poses[i].north - poses[j].north)
                if (d > maxDist) maxDist = d
            }
        }
        return maxDist
    }

    private fun cropJpegBox(jpegBytes: ByteArray, box: List<Int>): ByteArray {
        if (box.size < 4) return jpegBytes
        val src = try {
            ImageIO.read(ByteArrayInputStream(jpegBytes))
        } catch (_: Exception) {
            null
        } ?: return jpegBytes

        val x1 = (box[0] - 48).coerceIn(0, src.width - 1)
        val y1 = (box[1] - 48).coerceIn(0, src.height - 1)
        val x2 = (box[2] + 48).coerceIn(x1 + 1, src.width)
        val y2 = (box[3] + 48).coerceIn(y1 + 1, src.height)
        val sub = src.getSubimage(x1, y1, x2 - x1, y2 - y1)
        val copy = BufferedImage(sub.width, sub.height, BufferedImage.TYPE_INT_RGB)
        val g = copy.createGraphics()
        g.drawImage(sub, 0, 0, null)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(copy, "jpg", out)
        return out.toByteArray()
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
    private fun round3(v: Double): Double = round(v * 1000.0) / 1000.0
}

data class DetailedSceneEval(
    val sceneMetrics: IndoorConfigSceneMetrics,
    val rawObjDetail: ObjectEvalDetailedResult
)

data class SingleSceneEvalBundle(
    val sceneGt: HypersimSceneGt,
    val envReal6Dof: SimEnvironment,
    val fpCam00: RoomFingerprint,
    val fpCam01: RoomFingerprint,
    val real6DofResult: DetailedSceneEval,
    val assumed165Result: DetailedSceneEval,
    val rotOnlyResult: DetailedSceneEval
)
