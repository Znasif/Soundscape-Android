package org.scottishtecharmy.soundscape.sim.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 geographic coordinate in decimal degrees.
 */
data class LatLon(
    val latitude: Double,
    val longitude: Double
)

/**
 * Geographic bounding box `(minLon, minLat, maxLon, maxLat)`.
 */
data class BoundingBox(
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double
) {
    fun contains(latLon: LatLon): Boolean =
        latLon.latitude in minLat..maxLat && latLon.longitude in minLon..maxLon

    fun toMapillaryBboxString(): String = "$minLon,$minLat,$maxLon,$maxLat"
}

/**
 * Local tangent plane coordinate in metres (`east`, `north`, `up`).
 *
 * Note: When stored in `SimObject.position`, the 3-element array uses XR Blocks / glTF Y-up ordering
 * `[east, up, north]`. `toPositionList()` and `fromPositionList()` convert between the two.
 */
data class EnuPoint(
    val east: Double,
    val north: Double,
    val up: Double = 0.0
) {
    fun distanceTo2D(other: EnuPoint): Double =
        hypot(east - other.east, north - other.north)

    fun distanceTo3D(other: EnuPoint): Double {
        val de = east - other.east
        val dn = north - other.north
        val du = up - other.up
        return sqrt(de * de + dn * dn + du * du)
    }

    /**
     * Converts to XR Blocks / `SimObject.position` Y-up `[east, up, north]` list.
     */
    fun toPositionList(): List<Double> = listOf(east, up, north)

    companion object {
        /**
         * Parses an XR Blocks / `SimObject.position` `[east, up, north]` list into an [EnuPoint].
         */
        fun fromPositionList(position: List<Double>): EnuPoint {
            require(position.size >= 2) { "Position list must have at least 2 elements" }
            return if (position.size >= 3) {
                EnuPoint(east = position[0], up = position[1], north = position[2])
            } else {
                EnuPoint(east = position[0], north = position[1], up = 0.0)
            }
        }
    }
}

/**
 * High-precision local East-North-Up (ENU) tangent-plane converter anchored at a WGS84 coordinate.
 *
 * Uses the WGS84 ellipsoid radii of curvature ($M(\phi)$ in meridian and $N(\phi)$ in prime vertical)
 * with second-order latitude curvature correction, achieving `< 0.1 mm` round-trip and geodesic accuracy
 * within `1 km` of the anchor (`< 1 cm` requirement).
 *
 * Pure Kotlin (`kotlin.math.*` only) so it can move directly to KMP `shared/commonMain`.
 */
class LocalEnu(
    val anchorLat: Double,
    val anchorLon: Double,
    val anchorYawDegrees: Double = 0.0
) {
    private val phi0 = anchorLat * DEG_TO_RAD
    private val sinPhi0 = sin(phi0)
    private val cosPhi0 = cos(phi0)

    // Prime vertical radius of curvature N(phi0) and meridian radius of curvature M(phi0)
    private val denom = sqrt(1.0 - WGS84_E2 * sinPhi0 * sinPhi0)
    private val n0 = WGS84_A / denom
    private val m0 = WGS84_A * (1.0 - WGS84_E2) / (denom * denom * denom)

    private val yawRad = anchorYawDegrees * DEG_TO_RAD
    private val cosYaw = cos(yawRad)
    private val sinYaw = sin(yawRad)

    /**
     * Converts a WGS84 [LatLon] into local [EnuPoint] metres relative to the anchor.
     * If `anchorYawDegrees != 0`, rotates world ENU into the anchor's local frame.
     */
    fun latLonToEnu(latLon: LatLon, up: Double = 0.0): EnuPoint =
        latLonToEnu(latLon.latitude, latLon.longitude, up)

    fun latLonToEnu(latitude: Double, longitude: Double, up: Double = 0.0): EnuPoint {
        val dPhi = (latitude - anchorLat) * DEG_TO_RAD
        val dLambda = (longitude - anchorLon) * DEG_TO_RAD
        val phiMid = phi0 + 0.5 * dPhi
        val sinPhiMid = sin(phiMid)
        val cosPhiMid = cos(phiMid)
        val denomMid = sqrt(1.0 - WGS84_E2 * sinPhiMid * sinPhiMid)
        val nMid = WGS84_A / denomMid
        val mMid = WGS84_A * (1.0 - WGS84_E2) / (denomMid * denomMid * denomMid)

        val worldEast = dLambda * nMid * cosPhiMid
        val worldNorth = dPhi * mMid

        if (anchorYawDegrees == 0.0) {
            return EnuPoint(east = worldEast, north = worldNorth, up = up)
        }
        // Rotate world (East, North) into local frame where local +North is at bearing anchorYawDegrees
        val localEast = worldEast * cosYaw - worldNorth * sinYaw
        val localNorth = worldEast * sinYaw + worldNorth * cosYaw
        return EnuPoint(east = localEast, north = localNorth, up = up)
    }

    /**
     * Converts a local [EnuPoint] (in metres) back into WGS84 [LatLon].
     */
    fun enuToLatLon(point: EnuPoint): LatLon =
        enuToLatLon(point.east, point.north)

    fun enuToLatLon(east: Double, north: Double): LatLon {
        val worldEast: Double
        val worldNorth: Double
        if (anchorYawDegrees == 0.0) {
            worldEast = east
            worldNorth = north
        } else {
            worldEast = east * cosYaw + north * sinYaw
            worldNorth = -east * sinYaw + north * cosYaw
        }

        // First-order estimate of dPhi, then refine at midpoint latitude phiMid
        val dPhi0 = worldNorth / m0
        val phiMid = phi0 + 0.5 * dPhi0
        val sinPhiMid = sin(phiMid)
        val cosPhiMid = cos(phiMid)
        val denomMid = sqrt(1.0 - WGS84_E2 * sinPhiMid * sinPhiMid)
        val nMid = WGS84_A / denomMid
        val mMid = WGS84_A * (1.0 - WGS84_E2) / (denomMid * denomMid * denomMid)

        val dPhi = worldNorth / mMid
        val dLambda = if (cosPhiMid > 1e-12) worldEast / (nMid * cosPhiMid) else 0.0

        return LatLon(
            latitude = anchorLat + dPhi * RAD_TO_DEG,
            longitude = anchorLon + dLambda * RAD_TO_DEG
        )
    }

    /**
     * Computes a bounding box around the anchor with the given radius in metres.
     */
    fun boundingBox(radiusMeters: Double): BoundingBox {
        val sw = enuToLatLon(-radiusMeters, -radiusMeters)
        val ne = enuToLatLon(radiusMeters, radiusMeters)
        return BoundingBox(
            minLon = sw.longitude,
            minLat = sw.latitude,
            maxLon = ne.longitude,
            maxLat = ne.latitude
        )
    }

    companion object {
        const val WGS84_A = 6378137.0
        const val WGS84_F = 1.0 / 298.257223563
        const val WGS84_E2 = WGS84_F * (2.0 - WGS84_F)
        const val DEG_TO_RAD = PI / 180.0
        const val RAD_TO_DEG = 180.0 / PI

        /**
         * Geodesic distance in metres between two WGS84 coordinates using local ellipsoidal ENU.
         */
        fun distanceMeters(a: LatLon, b: LatLon): Double {
            val enu = LocalEnu(a.latitude, a.longitude).latLonToEnu(b)
            return hypot(enu.east, enu.north)
        }
    }
}
