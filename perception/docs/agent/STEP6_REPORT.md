# Step 6 Report: Indoor Perception Evaluation on Apple Hypersim

## 1. What Was Built

### 6a — Hypersim Verification, Pre-Registration & Partial HTTP Range Fetch (`DECISIONS.md` D-14, D-15, D-16)
- Verified Apple Hypersim (`https://github.com/apple/ml-hypersim`, CC BY-SA 3.0) conventions from `README.md`, `semantic_label_descs.csv`, `metadata_camera_parameters.csv`, and `metadata_scene.csv`:
  - **Licence**: CC BY-SA 3.0; people and prominent logos are excluded in the public release (`74,619` images across `461` scenes).
  - **Units**: Asset coordinates (`position.hdf5`, `mesh_objects_sii.hdf5`, `camera_keyframe_positions.hdf5`) are multiplied by `meters_per_asset_unit` (`0.0254` for inch scenes `ai_006_*`, `0.01` for cm scene `ai_003_010`).
  - **Camera pose & intrinsics**: `1024x768` images with `hFovDegrees = 60.0°` (`settings_camera_fov = pi/3` rad); `camera_keyframe_orientations.hdf5` maps camera-space `[+X right, +Y up, -Z look]` to world-space `[+X East, +Y North, +Z Up]`.
  - **Depth vs Position**: `depth_meters.hdf5` stores Euclidean distance to the optical centre, whereas `position.hdf5` stores exact 3D world coordinates per pixel (`x, y, z` in asset units).
- Pre-registered 6 public multi-trajectory scenes (`cam_00` and `cam_01`) prior to running perception (`D-15`):
  1. `ai_006_006` (Office waiting area)
  2. `ai_006_002` (Restaurant)
  3. `ai_003_010` (Living room)
  4. `ai_006_009` (Living room with desk)
  5. `ai_006_010` (Kitchen)
  6. `ai_006_001` (Asian living room)
- Fetched only the required tone-mapped keyframes and ground-truth HDF5 metadata from `https://docs-assets.developer.apple.com/ml-research/datasets/hypersim/v1/scenes/<scene>.zip` using partial HTTP `Range` requests (`30,827,601` bytes = `29.40 MB` total across all 6 scenes, logged in `perception/docs/agent/metrics/budget-ledger.json` against the `15 GB` cap).

### 6b — 6-DoF Pose vs Rotation-Only & Camera Height Sensitivity (`CaptureFrameAndCoverage.kt`, `RoomCaptureBuilder.kt`)
- Extended `CaptureFrame` with `cameraPositionEnu: EnuPoint? = null` (defaulting to `null` for rotation-only glasses mode).
- Updated `RoomCaptureBuilder` so that when `cameraPositionEnu` is provided:
  - Single-frame ground-plane projections originate from each keyframe's `(east, north)` position (`POSE_GROUND_PLANE_PITCH`).
  - Overlapping sightings from translated keyframes (`>= 0.35 m` baseline) use least-squares multi-view ray triangulation (`MULTI_VIEW_TRIANGULATION`).
  - Floor boundary samples are binned into 36 angular sectors around the interior sample mean.
- Evaluated three geometry configurations across the 6 scenes using the exact same `sam-3.1` 2D detections:
  1. **`pose_6dof_real_height`**: 6-DoF camera pose + true camera height above the floor (`upWorld - floorElevation`, mean `1.073 m – 1.855 m`).
  2. **`pose_6dof_assumed_165m`**: 6-DoF camera pose + fixed assumed `1.65 m` camera height (`cameraHeightError = |1.65 - realHeight|`).
  3. **`rotation_only_subset_lt_05m`**: Rotation-only (`cameraPositionEnu = null`) on trajectory subsets with `< 0.5 m` translation spread (`0.228 m – 0.427 m`).

### 6c — Ground-Truth Scoring & Cross-Trajectory Recognition (`Step6HypersimEvaluation.kt`, `RoomRecognizer.kt`)
- Committed `HypersimNyu40Mapping` (`2 -> FLOOR`, `5 -> CHAIR`, `6 -> SOFA`, `7 -> TABLE`, `8 -> DOOR`, `9 -> WINDOW`, `12 -> COUNTER`, `14 -> DESK`, `30 -> SIGN`).
- Scored predicted room objects against visible 9-DOF 3D bounding-box bottom-centres (`63` ground-truth objects across the 6 scenes) and evaluated reconstructed walkable floor polygons on a `5 cm` (`0.05 m`) 2D raster grid.
- Evaluated `RoomRecognizer` on 12 cross-trajectory trials (`6` positive `cam_01` revisit scans rotated by `+36.0°` matched against the 6-room `cam_00` library + `6` leave-one-out unseen-room trials).
- Generated `perception/samples/hypersim-<scene>.room.json` for all 6 scenes and `perception/docs/agent/metrics/STEP6-hypersim.json`.

