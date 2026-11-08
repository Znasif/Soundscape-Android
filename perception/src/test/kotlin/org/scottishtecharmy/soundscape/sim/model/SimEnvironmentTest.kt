package org.scottishtecharmy.soundscape.sim.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimEnvironmentTest {

    @Test
    fun loadsXrBlocksDefaultManifestStructureAndIgnoresUnknownFields() {
        // Mirrors /workspace/ref/xrblocks/DefaultManifests.ts ("Apartment Room") plus an unknown field
        val xrBlocksJson = """
            {
              "name": "Apartment Room",
              "scenePath": "environments/apartment_room/scene.gltf",
              "videoPath": "environments/apartment_room/preview.mp4",
              "scenePlanesPath": "environments/apartment_room/planes.json",
              "navMeshPath": "environments/apartment_room/navmesh.glb",
              "position": [0, 0, 0],
              "quaternion": [0, 0, 0, 1],
              "scale": [1, 1, 1],
              "unknownFutureXrBlocksField": "ignored-safely",
              "locations": {
                "Entryway": {
                  "position": [-1.2, 1.6, 2.4],
                  "rotation": [0, 90, 0]
                }
              },
              "objects": [
                {
                  "name": "Bowl",
                  "scenePath": "objects/bowl/model.gltf",
                  "position": [0.4, 0.85, -1.1],
                  "quaternion": [0, 0, 0, 1],
                  "scale": [1, 1, 1]
                }
              ],
              "lighting": {
                "ambientIntensity": 0.6,
                "directionalIntensity": 1.2,
                "directionalPosition": [2.0, 5.0, 3.0]
              }
            }
        """.trimIndent()

        val env = SimEnvironment.fromJson(xrBlocksJson)
        assertEquals("Apartment Room", env.name)
        assertEquals("environments/apartment_room/scene.gltf", env.scenePath)
        assertEquals(listOf(0.0, 0.0, 0.0), env.position)
        assertNotNull(env.locations)
        assertEquals(listOf(-1.2, 1.6, 2.4), env.locations["Entryway"]?.position)
        assertEquals(1, env.objects?.size)
        assertEquals("Bowl", env.objects?.first()?.name)
        assertEquals(0.6, env.lighting?.ambientIntensity)

        // Verify omitted defaults on re-serialization
        val encoded = env.toJson()
        assertFalse(encoded.contains("unknownFutureXrBlocksField"))
        assertFalse(encoded.contains("\"fingerprint\""))
    }

    @Test
    fun roundTripsFullStreetManifest() {
        val street = SimEnvironment(
            name = "Ferry Building Street Preview",
            kind = EnvironmentKind.STREET,
            geoAnchor = GeoAnchor(latitude = 37.79545, longitude = -122.39373, yawDegrees = 0.0),
            provenance = Provenance(
                createdAtEpochMs = 1730000000000L,
                builderVersion = "0.1.0",
                sources = listOf("OSM", "MAPILLARY", "SAM_3_1", "GEMINI_FLASH"),
                newestCaptureEpochMs = 1695600049000L,
                geohash7 = "9q8yyk8"
            ),
            attribution = SimEnvironment.DEFAULT_ATTRIBUTION,
            objects = listOf(
                SimObject(
                    id = "door-1",
                    name = "Blue Bottle Coffee Entrance",
                    category = SimCategory.DOOR,
                    subCategory = "main",
                    position = listOf(12.5, 0.0, -8.3),
                    latitude = 37.795375,
                    longitude = -122.393588,
                    confidence = 0.91,
                    sources = listOf("OSM", "SAM_3_1"),
                    text = "Blue Bottle Coffee",
                    linkedVenue = LinkedVenue(
                        osmId = "node/12345",
                        name = "Blue Bottle Coffee",
                        category = "cafe",
                        matchScore = 0.98
                    ),
                    evidence = listOf(
                        Evidence(
                            stationId = "1104309667442811",
                            bearingDegrees = 201.3,
                            elevationDegrees = -6.2,
                            box = listOf(145, 260, 158, 368),
                            detectorConfidence = 0.704,
                            verifierResult = VerifierVerdict.YES
                        )
                    )
                )
            ),
            signs = listOf(
                Sign(
                    id = "sign-1",
                    text = "Blue Bottle Coffee",
                    confidence = 0.95,
                    position = listOf(12.4, 2.2, -8.3),
                    latitude = 37.795375,
                    longitude = -122.393589,
                    stationId = "1104309667442811",
                    bearingDegrees = 201.0,
                    linkedDoorId = "door-1"
                )
            ),
            stations = listOf(
                Station(
                    id = "1104309667442811",
                    sequenceId = "seq-1",
                    latitude = 37.795778,
                    longitude = -122.393158,
                    compassAngle = 199.7,
                    capturedAtEpochMs = 1684026247000L,
                    isPano = true,
                    position = listOf(50.3, 0.0, 36.4)
                )
            ),
            walkable = Walkable(
                buildingFootprints = listOf(
                    BuildingFootprint(
                        osmId = "way/558731934",
                        name = "San Francisco Ferry Building",
                        verticesEnu = listOf(listOf(0.0, 0.0), listOf(30.0, 0.0), listOf(30.0, 40.0), listOf(0.0, 40.0))
                    )
                )
            )
        )

        val json = street.toJson()
        val decoded = SimEnvironment.fromJson(json)
        assertEquals(street, decoded)
        assertTrue(json.contains("Mapillary"))
        assertTrue(json.contains("OpenStreetMap"))
    }

    @Test
    fun roundTripsFullRoomManifest() {
        val room = SimEnvironment(
            name = "Conference Room 214",
            kind = EnvironmentKind.ROOM,
            geoAnchor = GeoAnchor(latitude = 37.79545, longitude = -122.39373, yawDegrees = 35.0),
            provenance = Provenance(
                createdAtEpochMs = 1730000100000L,
                builderVersion = "0.1.0",
                sources = listOf("RAYBAN_META", "SAM_3_1", "GEMINI_FLASH")
            ),
            objects = listOf(
                SimObject(
                    id = "room-door-1",
                    name = "Room 214 Door",
                    category = SimCategory.DOOR,
                    position = listOf(0.0, 0.0, 3.2),
                    confidence = 0.94,
                    text = "Room 214"
                ),
                SimObject(
                    id = "room-counter-1",
                    name = "Reception Counter",
                    category = SimCategory.COUNTER,
                    position = listOf(2.5, 0.0, 1.0),
                    confidence = 0.88
                )
            ),
            walkable = Walkable(
                floorPolygon = listOf(
                    listOf(-3.0, -3.0),
                    listOf(3.0, -3.0),
                    listOf(3.0, 3.5),
                    listOf(-3.0, 3.5)
                )
            ),
            fingerprint = Fingerprint(
                normalizedTexts = listOf("room 214", "reception"),
                distinctiveTokens = listOf("214"),
                embeddings = listOf(listOf(0.1, 0.2, 0.3, 0.4)),
                objectBearings = listOf(
                    ObjectBearingSignature(
                        objectId = "room-door-1",
                        category = SimCategory.DOOR,
                        bearingDegrees = 0.0,
                        distanceMeters = 3.2,
                        text = "room 214"
                    )
                )
            )
        )

        val json = room.toJson()
        val decoded = SimEnvironment.fromJson(json)
        assertEquals(room, decoded)
    }
}
