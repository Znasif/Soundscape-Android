package org.scottishtecharmy.soundscape.sim.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 2D horizontal ray in local ENU coordinates originating at [origin] and pointing along [bearingDegrees].
 */
data class Ray2D(
    val origin: EnuPoint,
    val bearingDegrees: Double
) {
    private val rad = bearingDegrees * PI / 180.0
    val dirEast: Double = sin(rad)
    val dirNorth: Double = cos(rad)

    fun pointAtDistance(distanceMeters: Double): EnuPoint = EnuPoint(
        east = origin.east + dirEast * distanceMeters,
        north = origin.north + dirNorth * distanceMeters,
        up = origin.up
    )
}

/**
 * Result of 2-ray or N-ray triangulation in 2D ENU coordinates.
 */
data class TriangulationResult(
    val point: EnuPoint,
    val rmsResidualMeters: Double,
    val maxParallaxDegrees: Double,
    val conditionNumber: Double
)

/**
 * Result of intersecting a [Ray2D] against a closed building-footprint polygon.
 */
data class PolygonHit(
    val point: EnuPoint,
    val distanceMeters: Double,
    val edgeIndex: Int,
    val edgeStart: EnuPoint,
    val edgeEnd: EnuPoint,
    val facadeNormalBearingDegrees: Double
)

/**
 * Result of snapping a 2D point onto the nearest edge of a building footprint polygon.
 */
data class SnapResult(
    val snappedPoint: EnuPoint,
    val distanceMeters: Double,
    val edgeIndex: Int,
    val edgeStart: EnuPoint,
    val edgeEnd: EnuPoint
)

/**
 * 2D geometric solvers for triangulation, ray-footprint intersection, edge snapping,
 * and monocular ground-plane distance estimation.
 */
object Geometry2D {

    /**
     * Exact 2D ray-ray intersection from 2 stations, rejecting parallel/near-parallel rays
     * (`maxParallax < minParallaxDegrees`), diverging rays (`t1 <= 0` or `t2 <= 0`), or hits beyond [maxDistanceMeters].
     */
    fun intersectTwoRays(
        r1: Ray2D,
        r2: Ray2D,
        minParallaxDegrees: Double = 5.0,
        maxDistanceMeters: Double = 100.0
    ): TriangulationResult? {
        val parallax = Angles.absAngleDifference(r1.bearingDegrees, r2.bearingDegrees)
        if (parallax < minParallaxDegrees || parallax > 180.0 - minParallaxDegrees) {
            return null
        }
        // Solve r1.origin + t1 * d1 = r2.origin + t2 * d2
        // Cross product in 2D: det = d1.east * d2.north - d1.north * d2.east
        val det = r1.dirEast * r2.dirNorth - r1.dirNorth * r2.dirEast
        if (abs(det) < 1e-9) return null

        val de = r2.origin.east - r1.origin.east
        val dn = r2.origin.north - r1.origin.north

        val t1 = (de * r2.dirNorth - dn * r2.dirEast) / det
        val t2 = (de * r1.dirNorth - dn * r1.dirEast) / det

        if (t1 <= 0.0 || t2 <= 0.0 || t1 > maxDistanceMeters || t2 > maxDistanceMeters) {
            return null
        }

        val hit = r1.pointAtDistance(t1)
        val cond = 1.0 / abs(sin(parallax * PI / 180.0)).coerceAtLeast(1e-6)
        return TriangulationResult(
            point = hit,
            rmsResidualMeters = 0.0,
            maxParallaxDegrees = parallax,
            conditionNumber = cond
        )
    }

