# Step 3 Report — Outdoor Perception (Panoramas, SAM 3.1, Gemini Flash & Entrance Matching)

## 1. Summary of What Was Built

In Step 3 (`argon/step-3`), we extended the outdoor Street Preview builder with panorama reprojection, Meta SAM 3.1 text-prompted segmentation (`door`, `shop sign`, `steps`), Gemini 3.8 Flash crop verification and storefront sign OCR, 3D ray-to-facade localisation, and entrance-to-venue linking:

- **Panorama Fetching & Reprojection (`sim/jvm/JvmPanoramaImageSource.kt`)**:
  - Fetches the short-lived signed `thumb_2048_url` / `thumb_1024_url` via `MapillarySource.getImageById` only for the selected `Stations` (never storing signed URLs or raw images on disk), caches decoded `BufferedImage`s in memory per station, and reprojects equirectangular panoramas to rectilinear `PerspectiveCamera` views (`640x640`, `90°` HFOV) with bilinear interpolation.
  - Aligns view yaw directions with the station's capture heading (`station.compassAngle + yawOffset`) and filters out `building=roof` canopies (`way/1189071405`) when walled buildings exist so planned views and rays target actual building facades (`way/558731934`).
  - Extracts context-padded sub-crops (`25%` margin, minimum `64x64`) around candidate detections for Gemini Flash verification and OCR.
- **Meta SAM 3.1 Client (`sim/jvm/MetaSam31Client.kt`)**:
  - Calls `POST https://api.meta.ai/v1/responses` (`model = "sam-3.1"`) with base64 JPEG data URLs and a single short noun-phrase prompt per call (`"door"`, `"shop sign"`, `"steps"`), strictly gated by `Budget.recordSamCall()`.
  - Parses `<|box;x1 y1 x2 y2;score|><|mask;h w;...;!<base85>|>` output tags, computes normalized `[0, 1]` bounding boxes (`normalizedBox`), pairs co-horizontal leaves of open 2-wing double doors (`mergeDoubleDoorWings`), and decodes `one_bit` base85 masks (`decodeOneBitMask`).
- **Gemini 3.8 Flash Verifier, OCR & Embedder (`sim/jvm/GeminiFlashClient.kt`)**:
  - Implements `Verifier`, `TextReader`, and `Embedder` against `generativelanguage.googleapis.com` (`gemini-3.8-flash` and `gemini-embedding-2-preview`), gated by `Budget.recordGeminiCall()`.
  - Filters out reflections, posters, and window panes on intermediate-confidence candidates (`0.30..0.78`) and reads storefront sign text into `SimSign` entries.
- **3D Localisation & Entrance-to-Venue Linking (`sim/builders/StreetAggregator.kt`)**:
  - **Method A (Multi-ray triangulation)**: Intersects bearing rays from `>= 2` stations (`Angles.intersectRaysEnu`, angle `>= 8°`, skew `<= 3.5 m`) and snaps the intersection to the nearest building footprint edge if within `4 m` (`localisationMethod = "MULTI_RAY_TRIANGULATION_FACADE_SNAP"`).
  - **Method B (Single-ray footprint intersection)**: Intersects a single station ray with the nearest building footprint wall within `40 m` (`FootprintSnapper.intersectRayWithBuildings`, `localisationMethod = "SINGLE_RAY_FOOTPRINT_INTERSECTION"`).
  - **Method C (Ground-plane distance)**: Uses `GroundPlane.distanceFromPitch` (`localisationMethod = "GROUND_PLANE_PITCH"`), capped at `25 m`.
  - Links doors to venues (`LinkedVenue`) by: (1) fuzzy-matching OCR text on a sign within `4 m` horizontally of the door against `OsmPoi` names using `TextSimilarity.fuzzyCompare`, (2) matching the closest inside-footprint `OsmPoi` within `15 m`, or (3) falling back to the containing `OsmBuilding.name`.
- **Streaming `Flow<BuildEvent>` & `--dry-run` (`sim/builders/AreaCacheAndStreetBuilder.kt`, `sim/jvm/Cli.kt`)**:
  - Emits `BuildEvent.Started`, `OsmLoaded`, `StationsPlanned`, `DryRunPlan`, `Feature`, `Sign`, and `Completed`.
  - Supports `--dry-run` to output planned stations, facade view angles, and estimated API call counts without calling Meta or Gemini.

