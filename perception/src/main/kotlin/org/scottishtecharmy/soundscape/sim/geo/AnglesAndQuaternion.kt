package org.scottishtecharmy.soundscape.sim.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * World direction expressed as compass bearing (`[0, 360)`, clockwise from North)
 * and elevation above horizon (`[-90, 90]`, positive up, negative down).
 */
data class SphericalRay(
    val bearingDegrees: Double,
    val elevationDegrees: Double
) {
    /**
     * Converts this ray into a unit 3D vector in local ENU `(east, north, up)`.
     */
    fun toUnitVectorEnu(): Vec3 {
        val az = bearingDegrees * DEG_TO_RAD
        val el = elevationDegrees * DEG_TO_RAD
        val cosEl = cos(el)
        return Vec3(
            x = sin(az) * cosEl, // East
            y = cos(az) * cosEl, // North
            z = sin(el)          // Up
        )
    }

    companion object {
        private const val DEG_TO_RAD = PI / 180.0
        private const val RAD_TO_DEG = 180.0 / PI

        /**
         * Converts a 3D vector `(east, north, up)` into a [SphericalRay].
         */
        fun fromVectorEnu(east: Double, north: Double, up: Double): SphericalRay {
            val horiz = hypot(east, north)
            val bearing = Angles.normalizeBearing(atan2(east, north) * RAD_TO_DEG)
            val elevation = atan2(up, horiz) * RAD_TO_DEG
            return SphericalRay(bearingDegrees = bearing, elevationDegrees = elevation)
        }
    }
}

/**
 * Simple 3D vector `(x, y, z)`.
 */
data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double
) {
    fun norm(): Double = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val n = norm()
        return if (n > 1e-12) Vec3(x / n, y / n, z / n) else Vec3(0.0, 0.0, 0.0)
    }

    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 = Vec3(
        x = y * other.z - z * other.y,
        y = z * other.x - x * other.z,
        z = x * other.y - y * other.x
    )
}

/**
 * Euler orientation angles in degrees:
 * - [yawDegrees]: compass heading in `[0, 360)` clockwise from true North (`0° = North`, `90° = East`).
 * - [pitchDegrees]: elevation angle in `[-90, 90]` (`+` = pitching nose up above horizon, `-` = looking down).
 * - [rollDegrees]: clockwise bank angle in `(-180, 180]` around the forward viewing axis.
 */
data class EulerAngles(
    val yawDegrees: Double,
    val pitchDegrees: Double,
    val rollDegrees: Double
)

/**
 * Compass bearing and angular arithmetic utilities.
 */
object Angles {
    const val DEG_TO_RAD = PI / 180.0
    const val RAD_TO_DEG = 180.0 / PI

    /**
     * Normalizes any angle in degrees into `[0.0, 360.0)`.
     */
    fun normalizeBearing(degrees: Double): Double {
        val mod = degrees % 360.0
        val result = if (mod < 0.0) mod + 360.0 else mod
        return if (result >= 360.0 || result == -0.0) 0.0 else result
    }

    /**
     * Signed shortest angular difference `targetDegrees - sourceDegrees` in `(-180.0, 180.0]`.
     * Positive means `targetDegrees` is clockwise from `sourceDegrees`.
     */
    fun angleDifference(sourceDegrees: Double, targetDegrees: Double): Double {
        var diff = (targetDegrees - sourceDegrees) % 360.0
        if (diff <= -180.0) diff += 360.0
        if (diff > 180.0) diff -= 360.0
        return if (diff == -0.0) 0.0 else diff
    }

    /**
     * Unsigned shortest angular difference in `[0.0, 180.0]`.
     */
    fun absAngleDifference(aDegrees: Double, bDegrees: Double): Double =
        abs(angleDifference(aDegrees, bDegrees))

    /**
     * Compass bearing in `[0.0, 360.0)` from `from` to `to` in local ENU coordinates.
     */
    fun bearingBetweenEnu(from: EnuPoint, to: EnuPoint): Double {
        val de = to.east - from.east
        val dn = to.north - from.north
        return normalizeBearing(atan2(de, dn) * RAD_TO_DEG)
    }

    /**
     * Compass bearing in `[0.0, 360.0)` from `from` to `to` in WGS84 coordinates.
     */
    fun bearingBetweenLatLon(from: LatLon, to: LatLon): Double {
        val enu = LocalEnu(from.latitude, from.longitude).latLonToEnu(to)
        return normalizeBearing(atan2(enu.east, enu.north) * RAD_TO_DEG)
    }

    /**
     * Circular mean of a non-empty list of bearings in degrees, returned in `[0.0, 360.0)`.
     */
    fun circularMeanDegrees(bearings: List<Double>): Double {
        require(bearings.isNotEmpty()) { "bearings must not be empty" }
        var sumSin = 0.0
        var sumCos = 0.0
        for (b in bearings) {
            val rad = b * DEG_TO_RAD
            sumSin += sin(rad)
            sumCos += cos(rad)
        }
        return normalizeBearing(atan2(sumSin, sumCos) * RAD_TO_DEG)
    }
}

