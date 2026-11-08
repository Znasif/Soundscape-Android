package org.scottishtecharmy.soundscape.sim.geo

import kotlin.math.PI
import kotlin.math.tan

/**
 * 2D sub-pixel coordinate `(u, v)` in an image (`u` right from `0..width`, `v` down from `0..height`).
 */
data class PixelCoord(
    val u: Double,
    val v: Double
)

/**
 * Equirectangular 360x180 panorama projection utilities.
 *
 * Convention:
 * - Horizontal center `u = width / 2.0` points along `compassAngleDegrees`.
 * - `u` increases clockwise with bearing (`+360°` across `width`).
 * - `v = 0` is zenith (`+90°` elevation), `v = height / 2.0` is horizon (`0°`), `v = height` is nadir (`-90°`).
 */
object EquirectangularPano {
    /**
     * Converts pixel `(u, v)` in a `width x height` equirectangular panorama with center heading
     * [compassAngleDegrees] into a world [SphericalRay] `(bearingDegrees, elevationDegrees)`.
     */
    fun pixelToSpherical(
        u: Double,
        v: Double,
        width: Int,
        height: Int,
        compassAngleDegrees: Double
    ): SphericalRay {
        require(width > 0 && height > 0) { "Panorama dimensions must be positive" }
        val relYaw = ((u / width.toDouble()) - 0.5) * 360.0
        val bearing = Angles.normalizeBearing(compassAngleDegrees + relYaw)
        val elevation = (0.5 - (v / height.toDouble())) * 180.0
        return SphericalRay(
            bearingDegrees = bearing,
            elevationDegrees = elevation.coerceIn(-90.0, 90.0)
        )
    }

    /**
     * Converts a world [SphericalRay] `(bearingDegrees, elevationDegrees)` into pixel coordinates `(u, v)`
     * in a `width x height` equirectangular panorama with heading [compassAngleDegrees].
     */
    fun sphericalToPixel(
        bearingDegrees: Double,
        elevationDegrees: Double,
        width: Int,
        height: Int,
        compassAngleDegrees: Double
    ): PixelCoord {
        require(width > 0 && height > 0) { "Panorama dimensions must be positive" }
        val relYaw = Angles.angleDifference(compassAngleDegrees, bearingDegrees) // in (-180, 180]
        var uNorm = 0.5 + (relYaw / 360.0)
        if (uNorm < 0.0) uNorm += 1.0
        if (uNorm >= 1.0) uNorm -= 1.0
        val vNorm = (0.5 - (elevationDegrees.coerceIn(-90.0, 90.0) / 180.0)).coerceIn(0.0, 1.0)
        return PixelCoord(
            u = uNorm * width.toDouble(),
            v = vNorm * height.toDouble()
        )
    }
}

/**
 * Rectilinear pinhole perspective camera model used for both panorama perspective crops
 * and indoor Ray-Ban Meta camera frames.
 *
 * @property yawDegrees World compass bearing (`[0, 360)`) of the camera's optical center.
 * @property pitchDegrees World elevation angle (`[-90, 90]`, `+` up, `-` down) of the optical center.
 * @property hFovDegrees Horizontal field of view in degrees (e.g., `90.0` for street crops, `~68.0` for glasses).
 * @property width Image width in pixels.
 * @property height Image height in pixels.
 * @property rollDegrees Clockwise camera roll in degrees (default `0.0`).
 */
data class PerspectiveCamera(
    val yawDegrees: Double,
    val pitchDegrees: Double = 0.0,
    val hFovDegrees: Double = 90.0,
    val width: Int = 640,
    val height: Int = 640,
    val rollDegrees: Double = 0.0
) {
    init {
        require(width > 0 && height > 0) { "Camera width and height must be positive" }
        require(hFovDegrees in 1.0..179.0) { "hFovDegrees must be in [1, 179]" }
    }

    val focalLengthPx: Double = (width * 0.5) / tan(0.5 * hFovDegrees * PI / 180.0)
    val cx: Double = width * 0.5
    val cy: Double = height * 0.5
    val orientation: Quaternion = Quaternion.fromYawPitchRoll(yawDegrees, pitchDegrees, rollDegrees)
    private val invOrientation: Quaternion = orientation.conjugate()

    /**
     * Converts pixel `(u, v)` (`u` right from `0..width`, `v` down from `0..height`) into a
     * camera-local ray `(x = Right, y = Forward, z = Up)`.
     */
    fun pixelToCameraVector(u: Double, v: Double): Vec3 = Vec3(
        x = (u - cx) / focalLengthPx,
        y = 1.0,
        z = (cy - v) / focalLengthPx
    ).normalized()

    /**
     * Converts pixel `(u, v)` into a world [SphericalRay] `(bearingDegrees, elevationDegrees)`.
     */
    fun pixelToRay(u: Double, v: Double): SphericalRay =
        orientation.cameraRayToWorld(pixelToCameraVector(u, v))

    /**
     * Projects a world [SphericalRay] onto image pixel coordinates `(u, v)`.
     * Returns `null` if the ray lies behind the camera (`forward <= 1e-6`).
     */
    fun rayToPixel(ray: SphericalRay): PixelCoord? {
        val worldVec = ray.toUnitVectorEnu() // x=East, y=North, z=Up
        val camVec = invOrientation.rotate(worldVec) // x=Right, y=Forward, z=Up
        if (camVec.y <= 1e-6) return null
        val u = cx + focalLengthPx * (camVec.x / camVec.y)
        val v = cy - focalLengthPx * (camVec.z / camVec.y)
        return PixelCoord(u = u, v = v)
    }

    /**
     * Maps pixel `(u, v)` of this perspective crop directly to source pixel `(uPano, vPano)`
     * in an equirectangular panorama.
     */
    fun pixelToEquirectangular(
        u: Double,
        v: Double,
        panoWidth: Int,
        panoHeight: Int,
        panoCompassAngleDegrees: Double
    ): PixelCoord {
        val ray = pixelToRay(u, v)
        return EquirectangularPano.sphericalToPixel(
            bearingDegrees = ray.bearingDegrees,
            elevationDegrees = ray.elevationDegrees,
            width = panoWidth,
            height = panoHeight,
            compassAngleDegrees = panoCompassAngleDegrees
        )
    }
}
