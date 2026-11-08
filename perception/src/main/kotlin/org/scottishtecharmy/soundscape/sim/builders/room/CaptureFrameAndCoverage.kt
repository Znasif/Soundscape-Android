package org.scottishtecharmy.soundscape.sim.builders.room

import kotlinx.serialization.Serializable
import org.scottishtecharmy.soundscape.sim.geo.Angles
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.PerspectiveCamera
import org.scottishtecharmy.soundscape.sim.geo.Quaternion
import kotlin.math.roundToInt

/**
 * Single indoor head-turn frame captured from Ray-Ban Meta smart glasses (`mwdat-camera` + `mwdat-motion`),
 * or from a 6-DoF indoor benchmark trajectory (such as Apple Hypersim) when [cameraPositionEnu] is provided.
 *
 * Pure Kotlin (`commonMain`-ready).
 *
 * @property frameId Unique identifier for this frame within the scan session.
 * @property timestampNs Capture timestamp in nanoseconds.
 * @property imageBytes Encoded JPEG image bytes (up to `720x1280` or `1024x768`).
 * @property orientation Scalar-first fused IMU orientation `(w, x, y, z)` at capture time.
 * @property gyroMagnitudeRadPerSec Instantaneous angular velocity magnitude $\sqrt{\omega_x^2 + \omega_y^2 + \omega_z^2}$ in rad/s.
 * @property hFovDegrees Horizontal field of view in degrees (default `68.0°` for portrait `720x1280`).
 * @property imageWidth Frame width in pixels (default `720`).
 * @property imageHeight Frame height in pixels (default `1280`).
 * @property cameraHeightMeters Eye/glasses height above the floor in metres (default `1.65 m`).
 * @property cameraPositionEnu Optional 3D camera position in local room ENU (`east`, `north`, `up = 0` on floor). Defaults to `null` (rotation-only glasses mode at `(0, 0)`).
 */
data class CaptureFrame(
    val frameId: String,
    val timestampNs: Long,
    val imageBytes: ByteArray,
    val orientation: Quaternion,
    val gyroMagnitudeRadPerSec: Double,
    val hFovDegrees: Double = 68.0,
    val imageWidth: Int = 720,
    val imageHeight: Int = 1280,
    val cameraHeightMeters: Double = 1.65,
    val cameraPositionEnu: EnuPoint? = null
) {
    val eulerAngles = orientation.toYawPitchRoll()

    fun toPerspectiveCamera(): PerspectiveCamera = PerspectiveCamera(
        yawDegrees = eulerAngles.yawDegrees,
        pitchDegrees = eulerAngles.pitchDegrees,
        hFovDegrees = hFovDegrees,
        width = imageWidth,
        height = imageHeight,
        rollDegrees = eulerAngles.rollDegrees
    )
}

/**
 * Type of angular coverage slot on the viewing sphere.
 */
@Serializable
enum class CoverageSlotType {
    HORIZON,
    UP,
    DOWN
}

/**
 * Structured representation of an uncovered angular slot so the mobile app can issue spoken guidance.
 *
 * @property slotIndex Slot index (`0..17` for horizon `20°` yaw slots, `18..19` for up slots, `20..21` for down slots).
 * @property slotType Whether this slot is along the horizon, pitched up, or pitched down.
 * @property centerYawDegrees Target world yaw bearing (`[0, 360)`) of the slot center.
 * @property targetPitchDegrees Target pitch elevation in degrees (`0.0` for horizon, `+20.0` for up, `-20.0` for down).
 * @property clockDirection Clock-face direction (`1..12`) relative to North (`0° = 12 o'clock`, `90° = 3 o'clock`).
 * @property spokenGuidanceHint Spoken prompt suitable for TTS (e.g. `"Turn toward 3 o'clock"`, `"Tilt slightly down"`).
 */
@Serializable
data class MissingCoverageSlot(
    val slotIndex: Int,
    val slotType: CoverageSlotType,
    val centerYawDegrees: Double,
    val targetPitchDegrees: Double,
    val clockDirection: Int,
    val spokenGuidanceHint: String
)

/**
 * Coverage evaluation result for a sequence of [CaptureFrame]s.
 */
data class CoverageReport(
    val acceptedFramesBySlot: Map<Int, CaptureFrame>,
    val rejectedBlurryCount: Int,
    val missingSlots: List<MissingCoverageSlot>,
    val horizonCoverageFraction: Double,
    val totalCoverageFraction: Double,
    val nextSpokenPrompt: String?
) {
    val isHorizonComplete: Boolean
        get() = missingSlots.none { it.slotType == CoverageSlotType.HORIZON }

    val isFullCoverageComplete: Boolean
        get() = missingSlots.isEmpty()

    /**
     * Distinct accepted keyframes ordered by yaw bearing.
     */
    val selectedKeyframes: List<CaptureFrame>
        get() = acceptedFramesBySlot.values
            .distinctBy { it.frameId }
            .sortedBy { it.eulerAngles.yawDegrees }
}

/**
 * Tracks spherical head-turn scan coverage across:
 * - `18` horizon yaw slots (`20°` each: `[0..20), [20..40), ..., [340..360)`),
 * - `2` upward pitch slots (`pitch >= +12°` in front `[0..180)` and back `[180..360)` hemispheres),
 * - `2` downward pitch slots (`pitch <= -12°` in front `[0..180)` and back `[180..360)` hemispheres).
 *
 * Rejects frames captured while turning too fast (`gyroMagnitudeRadPerSec > maxGyroRadPerSec`)
 * so motion-blurred frames are never sent to `SegmentationClient`.
 */
