# Step 4 Report — Indoor Room Scan (`RoomCaptureBuilder`), Revisit Recognition (`RoomRecognizer`) & Integration Guide

## 1. Summary of What Was Built

In Step 4 (`argon/step-4`), we implemented the complete indoor perception pipeline for rotation-only head-turn scans captured with Ray-Ban Meta smart glasses (`mwdat-camera` + `mwdat-motion`), revisit recognition with strict false-match prevention, and the Soundscape-Android integration guide (`perception/README.md`):

- **Capture Input & Spherical Coverage (`sim/builders/room/CaptureFrameAndCoverage.kt`)**:
  - `CaptureFrame`: encapsulates `frameId`, `timestampNs`, `imageBytes`, scalar-first `orientation: Quaternion` (`w, x, y, z`), `gyroMagnitudeRadPerSec`, `hFovDegrees` (`68.0°`), `imageWidth` (`720`), `imageHeight` (`1280`), and `cameraHeightMeters` (`1.65 m`).
  - `CaptureCoverage`: divides the sphere into `18` horizon yaw slots (`20°` each) + `2` upward pitch slots (`pitch >= +12°`) + `2` downward pitch slots (`pitch <= -12°`).
  - **Motion-blur gate**: rejects frames with `gyroMagnitudeRadPerSec > maxGyroRadPerSec` (default `0.8 rad/s`) so blurry frames are never sent to `SegmentationClient`, and keeps the sharpest frame per slot.
  - **Structured audio guidance**: reports uncovered slots as `MissingCoverageSlot`s with clock-face directions (`1..12 o'clock`) and spoken TTS hints (e.g., `"Turn slowly toward 3 o'clock (90 degrees)"`, `"Tilt head slightly down toward 3 o'clock to capture the floor boundary"`).
- **Room Category Precheck (`sim/sources/Sources.kt`, `sim/jvm/GeminiFlashClient.kt`)**:
  - `CategoryPrecheck.detectPresentCategories`: inspects 4–6 spread keyframes once with `gemini-3.8-flash` to identify which categories from `{door, desk, reception desk, counter, chair, table, sofa, sign, stairs, lift, window}` are present so `sam-3.1` is only invoked for visible categories plus `floor`.
- **Single-Position Room Builder (`sim/builders/room/RoomCaptureBuilder.kt`)**:
  - Projects floor-contacting object bottom-centres `(bottomCenterX, bottomY)` through `PerspectiveCamera.pixelToRay` and `Geometry2D.groundPlanePoint` (`d = h / tan(pitch_below_horizon)`, guarding near-horizon rays `< 4°`) into local ENU `[east, 0.0, north]`.
  - Extracts upper-edge floor-wall boundary points from `FLOOR` masks across 36 angular sectors and simplifies with Ramer-Douglas-Peucker (`epsilon = 0.12 m`) into `walkable.floorPolygon`.
  - Localises elevated wall objects (`SIGN`, `WINDOW`) by intersecting their bearing ray with the reconstructed `walkable.floorPolygon` and computing `up = cameraHeightMeters + d * tan(elevation)`.
  - Merges multi-frame detections within `0.65 m` (or `10°` bearing and `25%` distance), verifies low-confidence crops via `Verifier`, reads sign text via `TextReader`, and computes 3–5 keyframe embeddings via `Embedder`.
- **Room Revisit Recognition & Re-Anchoring (`sim/builders/room/RoomRecognizer.kt`)**:
  - `RoomFingerprint`: compact serializable signature storing `normalizedTexts`, extracted alphanumeric `identifierTokens` (e.g. `"214"`), `frameEmbeddings`, and `landmarks`.
  - **Hard discriminative room-number veto (Decision D-09)**: if both query and stored scans contain room/door number tokens and their token sets are disjoint (e.g. `"Room 216"` vs `"Room 214"`), the match is immediately vetoed (`RecognitionResult.Abstained`) even if visual appearance and geometry are identical.
  - Recovers the user's **yaw offset** (`yawOffsetDegrees`) using circular mean refinement on static architectural landmarks (`DOOR`, `SIGN`, `RECEPTION_DESK`, `STAIRS`), ignoring movable furniture (`CHAIR`), and estimates the user's **2D standing position** (`EnuPoint`) via backward-ray triangulation (`Geometry2D.triangulateRays`).
