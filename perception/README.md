# Soundscape Perception Data Pipeline (`perception/`)

Standalone Kotlin library (`org.scottishtecharmy.soundscape.sim`) that builds spatial-audio simulator environments (`SimEnvironment`) for **Outdoor Street Preview** (from Mapillary 360 panoramas + OpenStreetMap) and **Indoor Room Preview** (from a rotation-only head-turn scan with Ray-Ban Meta smart glasses via `mwdat-camera` and `mwdat-motion`).

---

## 1. Building and Running Tests

All code in `perception/` is isolated as a standalone Gradle project with its own `perception/settings.gradle.kts` so it builds and tests cleanly on JDK 21 without requiring the Android SDK:

```bash
# Run the full offline unit test suite (zero network calls)
./gradlew -p perception test

# Run the CLI (--dry-run plans stations, facade views, and API budget without calling Meta/Gemini)
./gradlew -p perception run --args="build-area --lat 37.79545 --lon -122.39373 --radius 75 --dry-run"
```

---

## 2. Package Architecture & KMP Portability (`shared/commonMain`)

Every package except `sim/jvm` is **pure Kotlin** (zero `java.*` / `javax.*` imports, using `kotlin.math.*`, `kotlinx.serialization`, and `kotlinx.coroutines`) so it can be moved directly into `shared/src/commonMain/kotlin/`:

| Package | Target Source Set | Responsibility |
|---|---|---|
| `org.scottishtecharmy.soundscape.sim.model` | `shared/commonMain` | `SimEnvironment` manifest (superset of XR Blocks `SimulatorEnvironmentManifest`), `SimObject`, `Sign`, `Station`, `Walkable`, `Fingerprint`, `GeoAnchor`, `Provenance`, `Evidence`. |
| `org.scottishtecharmy.soundscape.sim.geo` | `shared/commonMain` | `LocalEnu` (`< 0.1 mm` round-trip within 1 km), `Geohash` (precision-7 cache cells), `Angles`, `Quaternion` (`w, x, y, z` matching `mwdat-motion`), `EquirectangularPano`, `PerspectiveCamera`, `Geometry2D` (multi-ray triangulation, ray-footprint intersection, `groundPlaneDistance`). |
| `org.scottishtecharmy.soundscape.sim.eval` | `shared/commonMain` | `SimEval`: category-aware object Precision/Recall/F1 and median/p90 position error, OCR Character Error Rate (CER) & `fuzzyCompare`, `evaluateRoomRecognition` (`noFalseMatch`), and navigation SPL. |
| `org.scottishtecharmy.soundscape.sim.sources` | `shared/commonMain` | Pure-Kotlin client interfaces (`MapillarySource`, `OverpassSource`, `OvertureClient`, `PanoramaImageSource`, `SegmentationClient`, `Verifier`, `TextReader`, `Embedder`, `CategoryPrecheck`) and `Budget` hard-cap enforcer. |
| `org.scottishtecharmy.soundscape.sim.builders` | `shared/commonMain` | Outdoor `StreetBuilder`, `AreaCache` (`Flow<BuildEvent>`), `StationSelectorAndViewPlanner`, `StreetAggregator`. |
| `org.scottishtecharmy.soundscape.sim.builders.room` | `shared/commonMain` | Indoor `CaptureFrame`, `CaptureCoverage` (18 yaw + 2 up + 2 down slots, gyro motion-blur gate), `RoomCaptureBuilder`, `RoomFingerprint`, and `RoomRecognizer` (with hard room-number mismatch veto and yaw/position re-anchoring). |
| `org.scottishtecharmy.soundscape.sim.jvm` | `shared/androidMain` / JVM | Ktor CIO `HttpGateway` (fixture recording/replay + secret scrubbing), `HttpMapillaryClient`, `HttpOverpassClient`, `JvmPanoramaImageSource`, `MetaSam31Client`, `GeminiFlashClient`, and `Cli`. |

---

## 3. Integration Guide for Soundscape-Android

### 3.1 Feeding `SimEnvironment` Objects into `AudioEngine`

In Soundscape (`shared/src/commonMain/kotlin/org/scottishtecharmy/soundscape/audio/AudioEngine.kt`), spatial audio callouts and beacons take WGS84 `(latitude, longitude)` coordinates via `createBeacon(LngLatAlt(lon, lat), headingOnly)` and `createTextToSpeech(text, AudioType.LOCALIZED, latitude, longitude, heading)`.