/**
 * Scalar-first orientation quaternion `(w, x, y, z)` matching the Meta Wearables Device Access Toolkit
 * (`mwdat-motion` `MotionSample.quaternion`).
 *
 * ### Axis Convention (Documented in `DECISIONS.md` D-05):
 * - **Local body / camera frame**:
 *   - `+X`: Right
 *   - `+Y`: Forward (optical axis at `yaw = 0°, pitch = 0°` points along `+Y` = North)
 *   - `+Z`: Up
 * - **World ENU frame**:
 *   - `+X`: East
 *   - `+Y`: North
 *   - `+Z`: Up
 * - **Intrinsic rotation sequence** `Z(-yaw) * X(pitch) * Y(roll)`:
 *   - `yawDegrees`: clockwise heading from North (`+Y`) toward East (`+X`), i.e., right-handed rotation by `-yaw` about `+Z` (Up).
 *   - `pitchDegrees`: elevation above horizon, i.e., right-handed rotation by `+pitch` about local `+X` (Right).
 *   - `rollDegrees`: clockwise bank around forward `+Y`, i.e., right-handed rotation by `+roll` about local `+Y` (Forward).
 */
data class Quaternion(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double
) {
    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n > 1e-12) Quaternion(w / n, x / n, y / n, z / n) else IDENTITY
    }

    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    operator fun times(other: Quaternion): Quaternion = Quaternion(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w
    )

    /**
     * Rotates a 3D vector `(x=Right, y=Forward, z=Up)` from the body/camera frame into the world `(East, North, Up)` frame.
     */
    fun rotate(v: Vec3): Vec3 {
        val q = normalized()
        val u = Vec3(q.x, q.y, q.z)
        val s = q.w
        val uDotV = u.dot(v)
        val uDotU = u.dot(u)
        val uCrossV = u.cross(v)
        return Vec3(
            x = 2.0 * uDotV * u.x + (s * s - uDotU) * v.x + 2.0 * s * uCrossV.x,
            y = 2.0 * uDotV * u.y + (s * s - uDotU) * v.y + 2.0 * s * uCrossV.y,
            z = 2.0 * uDotV * u.z + (s * s - uDotU) * v.z + 2.0 * s * uCrossV.z
        )
    }

    /**
     * Extracts `(yawDegrees, pitchDegrees, rollDegrees)` under the intrinsic `Z(-yaw) * X(pitch) * Y(roll)` convention.
     */
    fun toYawPitchRoll(): EulerAngles {
        val q = normalized()
        // Forward axis (0, 1, 0) rotated into world ENU:
        // fwd.x = 2*(x*y - w*z)
        // fwd.y = 1 - 2*(x*x + z*z)
        // fwd.z = 2*(y*z + w*x)
        val fwdEast = 2.0 * (q.x * q.y - q.w * q.z)
        val fwdNorth = 1.0 - 2.0 * (q.x * q.x + q.z * q.z)
        val fwdUp = (2.0 * (q.y * q.z + q.w * q.x)).coerceIn(-1.0, 1.0)

        val pitchDeg = asin(fwdUp) * Angles.RAD_TO_DEG
        val yawDeg = Angles.normalizeBearing(atan2(fwdEast, fwdNorth) * Angles.RAD_TO_DEG)

        // R[0,2] = 2*(x*z + w*y) = -cos(pitch)*sin(roll) when yaw=0; in general:
        // Up component of local Right axis (1, 0, 0) is R[2,0] = 2*(x*z - w*y) = -cos(pitch)*sin(roll)
        // Up component of local Up axis (0, 0, 1) is R[2,2] = 1 - 2*(x*x + y*y) = cos(pitch)*cos(roll)
        val r20 = 2.0 * (q.x * q.z - q.w * q.y)
        val r22 = 1.0 - 2.0 * (q.x * q.x + q.y * q.y)
        val rollDeg = atan2(-r20, r22) * Angles.RAD_TO_DEG

        return EulerAngles(
            yawDegrees = yawDeg,
            pitchDegrees = pitchDeg,
            rollDegrees = rollDeg
        )
    }

    /**
     * Maps a camera-local ray `(right, forward, up)` into a world [SphericalRay] `(bearingDegrees, elevationDegrees)`.
     */
    fun cameraRayToWorld(cameraRayRightForwardUp: Vec3): SphericalRay {
        val worldEnu = rotate(cameraRayRightForwardUp)
        return SphericalRay.fromVectorEnu(
            east = worldEnu.x,
            north = worldEnu.y,
            up = worldEnu.z
        )
    }

    /**
     * Converts to XR Blocks `[x, y, z, w]` list representation.
     */
    fun toXrBlocksList(): List<Double> = listOf(x, y, z, w)

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        /**
         * Constructs a scalar-first [Quaternion] from compass `yawDegrees` (`[0, 360)` clockwise from North),
         * `pitchDegrees` (`[-90, 90]`, positive up), and `rollDegrees` (`(-180, 180]`, clockwise bank).
         */
        fun fromYawPitchRoll(
            yawDegrees: Double,
            pitchDegrees: Double = 0.0,
            rollDegrees: Double = 0.0
        ): Quaternion {
            val halfZ = -0.5 * yawDegrees * Angles.DEG_TO_RAD
            val halfX = 0.5 * pitchDegrees * Angles.DEG_TO_RAD
            val halfY = 0.5 * rollDegrees * Angles.DEG_TO_RAD

            val qz = Quaternion(cos(halfZ), 0.0, 0.0, sin(halfZ))
            val qx = Quaternion(cos(halfX), sin(halfX), 0.0, 0.0)
            val qy = Quaternion(cos(halfY), 0.0, sin(halfY), 0.0)

            return (qz * qx * qy).normalized()
        }

        fun fromXrBlocksList(list: List<Double>): Quaternion {
            require(list.size == 4) { "Quaternion list must have 4 elements [x, y, z, w]" }
            return Quaternion(w = list[3], x = list[0], y = list[1], z = list[2]).normalized()
        }
    }
}