class CaptureCoverage(
    val maxGyroRadPerSec: Double = 0.8,
    val numHorizonSlots: Int = 18,
    val minUpPitchDegrees: Double = 12.0,
    val maxDownPitchDegrees: Double = -12.0
) {
    private val slotWidthDegrees = 360.0 / numHorizonSlots.toDouble()
    private val bestFramePerSlot = mutableMapOf<Int, CaptureFrame>()
    private var rejectedBlurryFrames = 0

    /**
     * Evaluates and ingests a single [CaptureFrame].
     * Returns `true` if the frame passed the motion-blur gate (`gyroMagnitudeRadPerSec <= maxGyroRadPerSec`).
     */
    fun offerFrame(frame: CaptureFrame): Boolean {
        if (frame.gyroMagnitudeRadPerSec > maxGyroRadPerSec) {
            rejectedBlurryFrames++
            return false
        }

        val yaw = Angles.normalizeBearing(frame.eulerAngles.yawDegrees)
        val pitch = frame.eulerAngles.pitchDegrees

        // Horizon slot (valid when camera pitch includes the horizon region [-28, +28] deg)
        if (pitch in -28.0..28.0) {
            val hSlot = ((yaw / slotWidthDegrees).toInt()).coerceIn(0, numHorizonSlots - 1)
            updateSlotIfSharper(hSlot, frame)
        }

        // Upward pitch slots (slots numHorizonSlots and numHorizonSlots + 1)
        if (pitch >= minUpPitchDegrees) {
            val upSlot = if (yaw < 180.0) numHorizonSlots else numHorizonSlots + 1
            updateSlotIfSharper(upSlot, frame)
        }

        // Downward pitch slots (slots numHorizonSlots + 2 and numHorizonSlots + 3)
        if (pitch <= maxDownPitchDegrees) {
            val downSlot = if (yaw < 180.0) numHorizonSlots + 2 else numHorizonSlots + 3
            updateSlotIfSharper(downSlot, frame)
        }

        return true
    }

    /**
     * Ingests a batch of frames and returns the resulting [CoverageReport].
     */
    fun evaluate(frames: List<CaptureFrame>): CoverageReport {
        for (f in frames) {
            offerFrame(f)
        }
        return buildReport()
    }

    /**
     * Builds the current [CoverageReport] including missing slots and spoken TTS hints.
     */
    fun buildReport(): CoverageReport {
        val missing = mutableListOf<MissingCoverageSlot>()
        var coveredHorizon = 0

        for (slot in 0 until numHorizonSlots) {
            if (bestFramePerSlot.containsKey(slot)) {
                coveredHorizon++
            } else {
                val centerYaw = (slot + 0.5) * slotWidthDegrees
                val clock = bearingToClock(centerYaw)
                missing.add(
                    MissingCoverageSlot(
                        slotIndex = slot,
                        slotType = CoverageSlotType.HORIZON,
                        centerYawDegrees = centerYaw,
                        targetPitchDegrees = 0.0,
                        clockDirection = clock,
                        spokenGuidanceHint = "Turn slowly toward $clock o'clock (${centerYaw.roundToInt()} degrees)"
                    )
                )
            }
        }

        // Upward slots
        for (hemi in 0..1) {
            val slot = numHorizonSlots + hemi
            if (!bestFramePerSlot.containsKey(slot)) {
                val centerYaw = if (hemi == 0) 90.0 else 270.0
                val clock = bearingToClock(centerYaw)
                missing.add(
                    MissingCoverageSlot(
                        slotIndex = slot,
                        slotType = CoverageSlotType.UP,
                        centerYawDegrees = centerYaw,
                        targetPitchDegrees = 20.0,
                        clockDirection = clock,
                        spokenGuidanceHint = "Tilt head slightly up toward $clock o'clock to check high signs"
                    )
                )
            }
        }

        // Downward slots
        for (hemi in 0..1) {
            val slot = numHorizonSlots + 2 + hemi
            if (!bestFramePerSlot.containsKey(slot)) {
                val centerYaw = if (hemi == 0) 90.0 else 270.0
                val clock = bearingToClock(centerYaw)
                missing.add(
                    MissingCoverageSlot(
                        slotIndex = slot,
                        slotType = CoverageSlotType.DOWN,
                        centerYawDegrees = centerYaw,
                        targetPitchDegrees = -20.0,
                        clockDirection = clock,
                        spokenGuidanceHint = "Tilt head slightly down toward $clock o'clock to capture the floor boundary"
                    )
                )
            }
        }

        val totalSlots = numHorizonSlots + 4
        val coveredTotal = bestFramePerSlot.size
        val horizonFrac = coveredHorizon.toDouble() / numHorizonSlots.toDouble()
        val totalFrac = coveredTotal.toDouble() / totalSlots.toDouble()

        return CoverageReport(
            acceptedFramesBySlot = bestFramePerSlot.toMap(),
            rejectedBlurryCount = rejectedBlurryFrames,
            missingSlots = missing,
            horizonCoverageFraction = horizonFrac,
            totalCoverageFraction = totalFrac,
            nextSpokenPrompt = missing.firstOrNull()?.spokenGuidanceHint
        )
    }

    private fun updateSlotIfSharper(slot: Int, candidate: CaptureFrame) {
        val current = bestFramePerSlot[slot]
        if (current == null || candidate.gyroMagnitudeRadPerSec < current.gyroMagnitudeRadPerSec) {
            bestFramePerSlot[slot] = candidate
        }
    }

    companion object {
        /**
         * Maps compass bearing `[0, 360)` to clock face `1..12` (`0° -> 12`, `30° -> 1`, `90° -> 3`).
         */
        fun bearingToClock(bearingDegrees: Double): Int {
            val norm = Angles.normalizeBearing(bearingDegrees)
            val hour = ((norm / 30.0).roundToInt()) % 12
            return if (hour == 0) 12 else hour
        }
    }
}