- **Soundscape Integration Guide (`perception/README.md`)**:
  - Documents with concrete Kotlin code how `SimEnvironment` feeds `AudioEngine` (`createBeacon` / `createTextToSpeech`), how continuous walking mode updates `LocationProvider` in `StreetPreview`, how indoor `ROOM` environments convert local `[east, up, north]` coordinates through `geoAnchor` (`LocalEnu`), and which packages move directly to `shared/commonMain`.

## 2. Synthetic Room Benchmark Results (`STEP4-room-214.json`)

Evaluated on a synthetic `6 m x 8 m` rectangular room (`48.0 m²` true floor area) with 6 landmarks (`DOOR`, `RECEPTION_DESK`, `SIGN` `"Room 214"`, `STAIRS`, and 2 `CHAIR`s), scripted 18-slot head sweep + pitch slots + blurry fast-turn frame, pixel jitter, and missed detections:

| Metric | Target | Result | Status |
|---|---|---|---|
| Median object position error | `< 0.50 m` | **`0.0123 m` (`1.2 cm`)** (mean `0.0132 m`, p90 `0.0191 m`) | PASS |
| `walkable.floorPolygon` area accuracy | Within `20%` of `48.0 m²` | **`47.28 m²` (`1.50%` error)** | PASS |
| Duplicate objects after cross-frame merge | `0` duplicates | **`0` duplicates** (`6/6` TP, `0` FP, `0` FN, `F1 = 1.0`) | PASS |
| Revisit `"Room 214"` with `+40°` yaw offset & moved chairs | Match + yaw within `10°` | **Matched (`room-214`)**, recovered yaw **`39.89°` (`0.11°` error)** | PASS |
| Near-duplicate `"Room 216"` (identical layout & visual embedding) | `0` false matches (abstain) | **Abstained** (vetoed by identifier mismatch `216` vs `214`) | PASS |
| Unseen new room | Abstain | **Abstained** (`noFalseMatch = true`, `correctAbstentions = 2/2`) | PASS |

## 3. Verification Check & Output

Command executed:
```bash
./gradlew -p perception test
```
Output summary:
- `SimEnvironmentTest`: 3 passed
- `SimGeoTest`: 9 passed
- `SimEvalTest`: 4 passed
- `Step2StreetBuilderTest`: 5 passed
- `Step3PerceptionTest`: 5 passed
- `Step4RoomCaptureAndRecognitionTest`: 3 passed (`captureCoverageRejectsBlurryFramesAndReportsMissingSlotsForGuidance`, `syntheticRoomScanReconstructsObjectsWithinHalfMeterAndFloorPolygonWithin20Percent`, `roomRecognizerMatchesRevisitWith40DegYawOffsetAndMovedChairsAndRejectsRoom216AndNewRoom`)
- **Total**: 29 tests, 0 failures (`BUILD SUCCESSFUL`).
- Portability check: `rg "import java" perception/src/main/kotlin/org/scottishtecharmy/soundscape/sim/{model,geo,eval,builders,sources}` confirmed **zero** `java.*`/`javax.*` imports in all portable packages.

## 4. API Budget Usage (Step 4)

| API | Step 4 Live Usage | Step 4 Hard Cap |
|---|---|---|
| Mapillary Graph API | `0` calls | `2,000` |
| Meta `sam-3.1` | `0` calls | `80` |
| Gemini `gemini-3.8-flash` | `0` calls | `200` |

## 5. Known Risks & Limitations

1. **Monocular ground-plane assumption (`d = h / tan(pitch)`)**: Assumes a flat horizontal floor at distance `h = 1.65 m` below the glasses; split-level floors, ramps, or user height variations (`±10 cm`) introduce a proportional scale factor (`±6%`) on range estimates.
2. **Floor occlusion in crowded rooms**: If furniture completely occludes the floor-to-wall baseboard across several adjacent yaw slots, `walkable.floorPolygon` will trace the front edge of the furniture (the walkable free-space boundary) rather than the structural wall behind it.
3. **Placard-free identical rooms**: If two rooms are visually and structurally identical and neither has any distinguishing text or placard, `RoomRecognizer` relies on visual embeddings and landmark bearings; adding Wi-Fi BSSID / BLE beacon hints to `GeoAnchor` would further disambiguate placard-free twin rooms.
