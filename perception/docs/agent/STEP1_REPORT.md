# Step 1 Report: `perception/` Build, `sim/model`, `sim/geo`, `sim/eval`

## 1. What Was Built
1. **Standalone Gradle Build (`perception/`)**:
   - `perception/settings.gradle.kts` (`rootProject.name = "perception"`) and `perception/build.gradle.kts` (Kotlin/JVM `2.4.21`, `kotlin("plugin.serialization")`, Ktor `3.5.1`, `kotlinx-coroutines` `1.11.0`, `junit-jupiter` `6.1.2`, JDK 21 toolchain, `excludeTags("live")`).
   - Verified `./gradlew -p perception test` resolves `perception/settings.gradle.kts` without invoking the root Android build.
2. **`sim/model` (`SimEnvironment.kt`)**:
   - Implemented `SimEnvironment`, `SimObject`, `SimCategory`, `EnvironmentKind`, `Sign`, `Station`, `GeoAnchor`, `Provenance`, `Evidence`, `VerifierVerdict`, `LinkedVenue`, `Walkable`, `BuildingFootprint`, `Fingerprint`, `ObjectBearingSignature`, `SimUserLocation`, and `SimLightingConfig`.
   - Configured `SimJson` with `ignoreUnknownKeys = true`, `encodeDefaults = false`, `explicitNulls = false`, and `prettyPrint = true` so XR Blocks manifests (`DefaultManifests.ts`) deserialize cleanly and default/null fields are omitted on write.
3. **`sim/geo` (`LocalEnu.kt`, `AnglesAndQuaternion.kt`, `Projections.kt`, `Geometry2D.kt`, `Geohash.kt`)**:
   - Pure Kotlin (`kotlin.math.*` only, zero `java.*`/`javax.*` imports).
   - `LocalEnu`: WGS84 ellipsoid curvature radii ($M(\phi), N(\phi)$) with midpoint refinement (`< 0.1 mm` error at `1 km`, well inside the `< 1 cm` requirement) plus `anchorYawDegrees` frame rotation.
   - `Angles` & `Quaternion`: scalar-first `(w, x, y, z)` matching Meta Wearables DAT `MotionSample`, intrinsic `Z(-yaw) * X(pitch) * Y(roll)` Euler conversion, and `cameraRayToWorld`.
   - `EquirectangularPano` & `PerspectiveCamera`: bidirectional pixel <-> `(bearing, elevation)` projections and direct equirectangular-to-perspective sampling coordinates.
   - `Geometry2D`: 2-ray exact intersection and N-ray least-squares triangulation with conditioning (`conditionNumber`, `minParallaxDegrees`, forward distance, and RMS residual checks), ray-to-building-footprint polygon intersection (first hit + facade edge + outward normal), point-to-footprint-edge snapping within `maxDistanceMeters`, and monocular ground-plane distance `d = h / tan(pitch_below_horizon)` with near/above-horizon guard.
   - `Geohash`: precision-7 base32 encode/decode (`37.79545, -122.39373` -> `"9q8znb7"`) and 8 surrounding neighbours.
4. **`sim/eval` (`SimEval.kt`)**:
   - Pure Kotlin (`zero java.*`/`javax.*` imports).
   - Greedy distance-threshold category matching (`evaluateObjects`): Precision, Recall, F1, mean/median/p90 localisation error.
   - Sign OCR text evaluation (`characterErrorRate`, `evaluateTexts`) and Damerau-Levenshtein `fuzzyCompare` matching Soundscape's `StringExt.kt`.
   - Room re-recognition evaluation (`evaluateRoomRecognition`): confusion counts + hard `noFalseMatch` safety flag.
   - Navigation episode evaluation (`evaluateNavigation`): Success Rate and SPL.

## 2. Check Command & Output
Command:
```bash
./gradlew -p perception test
```
Output:
```text
> Task :checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :compileKotlin UP-TO-DATE
> Task :compileJava NO-SOURCE
> Task :processResources NO-SOURCE
> Task :classes UP-TO-DATE
> Task :jar UP-TO-DATE
> Task :processTestResources NO-SOURCE
> Task :compileTestKotlin
> Task :compileTestJava NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test

BUILD SUCCESSFUL in 4s
4 actionable tasks: 2 executed, 2 up-to-date
```
All 16 unit tests in `SimEnvironmentTest`, `SimGeoTest`, and `SimEvalTest` passed, with zero network calls and zero modifications outside `perception/`.

## 3. Budget Usage (Step 1)
| API | Used in Step 1 | Step Cap |
|---|---|---|
| Mapillary Graph API | `0` | `2,000` |
| Meta `sam-3.1` | `0` | `80` |
| Gemini Flash | `0` | `200` |

## 4. What Is Left
- **Step 2 (`argon/step-2`)**: `Budget`, `HttpGateway`, `MapillaryClient`, `OverpassClient`, `OvertureClient` (interface + TODO), `StationSelector`, `ViewPlanner`, `StreetAggregator`, `StreetBuilder`, `AreaCache`, CLI `build-area`, and Ferry Building sample `perception/samples/ferry-building.street.json`.
- **Step 3 (`argon/step-3`)**: Panorama reprojection, `MetaSam31Client`, `GeminiFlashClient` (`Verifier` + `TextReader`), multi-ray / facade localisation, venue linking, and `perception/docs/agent/metrics/STEP3-ferry-building.json`.
- **Step 4 (`argon/step-4`)**: Indoor head-turn room capture (`RoomCaptureBuilder`), floor polygon extraction, `RoomFingerprint`, `RoomRecognizer`, synthetic room test suite, and `perception/README.md`.

## 5. Known Risks
1. **Overpass API Rate Limits during Live Area Builds**: Mitigated in Step 2 via `HttpGateway` fixture recording/replay and exponential backoff.
2. **GitHub Proxy PAT Scope (`403 Forbidden` on `Znasif/Soundscape-Android`)**: Commits and branch refs (`argon/step-1`) are preserved in `/workspace/Soundscape-Android`.
