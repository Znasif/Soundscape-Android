# Soundscape-Android Perception Pipeline — Executive Summary (Steps 0–7)

## 1. Architecture & Step-by-Step Summary (Steps 0 to 7)

The `perception/` standalone Kotlin module implements an end-to-end outdoor ("Street Preview") and indoor ("Indoor Preview") spatial landmark perception pipeline for **Soundscape-Android**:

- **Step 0 (`argon/step-0`, PR #1)**: Established the standalone Gradle module (`perception/`), XR Blocks-compatible `SimEnvironment` JSON manifest schema, `CheapRuler` spherical/planar geometry (`EquirectangularMath`, `GroundPlaneRaycaster`), and the `PerceptionEvaluator` bipartite matching harness.
- **Step 1 (`argon/step-1`, PR #2)**: Built the deterministic OpenStreetMap Overpass parser and `OsmStreetBuilder` that extracts road centerlines, intersections, building footprints, entrances, steps, and amenities into a navigable `StreetGraph` and `SimEnvironment`.
- **Step 2 (`argon/step-2`, PR #3)**: Built the `StreetSimulator` and `RoomSimulator` state machines that walk a user pose (`latitude`/`longitude`/`headingDegrees` or indoor local `[x, y, z]`) and emit spatialized `AudioCue` callouts within an configurable forward fan.
- **Step 3 (`argon/step-3`, PR #4)**: Integrated live **Mapillary Graph API v4** panorama discovery, **Meta `sam-3.1`** open-vocabulary detection (`door`, `entrance`, `sign`, `steps`, `counter`), and **Gemini Flash** OCR/verification, projecting detections onto OSM building wall segments or ground-plane rays (`MapillaryPerceptionBuilder`).
- **Step 4 (`argon/step-4`, PR #5)**: Built the indoor head-turn scan builder (`RoomCaptureBuilder`) for Ray-Ban Meta Smart Glasses (`mwdat-motion` 30 Hz IMU + 720x1280 camera frames) and the training-free two-stage `RoomRecognizer` (chromatic-opponent visual signatures + 360° 1-degree polar landmark constellation + OCR sign verification).
- **Step 5 (`argon/step-5`, PR #6)**: Conducted a 10-area multi-city outdoor evaluation (`fillmore-2318`, `ferry-building`, and 8 held-out areas across 7 cities and 3 countries) with 3 development ablations, implemented the persistent `BudgetLedger`, and built the `GpxExporter` (`-route.gpx` and `-landmarks.gpx`) for Soundscape-Android field testing (`12` GPX files committed in `perception/samples/gpx/`).
- **Step 6 (`argon/step-6`, PR #7)**: Conducted a 6-scene indoor evaluation on real **Apple Hypersim** (`ml-hypersim`) 3D rooms using partial HTTP `Range` requests (`29.40 MB` total downloaded out of `15 GB` cap), live Meta `sam-3.1`, and live Gemini Flash, comparing 6-DoF real height vs assumed `1.65 m` height vs rotation-only `< 0.5 m` subsets and evaluating cross-trajectory `RoomRecognizer` matching (`0` false matches across all cross-trajectory and unseen-room trials).
- **Step 7 (`argon/step-7`, PR #8)**: Completed a comprehensive honesty and code portability audit (zero `java.*`/`javax.*` imports in portable packages, zero compiler warnings, dated audit notes on Phase 1 reports), authored the 2318 Fillmore Street field test plan (`FIELD_TEST_PLAN.md`) and verification schema (`perception/field/fillmore-2026-11-08-example.json`), and finalized project documentation.

---

## 2. Validated vs. Unvalidated Capabilities

| Capability / Claim | Status | Empirical Evidence & Limitations |
|---|---|---|
| **Portable KMP Architecture (`sim/model`, `sim/geo`, `sim/eval`, `sim/builders`, `sim/sources`)** | **Validated** | Enforced by `Step7AuditAndFieldPlanTest`: zero `java.*`/`javax.*` imports in portable packages; epoch milliseconds (`Long`) used throughout. |
| **Outdoor OSM + Mapillary + SAM 3.1 + Gemini Fusion (`StreetPreview` & GPX Export)** | **Validated (Offline APIs)** | Evaluated across **10 pre-registered urban areas** (`STEP5-outdoor.json`): `25` OSM entrances (`100%` retention), `+64` net-new perception landmarks on held-out areas (`+320%` enrichment), `95.0%` building-facade ray hit rate (`3.09 m` median ray distance). Validated for GPX 1.1 schema compliance (`12` files in `samples/gpx/`). |
| **Outdoor Ground-Truth Precision / Recall against Physical Tape-Measure Survey** | **Unvalidated (Pending Field Walk)** | OSM serves as a baseline rather than an exhaustive ground truth for signs/steps. Physical precision/recall requires executing `FIELD_TEST_PLAN.md` at **2318 Fillmore Street**. |
| **Indoor 3D Landmark Localization & Floor Polygon (`RoomCaptureBuilder` on Hypersim)** | **Validated (3D Mesh Benchmark)** | Evaluated on **6 Apple Hypersim rooms** (`STEP6-hypersim.json`): `F1 = 0.561` (`Precision = 0.455`, `Recall = 0.730`, `meanError = 0.615 m`, `median = 0.472 m`) with real height; `meanError = 0.807 m` (`+0.192 m`) under assumed `1.65 m` height; `F1 = 0.576` (`Precision = 0.679`, `median = 0.636 m`) on rotation-only (`< 0.5 m` translation) subset. |
| **Indoor `RoomRecognizer` Zero False Positive Abstention & Yaw Alignment** | **Validated (Hypersim Cross-Trajectory)** | `0` false matches across `6` `cam_01` cross-trajectory queries (`+36°` yaw offset) and `6` leave-one-out unseen-room trials (`noFalseMatch = true`, `100%` abstention on unseen rooms, `0.41°` yaw alignment error on `ai_006_002`). |
| **Live Ray-Ban Meta Smart Glasses (`mwdat-motion`) Hardware Capture & Magnetometer Drift** | **Unvalidated (Pending Hardware Test)** | IMU fusion and frame synchronization are tested on synthetic and Hypersim trajectories, not yet on live `mwdat-motion` Bluetooth streams from physical glasses. |

---

## 3. Cumulative Live API & Bandwidth Ledger (`budget-ledger.json`)

| Step | Meta `sam-3.1` Calls | Gemini Flash Calls | Mapillary Graph API Calls | Downloaded Bytes |
|---|---:|---:|---:|---:|
| **Steps 0–3** | 45 | 35 | 16 | 0 B |
| **Step 4** | 0 | 0 | 0 | 0 B |
| **Step 5** | 143 | 184 | 96 | 0 B |
| **Step 6** | 274 | 72 | 0 | 30,827,601 B (29.40 MB) |
| **Step 7** | 0 | 0 | 0 | 0 B |
| **Total (Steps 0–7)** | **462** | **291** | **112** | **30,827,601 B (29.40 MB)** |

---

## 4. Next 3 Engineering Tasks for Soundscape-Android Integration

1. **Move Portable `sim/*` Packages into `shared/src/commonMain`:**
   Copy `org.scottishtecharmy.soundscape.sim.{model,geo,eval,builders,sources}` into `shared/src/commonMain/kotlin/` and wire `StreetSimulator` into `Soundscape-Android`'s existing `StreetPreview.kt` and `GeoEngine.kt` so `SimObject` entrances, signs, and steps are surfaced alongside MVT tile features in the spatial audio engine (`AudioEngine.kt`).
2. **Execute the 2318 Fillmore Street Field Walk (`FIELD_TEST_PLAN.md`):**
   Load `perception/samples/gpx/fillmore-2318-route.gpx` and `fillmore-2318-landmarks.gpx` onto an Android test phone at **2318 Fillmore Street**, record physical true/false positives and missed landmarks into `perception/field/fillmore-<date>.json`, and calibrate confidence thresholds (`sam-3.1` and Gemini verification) against real pedestrian observations.
3. **Wire `mwdat-motion` SDK Frame + IMU Collector in `app/`:**
   Implement an Android service in `app/` that subscribes to the Meta Wearables Device Access Toolkit (`mwdat-motion`) 30 Hz fused orientation quaternion stream and 720x1280 camera frames, pairs each keyframe with its nearest timestamped yaw/pitch/roll (`CapturedFrame`), and invokes `RoomCaptureBuilder` + `RoomRecognizer` for live indoor room preview.