    /**
     * Least-squares 2D triangulation from `N >= 2` rays minimizing perpendicular squared distance
     * $\sum_i \| (I - d_i d_i^T)(p - o_i) \|^2$.
     *
     * Performs conditioning and geometry checks:
     * 1. Rejects when maximum pairwise angle difference `< minParallaxDegrees`.
     * 2. Rejects when the 2x2 normal matrix condition number $\lambda_{\max} / \lambda_{\min} > maxConditionNumber$.
     * 3. Rejects when the solved point lies behind any camera ray (or fewer than 2 rays point forward to it within [maxDistanceMeters]).
     * 4. Rejects when RMS perpendicular residual exceeds [maxRmsResidualMeters].
     */
    fun triangulateRays(
        rays: List<Ray2D>,
        minParallaxDegrees: Double = 5.0,
        maxConditionNumber: Double = 100.0,
        maxRmsResidualMeters: Double = 5.0,
        maxDistanceMeters: Double = 100.0
    ): TriangulationResult? {
        if (rays.size < 2) return null
        if (rays.size == 2) {
            return intersectTwoRays(rays[0], rays[1], minParallaxDegrees, maxDistanceMeters)
        }

        var maxParallax = 0.0
        for (i in rays.indices) {
            for (j in i + 1 until rays.size) {
                val diff = Angles.absAngleDifference(rays[i].bearingDegrees, rays[j].bearingDegrees)
                val acute = min(diff, 180.0 - diff)
                if (acute > maxParallax) maxParallax = acute
            }
        }
        if (maxParallax < minParallaxDegrees) return null

        // Assemble 2x2 symmetric matrix A = sum(I - d_i d_i^T) and rhs b = sum((I - d_i d_i^T) o_i)
        var a00 = 0.0
        var a01 = 0.0
        var a11 = 0.0
        var b0 = 0.0
        var b1 = 0.0

        for (r in rays) {
            val m00 = 1.0 - r.dirEast * r.dirEast
            val m01 = -r.dirEast * r.dirNorth
            val m11 = 1.0 - r.dirNorth * r.dirNorth
            a00 += m00
            a01 += m01
            a11 += m11
            b0 += m00 * r.origin.east + m01 * r.origin.north
            b1 += m01 * r.origin.east + m11 * r.origin.north
        }

        val trace = a00 + a11
        val det = a00 * a11 - a01 * a01
        if (det <= 1e-9 || trace <= 1e-9) return null

        val disc = sqrt(max(0.0, trace * trace - 4.0 * det))
        val lambdaMax = 0.5 * (trace + disc)
        val lambdaMin = 0.5 * (trace - disc)
        if (lambdaMin <= 1e-9) return null
        val conditionNumber = lambdaMax / lambdaMin
        if (conditionNumber > maxConditionNumber) return null

        val east = (a11 * b0 - a01 * b1) / det
        val north = (-a01 * b0 + a00 * b1) / det
        val solved = EnuPoint(east = east, north = north, up = 0.0)

        // Verify forward projection along rays and compute perpendicular RMS residual
        var sumSqErr = 0.0
        var validForwardCount = 0
        for (r in rays) {
            val de = solved.east - r.origin.east
            val dn = solved.north - r.origin.north
            val forwardDist = de * r.dirEast + dn * r.dirNorth
            if (forwardDist <= 0.0 || forwardDist > maxDistanceMeters) {
                return null
            }
            validForwardCount++
            val perpDist = abs(de * (-r.dirNorth) + dn * r.dirEast)
            sumSqErr += perpDist * perpDist
        }
        if (validForwardCount < 2) return null

        val rms = sqrt(sumSqErr / rays.size.toDouble())
        if (rms > maxRmsResidualMeters) return null

        return TriangulationResult(
            point = solved,
            rmsResidualMeters = rms,
            maxParallaxDegrees = maxParallax,
            conditionNumber = conditionNumber
        )
    }

    /**
     * Intersects a [Ray2D] with a closed building-footprint polygon (`polygonVertices`),
     * returning the closest positive hit within [maxDistanceMeters] and which facade edge was hit.
     *
     * Note: If the last vertex does not repeat the first vertex, the closing edge `(last -> first)`
     * is automatically included.
     */
    fun intersectRayWithPolygon(
        ray: Ray2D,
        polygonVertices: List<EnuPoint>,
        maxDistanceMeters: Double = 100.0
    ): PolygonHit? {
        if (polygonVertices.size < 3) return null
        val edges = buildPolygonEdges(polygonVertices)
        var bestHit: PolygonHit? = null

        for ((edgeIdx, pair) in edges.withIndex()) {
            val (p1, p2) = pair
            val segEast = p2.east - p1.east
            val segNorth = p2.north - p1.north
            val segLen = hypot(segEast, segNorth)
            if (segLen < 1e-9) continue

            // Solve ray.origin + t * ray.dir = p1 + u * (p2 - p1)
            // t * d - u * s = p1 - o
            val denom = ray.dirEast * (-segNorth) - ray.dirNorth * (-segEast)
            if (abs(denom) < 1e-12) continue

            val rhsEast = p1.east - ray.origin.east
            val rhsNorth = p1.north - ray.origin.north

            val t = (rhsEast * (-segNorth) - rhsNorth * (-segEast)) / denom
            val u = (ray.dirEast * rhsNorth - ray.dirNorth * rhsEast) / denom

            if (t > 1e-6 && t <= maxDistanceMeters && u in -1e-9..(1.0 + 1e-9)) {
                if (bestHit == null || t < bestHit.distanceMeters) {
                    val hitPoint = ray.pointAtDistance(t)
                    // Choose facade normal opposing the incoming ray
                    val n1East = -segNorth / segLen
                    val n1North = segEast / segLen
                    val dot = n1East * ray.dirEast + n1North * ray.dirNorth
                    val outEast = if (dot <= 0.0) n1East else -n1East
                    val outNorth = if (dot <= 0.0) n1North else -n1North
                    val normalBearing = SphericalRay.fromVectorEnu(outEast, outNorth, 0.0).bearingDegrees

                    bestHit = PolygonHit(
                        point = hitPoint,
                        distanceMeters = t,
                        edgeIndex = edgeIdx,
                        edgeStart = p1,
                        edgeEnd = p2,
                        facadeNormalBearingDegrees = normalBearing
                    )
                }
            }
        }
        return bestHit
    }