Every `SimObject` and `Sign` in an outdoor `SimEnvironment` already carries `latitude` and `longitude` (or can be converted on the fly from `position = [east, up, north]` via `LocalEnu`):

```kotlin
import org.scottishtecharmy.soundscape.audio.AudioEngine
import org.scottishtecharmy.soundscape.audio.AudioType
import org.scottishtecharmy.soundscape.geojsonparser.geojson.LngLatAlt
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import org.scottishtecharmy.soundscape.sim.model.SimCategory
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment
import org.scottishtecharmy.soundscape.sim.model.SimObject

class SimAudioBridge(
    private val env: SimEnvironment,
    private val audioEngine: AudioEngine
) {
    private val localEnu: LocalEnu? = env.geoAnchor?.let {
        LocalEnu(it.latitude, it.longitude, it.yawDegrees)
    }

    fun resolveLatLon(obj: SimObject): Pair<Double, Double> {
        if (obj.latitude != null && obj.longitude != null) {
            return obj.latitude to obj.longitude
        }
        val enu = EnuPoint.fromPositionList(obj.position)
        val anchor = requireNotNull(localEnu) { "SimEnvironment requires geoAnchor when object lat/lon are omitted" }
        val wgs84 = anchor.enuToLatLon(enu)
        return wgs84.latitude to wgs84.longitude
    }

    fun announceObject(obj: SimObject): Long {
        val (lat, lon) = resolveLatLon(obj)
        val label = when (obj.category) {
            SimCategory.DOOR -> obj.linkedVenue?.let { "${it.name} entrance" } ?: obj.name
            SimCategory.SIGN -> obj.text ?: obj.name
            else -> obj.name
        }
        return audioEngine.createTextToSpeech(
            text = label,
            type = AudioType.LOCALIZED,
            latitude = lat,
            longitude = lon,
            heading = obj.yawDegrees ?: Double.NaN
        )
    }

    fun placeBeaconOnDoor(door: SimObject): Long {
        val (lat, lon) = resolveLatLon(door)
        return audioEngine.createBeacon(LngLatAlt(lon, lat), headingOnly = false)
    }
}
```

### 3.2 Continuous Walking Mode in `StreetPreview` via `locationProvider`

Currently, `StreetPreview.go(...)` (`shared/src/commonMain/kotlin/org/scottishtecharmy/soundscape/geoengine/StreetPreview.kt`, lines 86–93 and 167–174) jumps instantaneously from one OSM intersection node to the next by calling `locationProvider.updateLocation(SoundscapeLocation(...))`.

To support **continuous walking preview** (where the user steps smoothly along the sidewalk at `1.2 m/s` along their current heading and hears perception-derived doors, signs, steps, and street furniture pass by in real time):

```kotlin
import org.scottishtecharmy.soundscape.locationprovider.LocationProvider
import org.scottishtecharmy.soundscape.locationprovider.SoundscapeLocation
import org.scottishtecharmy.soundscape.sim.geo.Angles
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import kotlin.math.cos
import kotlin.math.sin

class ContinuousStreetPreviewWalker(
    anchorLat: Double,
    anchorLon: Double,
    private val locationProvider: LocationProvider
) {
    private val localEnu = LocalEnu(anchorLat, anchorLon)
    private var currentPosEnu = EnuPoint(0.0, 0.0, 0.0)

    /**
     * Advances the simulated listener along [headingDegrees] at [speedMetersPerSec] for [dtSeconds]
     * and pushes the updated WGS84 fix into Soundscape's LocationProvider.
     */
    fun step(headingDegrees: Double, speedMetersPerSec: Double = 1.2, dtSeconds: Double = 0.1) {
        val rad = headingDegrees * Angles.DEG_TO_RAD
        val stepDist = speedMetersPerSec * dtSeconds
        currentPosEnu = EnuPoint(
            east = currentPosEnu.east + stepDist * sin(rad),
            north = currentPosEnu.north + stepDist * cos(rad),
            up = 0.0
        )
        val latLon = localEnu.enuToLatLon(currentPosEnu)
        locationProvider.updateLocation(
            SoundscapeLocation(
                latitude = latLon.latitude,
                longitude = latLon.longitude,
                bearing = headingDegrees.toFloat(),
                speed = speedMetersPerSec.toFloat(),
                hasAccuracy = true
            )
        )
    }
}
```

### 3.3 Indoor Room Preview (`kind = ROOM`) via `geoAnchor`