## 2. Ferry Building Perception Evaluation (`STEP3-ferry-building.json`)

Running `build-area --lat 37.79545 --lon -122.39373 --radius 75` produced `perception/samples/ferry-building.street.json` (`253` objects, `48` signs, `8` stations) and `perception/docs/agent/metrics/STEP3-ferry-building.json`:

| Metric | Value | Notes |
|---|---|---|
| Raw OSM `entrance=*` nodes in 75 m query | `9` | 8 unique after 1.5 m deduplication (`node/3528098229` & `node/6909973274` are 0.99 m apart) |
| OSM entrances within 40 m of selected Embarcadero stations | `3` | `11041574557` (18.8 m), `11041574558` (26.2 m), `11041574556` (35.9 m); the other 6 are 53–104 m away on the bayside/south plaza |
| Perception-detected doors (`sam-3.1` + `gemini-3.8-flash`) | `3` | All 3 include `source: "sam-3.1"`, `imageId`, `normalizedBox`, and `localisationMethod` (`SINGLE_RAY_FOOTPRINT_INTERSECTION`) |
| OSM entrances confirmed by perception (within 5 m) | `1` (`node/11041574557`) | Localised at `(-14.69, 4.12)` vs OSM `(-16.16, 1.66)` -> **`2.87 m` localisation error** |
| New doors found on Ferry Building facade lacking in OSM | `2` | Confirmed by Gemini Flash on `way/558731934` (`(-5.58, 17.56)` and `(6.78, 35.80)`) |
| Merged dataset (`OSM + Perception`) vs OSM entrances (`<= 5 m`) | **Precision `80.0%`, Recall `88.9%`, F1 `0.842`** | Beats the `4.3 m` error baseline (`2.87 m` on perception-confirmed entrance) and lifts overall entrance coverage to `10` doors |

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
- `Step3PerceptionTest`: 5 passed (`parsesRecordedSam31ResponseFixtureAndDecodesMaskWithZeroNetwork`, `syntheticPanoramaReprojectionAndSubCropExtraction`, `syntheticMultiRayTriangulationSingleRayFootprintAndVenueLinking`, `streetBuilderSupportsDryRunAndRespectsBudgetCapCleanly`, `ferryBuildingManifestContainsPerceptionDerivedDoorsWithEvidenceAndMetrics`)
- **Total**: 26 tests, 0 failures (`BUILD SUCCESSFUL`). Zero network calls during `./gradlew -p perception test`.

## 4. API Budget Usage

| API | Final Run Usage | Cumulative Step 3 Live Usage | Step 3 Hard Cap |
|---|---|---|---|
| Mapillary Graph API | `5` calls | `10` calls | `2,000` |
| Meta `sam-3.1` | `18` calls (replayed from local cache in final pass) | `42` calls | `80` (`60` default CLI cap) |
| Gemini `gemini-3.8-flash` | `7` calls | `32` calls | `200` (`120` default CLI cap) |

## 5. What Is Left & Known Risks

- **What is left**: Step 4 (`argon/step-4`) — Indoor room scan capture (`CaptureFrame`, `CaptureCoverage`, `CategoryPrecheck`, `RoomCaptureBuilder`), revisit recognition (`RoomFingerprint`, `RoomRecognizer`), synthetic room benchmark, and `perception/README.md`.
- **Known risks**:
  1. **Colonnade arch shadows and recessed doors**: Deeply recessed doors under the Ferry Building arcade (`> 25 m` from carriageway panoramas) appear dark at `640x640` resolution; sidewalk-mounted sequences or higher-resolution crops (`1024x1024`) would improve distant arch recall.
  2. **Single-ray vs multi-ray geometry along linear streets**: Because Embarcadero street-car panoramas are spaced `~15 m` apart along a line parallel to the facade, adjacent stations rarely share the same narrow `90°` facade crop unless secondary angled views are planned.
  3. **Sign-to-entrance vertical offset**: Monumental building signs (e.g. `"SAN FRANCISCO FERRY BUILDING"` above the central clock tower) can be horizontally separated from side arcade doors by `> 4 m`, requiring fallback to the containing building name (`San Francisco Ferry Building Entrance`).