    /**
     * Snaps [point] onto the nearest edge of [polygonVertices] if the perpendicular/segment distance
     * is at most [maxDistanceMeters]. Returns `null` if no edge is within [maxDistanceMeters].
     */
    fun snapPointToPolygonEdge(
        point: EnuPoint,
        polygonVertices: List<EnuPoint>,
        maxDistanceMeters: Double
    ): SnapResult? {
        if (polygonVertices.size < 2) return null
        val edges = buildPolygonEdges(polygonVertices)
        var best: SnapResult? = null

        for ((edgeIdx, pair) in edges.withIndex()) {
            val (p1, p2) = pair
            val dx = p2.east - p1.east
            val dy = p2.north - p1.north
            val lenSq = dx * dx + dy * dy
            val u = if (lenSq > 1e-12) {
                (((point.east - p1.east) * dx + (point.north - p1.north) * dy) / lenSq).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val proj = EnuPoint(
                east = p1.east + u * dx,
                north = p1.north + u * dy,
                up = point.up
            )
            val dist = point.distanceTo2D(proj)
            if (dist <= maxDistanceMeters && (best == null || dist < best.distanceMeters)) {
                best = SnapResult(
                    snappedPoint = proj,
                    distanceMeters = dist,
                    edgeIndex = edgeIdx,
                    edgeStart = p1,
                    edgeEnd = p2
                )
            }
        }
        return best
    }

    /**
     * Projects [point] onto the line segment `[segStart, segEnd]` and returns a [SnapResult]
     * with the closest point on the segment and its 2D distance.
     */
    fun snapPointToSegment(
        point: EnuPoint,
        segStart: EnuPoint,
        segEnd: EnuPoint
    ): SnapResult {
        val dx = segEnd.east - segStart.east
        val dy = segEnd.north - segStart.north
        val lenSq = dx * dx + dy * dy
        val u = if (lenSq > 1e-12) {
            (((point.east - segStart.east) * dx + (point.north - segStart.north) * dy) / lenSq).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val proj = EnuPoint(
            east = segStart.east + u * dx,
            north = segStart.north + u * dy,
            up = point.up
        )
        return SnapResult(
            snappedPoint = proj,
            distanceMeters = point.distanceTo2D(proj),
            edgeIndex = 0,
            edgeStart = segStart,
            edgeEnd = segEnd
        )
    }

    /**
     * Computes horizontal ground-plane distance $d = h / \tan(\text{pitchBelowHorizon})$ from camera height $h$
     * and pitch angle below the horizon (positive down from horizon, in degrees).
     *
     * Guards against near-horizon or above-horizon rays:
     * - Returns `null` if `pitchBelowHorizonDegrees < minPitchDegrees` (default `4.0°`) or `>= 89.9°`.
     * - Returns `null` if $d > \text{maxDistanceMeters}$.
     */
    fun groundPlaneDistance(
        cameraHeightMeters: Double,
        pitchBelowHorizonDegrees: Double,
        minPitchDegrees: Double = 4.0,
        maxDistanceMeters: Double = 25.0
    ): Double? {
        require(cameraHeightMeters > 0.0) { "cameraHeightMeters must be positive" }
        if (pitchBelowHorizonDegrees < minPitchDegrees || pitchBelowHorizonDegrees >= 89.9) {
            return null
        }
        val rad = pitchBelowHorizonDegrees * PI / 180.0
        val dist = cameraHeightMeters / tan(rad)
        return if (dist in 0.0..maxDistanceMeters) dist else null
    }

    /**
     * Projects a floor-contact ray `(bearingDegrees, elevationDegrees)` (where `elevationDegrees < 0` is below horizon)
     * onto the horizontal floor plane `up = 0` relative to `origin`.
     */
    fun groundPlanePoint(
        origin: EnuPoint = EnuPoint(0.0, 0.0, 0.0),
        bearingDegrees: Double,
        elevationDegrees: Double,
        cameraHeightMeters: Double = 1.65,
        minPitchBelowHorizonDegrees: Double = 4.0,
        maxDistanceMeters: Double = 25.0
    ): EnuPoint? {
        val pitchBelow = -elevationDegrees
        val dist = groundPlaneDistance(
            cameraHeightMeters = cameraHeightMeters,
            pitchBelowHorizonDegrees = pitchBelow,
            minPitchDegrees = minPitchBelowHorizonDegrees,
            maxDistanceMeters = maxDistanceMeters
        ) ?: return null
        return Ray2D(origin.copy(up = 0.0), bearingDegrees).pointAtDistance(dist)
    }

    /**
     * Computes the unsigned 2D area of a polygon in square metres using the Shoelace formula.
     */
    fun polygonArea2D(vertices: List<EnuPoint>): Double {
        if (vertices.size < 3) return 0.0
        var sum = 0.0
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            sum += a.east * b.north - b.east * a.north
        }
        return 0.5 * abs(sum)
    }

    private fun buildPolygonEdges(vertices: List<EnuPoint>): List<Pair<EnuPoint, EnuPoint>> {
        val result = mutableListOf<Pair<EnuPoint, EnuPoint>>()
        for (i in 0 until vertices.size - 1) {
            result.add(vertices[i] to vertices[i + 1])
        }
        val first = vertices.first()
        val last = vertices.last()
        if (first.distanceTo2D(last) > 1e-6 && vertices.size >= 3) {
            result.add(last to first)
        }
        return result
    }
}