### 6d — Offline Replay Test & Non-Circular Step 4 Test (`Step6HypersimReplayTest.kt`, `Step4RoomCaptureAndRecognitionTest.kt`)
- Updated `Step4RoomCaptureAndRecognitionTest.kt` so `FakeRoomSegmentationClient` applies independent 3D position offsets (`0.08–0.15 m`), pixel-level bounding-box jitter (`2–5 px`), and deterministic frame misses (`1` in `7` sightings dropped) instead of inverting the exact ground-truth projection.
- Recorded `sam-3.1` and `gemini-3.8-flash` fixtures for `ai_006_006` into `perception/src/test/resources/fixtures/hypersim-ai_006_006/` (`79 KB` total, `< 3 MB`, zero images/HDF5 committed) and added `Step6HypersimReplayTest.kt` verifying bit-for-bit reproduction of `STEP6-hypersim.json` metrics with zero live network calls.

---

## 2. Evaluation Results (`perception/docs/agent/metrics/STEP6-hypersim.json`)

### Aggregate Comparison Across Geometry Configurations (6 Scenes, 2,000 Bootstrap Resamples)

| Configuration | Total Pred | Total Truth | TP | Precision (95% CI) | Recall (95% CI) | F1 (95% CI) | Mean Error (m) | Median Error (m) | p90 Error (m) | Mean 5cm Floor IoU | Mean Floor Rel Area Error |
|---|---:|---:|---:|---|---|---|---:|---:|---:|---:|---:|
| **1. 6-DoF Pose + Real Camera Height** | 101 | 63 | 46 | **0.455** `[0.375, 0.545]` | **0.730** `[0.600, 0.831]` | **0.561** `[0.466, 0.638]` | **0.615 m** | **0.472 m** | **1.282 m** | **0.333** | **0.491** |
| **2. 6-DoF Pose + Assumed 1.65 m Height** | 103 | 63 | 46 | **0.447** `[0.378, 0.511]` | **0.730** `[0.596, 0.818]` | **0.554** `[0.468, 0.610]` | **0.807 m** | **0.576 m** | **1.854 m** | **0.211** | **0.593** |
| **3. Rotation-Only (`< 0.5 m` Subset)** | 28 | 38 | 19 | **0.679** `[0.583, 0.900]` | **0.500** `[0.272, 0.622]` | **0.576** `[0.378, 0.703]` | **0.768 m** | **0.636 m** | **1.348 m** | **0.003** | **0.887** |

- **Camera Height Error Growth**: Assuming a fixed `1.65 m` camera height increases mean 3D localisation error by **`+0.192 m`** (`0.615 m -> 0.807 m`, and p90 error from `1.282 m -> 1.854 m`), with the largest degradation occurring on low/seated camera trajectories (`ai_003_010` where true camera height is `1.073 m`, `heightError = 0.577 m`, causing mean error to grow from `0.624 m` to `1.124 m` and 5 cm floor IoU to drop from `0.419` to `0.029`).
- **Rotation-Only (`< 0.5 m` Translation Spread) vs 6-DoF**: On `< 0.5 m` translation subsets (`0.228 m – 0.427 m` spread, 3 frames covering a limited angular sector), rotation-only (`cameraPositionEnu = null`) achieves `0.768 m` mean object error (`0.636 m` median error) on visible objects within the subset's field of view, but cannot reconstruct the full-room floor polygon (`meanFloorIou5cm = 0.003`) because the 3 subset frames only observe a narrow angular sector of the room boundary.

### Per-Scene Breakdown (`pose_6dof_real_height` vs `pose_6dof_assumed_165m`)

| Scene ID | Room Type | Mean Cam Height (m) | Height Diff vs 1.65m | Pred / Truth / TP | Precision / Recall / F1 | Mean Err (Real Height) | Mean Err (1.65m Assumed) | 5cm Floor IoU (Real) | 5cm Floor IoU (1.65m) |
|---|---|---:|---:|---|---:|---:|---:|---:|---:|
| `ai_006_006` | Office Waiting Area | `1.543 m` | `0.107 m` | `13 / 8 / 5` | `0.385 / 0.625 / 0.476` | `0.656 m` | `0.663 m` | `0.290` | `0.295` |
| `ai_006_002` | Restaurant | `1.492 m` | `0.158 m` | `36 / 20 / 16` | `0.444 / 0.800 / 0.571` | `0.592 m` | `0.779 m` | `0.565` | `0.568` |
| `ai_003_010` | Living Room | `1.073 m` | `0.577 m` | `17 / 12 / 9` | `0.529 / 0.750 / 0.621` | `0.624 m` | `1.124 m` | `0.419` | `0.029` |
| `ai_006_009` | Living Room w/ Desk | `1.148 m` | `0.502 m` | `12 / 9 / 5` | `0.417 / 0.556 / 0.476` | `0.592 m` | `0.816 m` | `0.339` | `0.068` |
| `ai_006_010` | Kitchen | `1.855 m` | `0.205 m` | `14 / 8 / 6` | `0.429 / 0.750 / 0.545` | `0.546 m` | `0.574 m` | `0.453` | `0.305` |
| `ai_006_001` | Asian Living Room | `1.482 m` | `0.168 m` | `9 / 6 / 5` | `0.556 / 0.833 / 0.667` | `0.758 m` | `0.727 m` | `0.000` | `0.000` |