When a user scans an indoor room with Ray-Ban Meta glasses (`RoomCaptureBuilder`), all room landmarks (`DOOR`, `RECEPTION_DESK`, `SIGN`, `STAIRS`, `CHAIR`, and `walkable.floorPolygon`) are localised in metres relative to the user's standing spot `[0, 0, 0]` (`position = [east, up, north]`).

By attaching a coarse `geoAnchor` (e.g. from the last outdoor GPS fix at the building entrance, or a synthetic anchor `(37.79545, -122.39373)` when offline) and applying the `yawOffsetDegrees` recovered by `RoomRecognizer` on revisit:

```kotlin
import org.scottishtecharmy.soundscape.sim.builders.room.RecognitionResult
import org.scottishtecharmy.soundscape.sim.geo.EnuPoint
import org.scottishtecharmy.soundscape.sim.geo.LocalEnu
import org.scottishtecharmy.soundscape.sim.model.SimEnvironment

fun bindIndoorRoomToGeoEngine(
    roomEnv: SimEnvironment,
    revisitMatch: RecognitionResult.Matched? = null
): LocalEnu {
    val anchor = roomEnv.geoAnchor
    val baseLat = anchor?.latitude ?: 37.79545
    val baseLon = anchor?.longitude ?: -122.39373
    // Incorporate any yaw offset recovered by RoomRecognizer when re-entering the room
    val totalYaw = (anchor?.yawDegrees ?: 0.0) + (revisitMatch?.yawOffsetDegrees ?: 0.0)
    return LocalEnu(
        anchorLat = baseLat,
        anchorLon = baseLon,
        anchorYawDegrees = totalYaw
    )
}
```

Because `LocalEnu` maps `[east, up, north]` with `< 0.1 mm` precision into WGS84 `(latitude, longitude)`, Soundscape's existing `GeoEngine`, `CheapRuler`, and `AudioEngine.updateGeometry(...)` work indoors without any changes to the core audio or spatial-indexing pipeline.

---

## 4. GPX Route Export & Phase 2 CLI Commands

`GpxExporter` (`sim/jvm/GpxExporter.kt`) exports any `SimEnvironment` (`--include osm|perceived|all`) into a GPX 1.1 `<wpt>` + `<rte>` file compatible with Soundscape-Android's `parseGpxFile` (`shared/src/commonMain/kotlin/org/scottishtecharmy/soundscape/utils/GpxUtils.kt`):

```bash
# Export perceived doors/signs/steps from a SimEnvironment into a Soundscape GPX route
./gradlew -p perception run --args="export-gpx --in samples/fillmore-2318.street.json --out samples/gpx/fillmore-2318-perceived.gpx --include perceived"

# Replay the 10-area Step 5 outdoor evaluation offline
./gradlew -p perception run --args="run-step5-eval --replay-only"

# Replay the 6-scene Step 6 Apple Hypersim indoor evaluation offline
./gradlew -p perception run --args="run-step6-eval --replay-only"
```

---

## 5. Phase 2 Evaluation Summary (`docs/agent/metrics/`)

- **Outdoor (`STEP5-outdoor.json`, `STEP5_REPORT.md`)**: Evaluated across 10 pre-registered areas (`2` development + `8` held-out across 7 cities & 3 countries). On the 8 held-out areas (`65` visible OSM entrances across `47` stations), perception-only door detection achieved **Precision `0.569` `[0.431, 0.725]`**, **Recall `0.453` `[0.278, 0.723]`**, **F1 `0.504` `[0.339, 0.706]`**, and **Median Error `3.204 m`**. At `2318 Fillmore Street` (Smith-Kettlewell Eye Research Institute, `way/160705972`), where OSM has `0` mapped entrances, perception added `6` entrances (`4` on `way/160705972`) and `4` storefront signs (`samples/FILLMORE.md`).
- **Indoor (`STEP6-hypersim.json`, `STEP6_REPORT.md`)**: Evaluated across 6 pre-registered Apple Hypersim scenes (`ai_006_006`, `ai_006_002`, `ai_003_010`, `ai_006_009`, `ai_006_010`, `ai_006_001`). With 6-DoF pose and real camera height, `RoomCaptureBuilder` achieved **Precision `0.455`**, **Recall `0.730`**, **F1 `0.561`**, **Mean Error `0.615 m`** (`0.472 m` median), and **Mean 5cm Floor IoU `0.333`**. Assuming a fixed `1.65 m` camera height increased mean 3D error by **`+0.192 m`** (`0.807 m`). Cross-trajectory `RoomRecognizer` achieved **`0` false matches** (`noFalseMatch = true`, `100%` abstention on unseen rooms).

