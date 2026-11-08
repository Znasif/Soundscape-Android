package org.scottishtecharmy.soundscape.sim.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimGeoTest {

    @Test
    fun localEnuRoundTripAndAccuracyWithinOneCmAtOneKm() {
        val anchor = LocalEnu(anchorLat = 37.79545, anchorLon = -122.39373)

        // Test points up to 1 km in East, North, and diagonal directions
        val testPoints = listOf(
            EnuPoint(east = 0.0, north = 0.0, up = 0.0),
            EnuPoint(east = 1000.0, north = 0.0, up = 0.0),
            EnuPoint(east = 0.0, north = 1000.0, up = 0.0),
            EnuPoint(east = -707.10678, north = 707.10678, up = 2.5),
            EnuPoint(east = 600.0, north = -800.0, up = 0.0)
        )

        for (pt in testPoints) {
            val latLon = anchor.enuToLatLon(pt)
            val recovered = anchor.latLonToEnu(latLon, up = pt.up)
            val errMeters = pt.distanceTo2D(recovered)
            assertTrue(errMeters < 0.001, "Expected round-trip error < 1 mm (< 1 cm), got $errMeters m for $pt")
        }

        // Also verify rotated frame with non-zero anchorYawDegrees
        val rotated = LocalEnu(anchorLat = 37.79545, anchorLon = -122.39373, anchorYawDegrees = 42.0)
        val localPt = EnuPoint(east = 15.0, north = -25.0, up = 1.2)
        val wgs = rotated.enuToLatLon(localPt)
        val back = rotated.latLonToEnu(wgs, up = 1.2)
        assertTrue(localPt.distanceTo3D(back) < 1e-4)
    }

    @Test
    fun bearingsAndAnglesHandleWrapAround() {
        assertEquals(0.0, Angles.normalizeBearing(360.0), 1e-9)
        assertEquals(350.0, Angles.normalizeBearing(-10.0), 1e-9)
        assertEquals(20.0, Angles.angleDifference(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Angles.angleDifference(10.0, 350.0), 1e-9)
        assertEquals(20.0, Angles.absAngleDifference(350.0, 10.0), 1e-9)

        val mean = Angles.circularMeanDegrees(listOf(355.0, 5.0, 0.0))
        assertTrue(Angles.absAngleDifference(0.0, mean) < 1e-6)
    }

    @Test
    fun equirectangularPanoramaPixelAndSphericalRayRoundTrip() {
        val width = 2048
        val height = 1024
        val compass = 199.7

        // Center pixel should point straight along compassAngle at horizon (elevation 0)
        val centerRay = EquirectangularPano.pixelToSpherical(1024.0, 512.0, width, height, compass)
        assertEquals(compass, centerRay.bearingDegrees, 1e-6)
        assertEquals(0.0, centerRay.elevationDegrees, 1e-6)

        val testRays = listOf(
            SphericalRay(bearingDegrees = 199.7, elevationDegrees = 0.0),
            SphericalRay(bearingDegrees = 15.0, elevationDegrees = -18.5),
            SphericalRay(bearingDegrees = 359.2, elevationDegrees = 25.0)
        )
        for (ray in testRays) {
            val px = EquirectangularPano.sphericalToPixel(ray.bearingDegrees, ray.elevationDegrees, width, height, compass)
            val back = EquirectangularPano.pixelToSpherical(px.u, px.v, width, height, compass)
            assertTrue(Angles.absAngleDifference(ray.bearingDegrees, back.bearingDegrees) < 1e-6)
            assertEquals(ray.elevationDegrees, back.elevationDegrees, 1e-6)
        }
    }

    @Test
    fun perspectiveCameraPixelToRayAndRayToPixelRoundTrip() {
        val cam = PerspectiveCamera(
            yawDegrees = 205.0,
            pitchDegrees = -5.0,
            hFovDegrees = 90.0,
            width = 640,
            height = 640
        )

        // Optical center must match camera yaw & pitch
        val centerRay = cam.pixelToRay(320.0, 320.0)
        assertTrue(Angles.absAngleDifference(205.0, centerRay.bearingDegrees) < 1e-6)
        assertEquals(-5.0, centerRay.elevationDegrees, 1e-6)

        // Arbitrary pixel round-trip
        val px = PixelCoord(145.0, 368.0)
        val ray = cam.pixelToRay(px.u, px.v)
        val recoveredPx = cam.rayToPixel(ray)
        assertNotNull(recoveredPx)
        assertEquals(px.u, recoveredPx.u, 1e-6)
        assertEquals(px.v, recoveredPx.v, 1e-6)

        // Ray behind camera returns null
        val behindRay = SphericalRay(bearingDegrees = 25.0, elevationDegrees = 0.0)
        assertNull(cam.rayToPixel(behindRay))
    }

    @Test
    fun rayIntersectionAndLeastSquaresTriangulationWithConditioning() {
        val target = EnuPoint(east = 10.0, north = 20.0)
        val s1 = EnuPoint(east = 0.0, north = 0.0)
        val s2 = EnuPoint(east = 20.0, north = 0.0)
        val s3 = EnuPoint(east = 10.0, north = -5.0)

        val r1 = Ray2D(s1, Angles.bearingBetweenEnu(s1, target))
        val r2 = Ray2D(s2, Angles.bearingBetweenEnu(s2, target))
        val r3 = Ray2D(s3, Angles.bearingBetweenEnu(s3, target))

        val twoRay = Geometry2D.intersectTwoRays(r1, r2)
        assertNotNull(twoRay)
        assertTrue(twoRay.point.distanceTo2D(target) < 1e-6)

        val threeRay = Geometry2D.triangulateRays(listOf(r1, r2, r3))
        assertNotNull(threeRay)
        assertTrue(threeRay.point.distanceTo2D(target) < 1e-6)
        assertTrue(threeRay.rmsResidualMeters < 1e-6)

        // Near-parallel rays (1 degree difference) must be rejected by conditioning / parallax check
        val parallel1 = Ray2D(EnuPoint(0.0, 0.0), 45.0)
        val parallel2 = Ray2D(EnuPoint(0.1, 0.0), 45.5)
        assertNull(Geometry2D.intersectTwoRays(parallel1, parallel2))
        assertNull(Geometry2D.triangulateRays(listOf(parallel1, parallel2, Ray2D(EnuPoint(0.2, 0.0), 45.8))))
    }

    @Test
    fun rayToBuildingFootprintIntersectionAndPointSnapping() {
        // Rectangular building from East [10..30], North [15..25]
        val footprint = listOf(
            EnuPoint(10.0, 15.0), // edge 0: South facade (10,15) -> (30,15)
            EnuPoint(30.0, 15.0), // edge 1: East facade (30,15) -> (30,25)
            EnuPoint(30.0, 25.0), // edge 2: North facade (30,25) -> (10,25)
            EnuPoint(10.0, 25.0)  // edge 3: West facade (10,25) -> (10,15)
        )

        // Ray from (20, 0) pointing straight North (bearing 0) hits South facade at (20, 15) on edge 0
        val ray = Ray2D(EnuPoint(20.0, 0.0), bearingDegrees = 0.0)
        val hit = Geometry2D.intersectRayWithPolygon(ray, footprint)
        assertNotNull(hit)
        assertEquals(15.0, hit.distanceMeters, 1e-6)
        assertEquals(20.0, hit.point.east, 1e-6)
        assertEquals(15.0, hit.point.north, 1e-6)
        assertEquals(0, hit.edgeIndex)
        assertEquals(180.0, hit.facadeNormalBearingDegrees, 1e-6)

        // Snap a point at (22, 13.2) (1.8m South of South facade) within maxDistance 5.0m
        val snap = Geometry2D.snapPointToPolygonEdge(EnuPoint(22.0, 13.2), footprint, maxDistanceMeters = 5.0)
        assertNotNull(snap)
        assertEquals(22.0, snap.snappedPoint.east, 1e-6)
        assertEquals(15.0, snap.snappedPoint.north, 1e-6)
        assertEquals(1.8, snap.distanceMeters, 1e-6)
        assertEquals(0, snap.edgeIndex)

        // Point 8m away should not snap when maxDistanceMeters = 5.0m
        assertNull(Geometry2D.snapPointToPolygonEdge(EnuPoint(22.0, 7.0), footprint, maxDistanceMeters = 5.0))
    }

    @Test
    fun groundPlaneDistanceAndHorizonGuard() {
        // At h = 1.65m and pitchBelowHorizon = 45 deg, d = 1.65m
        val d45 = Geometry2D.groundPlaneDistance(cameraHeightMeters = 1.65, pitchBelowHorizonDegrees = 45.0)
        assertNotNull(d45)
        assertEquals(1.65, d45, 1e-6)

        // Near or above horizon (< 4.0 deg) must return null
        assertNull(Geometry2D.groundPlaneDistance(cameraHeightMeters = 1.65, pitchBelowHorizonDegrees = 2.0))
        assertNull(Geometry2D.groundPlaneDistance(cameraHeightMeters = 1.65, pitchBelowHorizonDegrees = 0.0))
        assertNull(Geometry2D.groundPlaneDistance(cameraHeightMeters = 1.65, pitchBelowHorizonDegrees = -10.0))
    }

    @Test
    fun quaternionYawPitchRollAndCameraRayToWorld() {
        val q = Quaternion.fromYawPitchRoll(yawDegrees = 120.0, pitchDegrees = -15.0, rollDegrees = 8.0)
        val euler = q.toYawPitchRoll()
        assertTrue(Angles.absAngleDifference(120.0, euler.yawDegrees) < 1e-6)
        assertEquals(-15.0, euler.pitchDegrees, 1e-6)
        assertEquals(8.0, euler.rollDegrees, 1e-6)

        // Optical axis (0, 1, 0) rotated into world must have bearing 120 deg and elevation -15 deg
        val worldRay = q.cameraRayToWorld(Vec3(0.0, 1.0, 0.0))
        assertTrue(Angles.absAngleDifference(120.0, worldRay.bearingDegrees) < 1e-6)
        assertEquals(-15.0, worldRay.elevationDegrees, 1e-6)
    }

    @Test
    fun geohashEncodeDecodeAndEightNeighbours() {
        val lat = 37.79545
        val lon = -122.39373
        val hash = Geohash.encode(lat, lon, precision = 7)
        assertEquals(7, hash.length)
        assertEquals("9q8znb7", hash)

        val bounds = Geohash.decodeBounds(hash)
        assertTrue(bounds.contains(LatLon(lat, lon)))

        val center = Geohash.decode(hash)
        assertTrue(abs(center.latitude - lat) < 0.002)
        assertTrue(abs(center.longitude - lon) < 0.002)

        val neighbours = Geohash.neighbours(hash)
        assertEquals(8, neighbours.size)
        assertEquals(8, neighbours.toSet().size)
        assertTrue(neighbours.none { it == hash })
        assertTrue(neighbours.all { it.length == 7 })
    }
}