### Cross-Trajectory Room Recognition (`cam_01` vs `cam_00` Library, 12 Trials)

| Metric | Value | Notes |
|---|---|---|
| **Total Trials** | `12` | `6` cross-trajectory revisit scans (`cam_01` w/ `+36°` yaw offset) + `6` leave-one-out unseen-room trials |
| **False Matches** | **`0` (`0.0%`)** | `noFalseMatch = true`; zero false matches across both seen and unseen rooms |
| **Correct Abstentions (Unseen Rooms)** | **`6 / 6` (`100.0%`)** | All 6 leave-one-out unseen-room queries abstained cleanly |
| **Cross-Trajectory Positive Matches** | `2 / 6` (`33.3%`) | `ai_006_002` matched (`conf = 0.807`, `yawErr = 0.41°`), `ai_003_010` matched (`conf = 0.759`, `yawErr = 32.0°`); remaining 4 textless 2-frame scans conservatively abstained (`conf = 0.561–0.615 < 0.72`) |
| **Mean Yaw Recovery Error (Matched)** | `16.2°` (`0.41°` on `ai_006_002`) | Recovered from `+36.0°` synthetic yaw offset on `cam_01` |

---

## 3. Check Command & Output

```text
$ ./gradlew -p perception test
> Task :checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :compileKotlin UP-TO-DATE
> Task :compileJava NO-SOURCE
> Task :processResources NO-SOURCE
> Task :classes UP-TO-DATE
> Task :jar UP-TO-DATE
> Task :processTestResources UP-TO-DATE
> Task :compileTestKotlin
> Task :compileTestJava NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test

BUILD SUCCESSFUL in 4s
5 actionable tasks: 2 executed, 3 up-to-date
```

---

## 4. Budget Usage (`perception/docs/agent/metrics/budget-ledger.json`)

| Step | Meta `sam-3.1` Calls | Gemini Flash Calls | Mapillary Graph API Calls | Downloaded Bytes |
|---|---:|---:|---:|---:|
| **Steps 0 to 3** | `45` | `35` | `16` | `0` |
| **Step 4** | `0` | `0` | `0` | `0` |
| **Step 5** | `143 / 300` | `184 / 600` | `96 / 5,000` | `0` |
| **Step 6** | **`274 / 300`** | **`72 / 300`** | **`0 / 5,000`** | **`30,827,601 / 16,106,127,360` (`29.40 MB / 15 GB`)** |
| **Cumulative (Steps 0 to 6)** | **`462`** | **`291`** | **`112`** | **`30,827,601` (`29.40 MB`)** |

---

## 5. Known Risks & Limitations
1. **Camera Height Sensitivity (`+0.192 m` mean error growth at `1.65 m`)**: Monocular ground-plane pitch distance $d = h / \tan(\text{pitch})$ scales linearly with $h$. When a user is seated (`~1.1 m` eye height in `ai_003_010` and `ai_006_009`) but the pipeline assumes standing height (`1.65 m`), distances are overestimated by `~45–50%`. Making user height configurable in app settings (and detecting seated vs standing posture) is critical.
2. **Floor Polygon Under-Coverage on Partial Angular Scans**: In `ai_006_001` and in the 3-frame `< 0.5 m` rotation-only subsets, keyframes only cover `< 120°` of the room horizon, leaving un-scanned sectors empty in the 36-sector radial floor polygon builder. Full `360°` guided head-turn coverage (`CaptureCoverage`) is required for reliable floor polygon reconstruction.
3. **Textless Cross-Trajectory Re-Recognition Recall (`33.3%`)**: When a room lacks a text/number sign (`Room 214`) and is revisited from a different 3D trajectory (`cam_01` vs `cam_00`) using only 2 keyframes, our conservative matcher abstains on `4 / 6` rooms (`conf = 0.56–0.62 < 0.72`) to maintain `0%` false matches.
