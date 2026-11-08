# Step 6 Plan: Indoor Pipeline Evaluation on Apple Hypersim

## Objectives
1. **6a — Verify & Pre-register 6 Hypersim Scenes (`DECISIONS.md`)**:
   - Verify Apple Hypersim (`https://github.com/apple/ml-hypersim`, CC BY-SA 3.0) coordinate conventions, `meters_per_asset_unit`, `depth_meters` vs `position.hdf5`, and NYU40 labels (`semantic_label_descs.csv`).
   - Pre-register 6 public multi-trajectory scenes (`ai_006_006`, `ai_006_002`, `ai_003_010`, `ai_006_009`, `ai_006_010`, `ai_006_001`) before running perception.
   - Fetch only required tone-mapped keyframes and ground-truth HDF5 metadata via HTTP `Range` requests (`<= 15 GB` download cap logged in `budget-ledger.json`).
2. **6b — 6-DoF Pose vs Rotation-Only & Camera Height Sensitivity (`RoomCaptureBuilder`)**:
   - Extend `CaptureFrame` and `RoomCaptureBuilder` with optional `cameraPositionEnu: EnuPoint? = null`.
   - Evaluate 3 geometry configurations on each scene:
     1. 6-DoF pose + real camera height above floor (`upWorld - floorElevation`).
     2. 6-DoF pose + assumed `1.65 m` camera height (`heightAssumptionErrorGrowthMeters`).
     3. Rotation-only (`cameraPositionEnu = null`) on `< 0.5 m` translation subsets.
3. **6c — Ground-Truth Scoring & Cross-Trajectory Recognition (`Step6HypersimEvaluation.kt`)**:
   - Commit NYU40-to-`SimCategory` mapping table (`HypersimNyu40Mapping`).
   - Score predicted landmarks against 9-DOF 3D bounding-box bottom-centres and 5 cm rasterised floor area / IoU.
   - Evaluate `RoomRecognizer` on cross-trajectory revisit scans (`cam_01` vs `cam_00` library + leave-one-out unseen-room trials).
   - Write `perception/samples/hypersim-<scene>.room.json` and `perception/docs/agent/metrics/STEP6-hypersim.json`.
4. **6d — Offline Replay Test & Non-Circular Step 4 Test**:
   - Replace circular synthetic projections in `Step4RoomCaptureAndRecognitionTest.kt` with independent 3D offsets, pixel jitter, and frame misses.
   - Add `Step6HypersimReplayTest.kt` replaying `ai_006_006` offline (`< 3 MB` fixtures, zero images/HDF5 committed).
