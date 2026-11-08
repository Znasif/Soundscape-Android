# Step 0 Plan: Soundscape Perception Data Pipeline (`org.scottishtecharmy.soundscape.sim`)

## 1. Existing Soundscape-Android Architecture Summary

### 1.1 How `StreetPreview` Moves the User (`locationProvider.updateLocation`)
- **State machine (`StreetPreview.kt`)**: `StreetPreview` operates across `OFF -> INITIALIZING -> ON`. When initialized at a user-selected coordinate (`StreetPreviewEnabled`), `GeoEngine.updateStreetPreview(location)` builds a `TileGrid` at zoom 16 (`SlippyMapTile`), finds the nearest `Road` ways, and synthesizes intersection choices (`StreetPreviewChoice(heading, name, road)`).
- **Stepping (`GeoEngine.streetPreviewGo`)**: Choosing a street direction finds the two endpoints of the chosen road segment and picks the endpoint closer to the current heading (`bestIndex`). It then calls:
  ```kotlin
  locationProvider.updateLocation(
      SoundscapeLocation(
          latitude = newLocation.latitude,
          longitude = newLocation.longitude,
          bearing = lastBearing,
          speed = 1.0F,
          hasAccuracy = true
      )
  )
  ```
- **Downstream flow (`LocationProvider.kt` & `GeoEngine.kt`)**: In `StaticLocationProvider` (used during Street Preview), `updateLocation` emits into `mutableLocationFlow`. `GeoEngine.start()` observes `locationProvider.filteredLocationFlow`, calling `gridState.locationUpdate(LngLatAlt(location.longitude, location.latitude), ...)` and `autoCallout.updateLocation(...)`.
- **Implication for Simulator**: Because `StreetPreview` jumps node-to-node between intersections, mid-block doors, steps, and shop signs are skipped unless the user steps continuously along the sidewalk (e.g., 2 m–5 m increments) or transitions into an indoor room frame (`geoAnchor` + local ENU coordinates converted back to WGS84 via `LocalEnu`).

### 1.2 How `AudioEngine` Places Spatial Sounds (`latitude`/`longitude` + Listener Heading)
- **3D HRTF Audio (`AudioEngine.kt`, `NativeAudioEngine.kt`)**: All Soundscape user feedback is spatialized audio:
  - `createBeacon(location: LngLatAlt, headingOnly: Boolean)` places a continuous spatial audio beacon at `(longitude, latitude)`.
  - `createEarcon(asset: String, type: PositionedAudioType, latitude: Double, longitude: Double, heading: Double)` places a short earcon in world coordinates (`PositionedAudioType.LAT_LONG`) or relative to the user (`LOCALIZED`, `RELATIVE`, `STANDARD`).
  - `createTextToSpeech(text: String, type: PositionedAudioType, latitude: Double, longitude: Double, heading: Double)` spatializes spoken callouts at a target's geographic coordinates.
- **Listener pose update (`AudioEngine.updateGeometry`)**: Every 50 ms (`audioEngineUpdateJob` in `GeoEngine.kt`), the engine reads the latest `SoundscapeLocation` and `Orientation` (`HeadTrackingProvider` / device compass) and invokes:
  ```kotlin
  audioEngine.updateGeometry(
      listenerLatitude = location.latitude,
      listenerLongitude = location.longitude,
      listenerHeading = orientation.heading,
      focusGained = ...,
      duckingAllowed = ...,
      proximityNear = ...
  )
  ```
- **Implication for Simulator**: Every `SimObject` in a `SimEnvironment` must carry both local ENU `position` (`[east, up, north]` or `[x, y, z]`) and WGS84 `latitude`/`longitude` (either directly for outdoor objects or anchored via `geoAnchor` + yaw for indoor room scans) so `AudioEngine` can spatialize doors, signs, steps, and counters with zero changes to native audio rendering.

### 1.3 How OSM Entrances Are Matched to Buildings Today (`EntranceMatching.kt`)
- **Tile-space vertex sharing**: `EntranceMatching` runs during MVT / Overpass tile ingestion (`TileGrid`). Every geometry point inside a tile is quantized to `0..4095` tile coordinates and packed into a single integer key `(x shl 12) + y`.
- **Eligible tags**: Only nodes with `entrance` in `{"yes", "main", "staircase", "shop", "secondary", "restaurant", "office", "subway_entrance", "entrance"}` are ingested via `addEntrance`.
- **Two-pass polygon matching (`generateEntrances`)**:
  1. First, matches an entrance node to polygon POIs at the exact same `(x, y)` vertex that also have a matching `layer` tag.
  2. If no layer-matched polygon exists, falls back to any polygon sharing the `(x, y)` vertex.
  3. Only creates a matched `Entrance` if **exactly one** non-entrance POI polygon shares the node (`matchingPois.size == 1`), synthesizing ` EntranceDetails(name, entranceType, poiType, OsmId)` and setting `properties["has_entrances"] = "yes"` on the parent POI.
- **Limitations & Perception Opportunity**:
  - In OSM, most buildings and shops lack `entrance=*` nodes (or have POI nodes floating near the building centroid rather than on the facade polygon vertex).
  - Our perception pipeline detects doors, signs, and steps visually from Mapillary panoramas, intersects or triangulates their rays against OSM building footprints, snaps doors onto the nearest facade edge within `5 m`, reads shop sign text via OCR, and links doors to OSM venues using Soundscape's Damerau-Levenshtein `fuzzyCompare` (`StringExt.kt`).

---

## 2. Primary Source Verification

### 2a. Mapillary API v4
- **Sources**:
  - [Mapillary API v4 Documentation](https://www.mapillary.com/developer/api-documentation) (**Confirmed**)
  - [Mapillary Map Feature Point Classes](https://www.mapillary.com/developer/api-documentation/points) (**Confirmed**)
  - [Mapillary Object Detection Classes](https://www.mapillary.com/developer/api-documentation/detections) (**Confirmed**)
- **Endpoints & Fields**:
  - `GET https://graph.mapillary.com/images?bbox=minLon,minLat,maxLon,maxLat&fields=id,is_pano,compass_angle,captured_at,sequence,geometry,computed_geometry,computed_compass_angle,thumb_256_url,thumb_1024_url,thumb_2048_url,thumb_original_url,width,height,camera_type,quality_score`
    - Constraint: `bbox` area must be `< 0.01` square degrees; `limit` defaults to and caps at `2000`.
    - `is_pano`: boolean (`true` for 360° equirectangular panoramas).
    - `compass_angle` / `computed_compass_angle`: float degrees in `[0, 360)`.
    - `captured_at`: integer epoch milliseconds (`Long`).
    - `sequence`: string sequence ID.
    - `geometry` / `computed_geometry`: GeoJSON `Point` (`{"type":"Point","coordinates":[lon,lat]}`).
    - `thumb_256_url`, `thumb_1024_url`, `thumb_2048_url`, `thumb_original_url`: short-lived signed CDN URLs.
  - `GET https://graph.mapillary.com/map_features?bbox=minLon,minLat,maxLon,maxLat&fields=id,aligned_direction,first_seen_at,last_seen_at,object_value,object_type,geometry,images`
  - `GET https://graph.mapillary.com/:image_id/detections?fields=id,value,geometry,created_at,image` (note: `geometry` is base64-encoded Mapbox Vector Tile polygon in normalized image coordinates).
- **Class Lists (Confirmed Gap)**:
  - `map_features` has **42 point classes** (`object--bench`, `object--trash-can`, `object--bike-rack`, `object--sign--store`, `object--sign--information`, `object--sign--advertisement`, `object--street-light`, `object--traffic-light--pedestrians-*`, `marking--discrete--crosswalk-zebra`, etc.).
  - `detections` has **152 non-sign object classes** plus ~1,500 traffic-sign classes (`construction--flat--crosswalk-plain`, `construction--flat--curb-cut`, `object--ramp`, `object--sign--store`, `object--bench`, `object--trash-can`, `object--bike-rack`, etc.).
  - **Confirmed**: Neither `map_features` nor `detections` includes any `door`, `entrance`, `stairs`, or `steps` class. Visual segmentation (`sam-3.1` + `Verifier`) is required to extract doors and steps from Mapillary panoramas.
- **Rate Limits & Authentication**:
  - Header authentication: `Authorization: OAuth $MAPILLARY_TOKEN` (**Confirmed**).
  - Rate limits: Entity APIs (`graph.mapillary.com/:id`) = `60,000` requests/minute/app; Search APIs (`graph.mapillary.com/images?bbox=...`, `/map_features?bbox=...`) = `10,000` requests/minute/app; Vector Tiles (`tiles.mapillary.com`) = `50,000` requests/day/app (**Confirmed**).

### 2b. Overture Maps
- **Sources**:
  - [Overture Places Schema (`place`)](https://docs.overturemaps.org/schema/reference/places/place/) (**Confirmed**)
  - [Overture Buildings Schema (`building`)](https://docs.overturemaps.org/schema/reference/buildings/building/) (**Confirmed**)
  - [Overture Cloud Data Sources](https://docs.overturemaps.org/getting-data/cloud-sources/) (**Confirmed**)
  - [Overture Blog: Member Spotlight Zephr — Finding the Front Door (2026)](https://overturemaps.org/blog/2026/member-spotlight-zephr-finding-the-front-door/) (**Confirmed**)
  - [Zephr Embedding Tiles Repository](https://github.com/zephr-xyz/embedding-tiles) (**Confirmed**)
- **Schema & Zephr Entrance Contribution**:
  - Current Overture `places/place` schema (`v2.0.0`) provides `id` (GERS UUID), `geometry` (`Point`), `names`, `basic_category`, `taxonomy`, `confidence`, `addresses`, `websites`, `phones`, `brand`, `operating_status`, and `sources`. It does **not** include separate entrance point coordinates or facade bearings in the standard schema (**Confirmed**).
  - Current Overture `buildings/building` schema provides footprint `geometry` (`Polygon`/`MultiPolygon`), `height`, `num_floors`, `facade_color`, `facade_material`, and `roof_*`, with **no** entrance sub-features (**Confirmed**).
  - Zephr contributed relocated place pins (`Point` geometry) for ~50k US restaurants into Overture Places, while publishing `entrance_lat`/`entrance_lon`, `entrance_Confidence`, and `nav_context.facade_bearing` via Zephr's separate MCP API and regional S3 embedding tiles (`zephr-xyz/embedding-tiles`, which currently cover only Boulder, CO and St. Louis, MO — not San Francisco).
- **Fetching a Small Bounding Box Without DuckDB**:
  - Overture distributes multi-gigabyte GeoParquet files on AWS S3 (`s3://overturemaps-us-west-2/release/...`) and Azure Blob Storage indexed by a STAC catalog (`https://stac.overturemaps.org/catalog.json`), plus monolithic inspection PMTiles archives. Reading row-group footers and column chunks via HTTP Range requests in pure Kotlin/JVM without DuckDB or heavy Apache Parquet/Arrow/Hadoop dependencies is impractical for a lightweight mobile library.
  - **Decision**: Define `OvertureClient` as a clean interface in `sim/sources` with a documented `TODO` and decision record in `DECISIONS.md`, and rely on `OverpassClient` + `MapillaryClient` for live outdoor geometry and POI queries.
- **License**:
  - Overture `places` theme: **CDLA-Permissive-2.0** (**Confirmed**).
  - Overture `buildings` theme: **CDLA-Permissive-2.0** overall, with OSM-derived building footprints retaining **ODbL** attribution requirements (**Confirmed**).

### 2c. Meta Model API (`sam-3.1` & Muse)
- **Sources**:
  - [Meta SAM API Overview](https://dev.meta.ai/docs/sam/overview) (**Confirmed**)
  - [Meta SAM Segmenting Guide](https://dev.meta.ai/docs/sam/segmenting) (**Confirmed**)
  - [Meta SAM Reading Segmentation Output](https://dev.meta.ai/docs/sam/reading-segmentation) (**Confirmed**)
  - [Meta Model API Pricing & Rate Limits](https://dev.meta.ai/docs/pricing-rate-limits) (**Confirmed**)
  - [Meta Geographic Use Policy & Model Tiers](https://dev.meta.ai/legal/geographic-use-policy) (**Confirmed**)
- **Endpoint & Authentication**:
  - `POST https://api.meta.ai/v1/responses` with header `Authorization: Bearer $META_API_KEY` and `Content-Type: application/json` (**Confirmed**).
- **Request & Response Format for `sam-3.1`**:
  - Request body:
    ```json
    {
      "model": "sam-3.1",
      "input": [{
        "type": "message",
        "role": "user",
        "content": [
          {"type": "input_text", "text": "door"},
          {"type": "input_image", "image_url": "data:image/jpeg;base64,..."}
        ]
      }],
      "stream": false,
      "metadata": {
        "include_confidence": "true",
        "mask_encoding": "one_bit"
      }
    }
    ```
  - Text prompts must be a **single short noun phrase per request** (e.g., `"door"`, `"shop sign"`, `"steps"`). Multi-concept comma lists in a single text prompt are not supported by SAM 3.1's text encoder (`SAM 3.1` supports either one text concept prompt per call or up to 16 visual box/point/mask object prompts).
  - Response format (`output[0].content[0].text`):
    `<0f>0<|box;x1=145;y1=260;x2=158;y2=368;w=640;h=640;c=0.7037|><|mask;x=0;y=0;c=0.7037;data=109,14,!...base85...|>,1<|box;...|><|mask;...|>`
    - When no instances match, `output_text` is empty (`""`).
    - Bounding box coordinates `x1, y1, x2, y2` are integer pixels in the input image frame `w x h`.
    - `data=<H_crop>,<W_crop>,<prefix><base85>` encodes the binary mask cropped to the bounding box (`H_crop = y2 - y1 + 1`, `W_crop = x2 - x1 + 1`), prefixed by `!` (`one_bit`) or `~` (`lossless` RLE+zlib+base85).
- **Pricing & Rate Limits**:
  - Image segmentation (`sam-3.1`): **`$2.50` per 1,000 images** (`$0.0025` per image call).
  - Video segmentation (`sam-3.1`): **`$0.20` per 1,000 frames** (minimum 50 frames).
  - Rate limit: **`3,000 RPM`** per team (no TPM limit).
- **Muse vs. Gemini Flash for Verification & OCR**:
  - Meta's `muse-spark-1.3` (`$1.25`/`$4.25` per 1M input/output tokens on Standard tier; `$0.10`/`$0.20` on Contributor tier where data is used for training) and `muse-wonder` (`$4.00`/`$20.00`) are heavier general/agentic models. Gemini Flash (`gemini-3.8-flash` / `gemini-3.5-flash-lite`) is purpose-built for fast multimodal crop verification and OCR without Contributor-tier data-sharing trade-offs.
- **License & Terms**:
  - Hosted API: governed by Meta Model API Terms (`sam-3.1` is classified as a Standard Model; free credits / Contributor tier allow Meta to use submitted content for product improvement, so we only ever send public CC-BY-SA Mapillary imagery or synthetic test images).
  - Open weights: governed by the **SAM License** (permissive commercial & research use, attribution required, military/surveillance restrictions).

### 2d. Gemini API
- **Sources**:
  - `/workspace/ref/builder-guide.txt` (**Confirmed**)
  - [Google AI Studio Gemini Models Documentation](https://ai.google.dev/gemini-api/docs/models) (**Confirmed**)
  - [Google AI Studio Multimodal Embeddings Documentation](https://ai.google.dev/gemini-api/docs/embeddings) (**Confirmed**)
- **Model Identifiers**:
  - Vision crop verification (`Verifier`) and sign OCR (`TextReader`): **`gemini-3.8-flash`** (with `thinkingConfig: {"thinkingBudget": 0}` or `thinkingLevel: "MINIMAL"` for fast single-token/short-JSON latency; `gemini-3.5-flash-lite` as ultra-low-cost fallback). Note: `gemini-2.5-flash` and `gemini-3.5-flash` are retired.
  - Multimodal image/text embeddings (`Embedder` for `RoomFingerprint`): **`gemini-embedding-2-preview`** (or `gemini-embedding-001` for text), supporting unified image + text embeddings via `embedContent`.
- **Endpoint Choice**:
  - Standard REST `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` and `:embedContent` with header `x-goog-api-key: $GEMINI_API_KEY`.
  - Why `:generateContent` over `/v1beta/interactions`: Every `Verifier`, `TextReader`, and `Embedder` call in our pipeline is a stateless single-shot transformation on an image crop. `:generateContent` has minimal payload overhead and direct JSON schema support, whereas `/v1beta/interactions` is designed for stateful multi-turn agent loops.

### 2e. Dataset & Model Licences
- **ScanNet++** ([ScanNet++ Terms of Use](https://scannetpp.mlsg.cit.tum.de/scannetpp/)):
  - Custom non-commercial academic research license (no commercial use, no redistribution). Contains 1,000+ scenes with open/long-tail semantic annotations (**Confirmed**).
- **3RScan** ([3RScan License](https://github.com/WaldJohannaU/3RScan/blob/master/LICENSE), [3RScan Documentation](https://vmnavab26.in.tum.de/3RScan/documentation.php)):
  - Repository code is **MIT License**, but the **3RScan dataset** itself is governed by the 3RScan Terms of Use (**non-commercial academic research only**) (**Confirmed**).
- **Aria Synthetic Environments (ASE)** ([ASE License Agreement](https://www.projectaria.com/datasets/ase/license/)):
  - Custom **Aria Synthetic Environments Dataset License Agreement** — non-commercial research use only (**Confirmed**).
- **EFM3D** ([Project Aria EFM3D](https://www.projectaria.com/research/efm3D/), [arXiv:2406.10224](https://arxiv.org/abs/2406.10224)):
  - Captured with **Aria Gen 1** glasses (published June 2024 on ASE + ADT + AEO; Aria Gen 2 Pilot Dataset was released later in October 2025, [arXiv:2510.16134](https://arxiv.org/abs/2510.16134)). Code/weights use Project Aria non-commercial research terms (**Confirmed**).
- **Zephr `jepa-entrance`** ([GitHub `zephr-xyz/jepa-entrance`](https://github.com/zephr-xyz/jepa-entrance)):
  - Repository README states **MIT License** at the bottom (no standalone `LICENSE` file in root).
  - Pretrained weights (`best_model.pt`, 50.7 MB) are publicly hosted at `https://zephr-mapillary-cache.s3.amazonaws.com/jepa-entrance-v3-singleedge/best_model.pt` (verified HTTP 200), trained on Boulder & Louisville data with `1.73 m` mean / `0.78 m` median error on 71 RTK ground-truth POIs (**Confirmed**).

---

## 3. Live Probe Results (Ferry Building, San Francisco)

Target area: **Ferry Building, San Francisco** (`lat = 37.79545, lon = -122.39373`, radius `75 m`, bounding box `-122.394583,37.794776,-122.392877,37.796124`).

| Probe | Target / Endpoint | HTTP Status | Latency | Summary of Real Output | Approx. Cost |
|---|---|---|---|---|---|
| **1a. Mapillary `/images`** | `GET graph.mapillary.com/images?bbox=...&limit=2000` | `200 OK` | `7,857.6 ms` | **650 images** total; **180 panoramas** (`is_pano=true`); **41 sequences**; `captured_at` range `1319973389000` (2011-10-30) to `1695600049000` (2023-09-25). | Free (`OAuth` quota) |
| **1b. Mapillary `/map_features`** | `GET graph.mapillary.com/map_features?bbox=...&limit=2000` | `200 OK` | `10,545.6 ms` | **647 map features** across 44 classes: `object--street-light` (104), `object--manhole` (50), `object--traffic-sign--front` (50), `object--sign--store` (47), `object--sign--advertisement` (42), `object--bench` (36), `object--trash-can` (28), `marking--discrete--crosswalk-zebra` (18), `object--traffic-light--pedestrians-side` (17), `object--traffic-light--pedestrians-front` (12), `object--bike-rack` (6), `object--sign--information` (2). Zero doors/steps. | Free (`OAuth` quota) |
| **2. Overpass API** | `POST overpass-api.de/api/interpreter` (`around:75,37.79545,-122.39373`) | `200 OK` | `40,979.0 ms` | **78 elements**: **2 buildings** (`way/558731934` "San Francisco Ferry Building" with 51 polygon vertices; `way/1189071405` with 28 vertices), **9 `entrance=*` nodes** (`2481430442`, `2481430444`, `11041574557`, `11041574570`, `11041574615`, `11041574623` [`main`], `11041574624` [`main`], `11041574625`, `11041574633`), and **50 named POIs** (e.g. Blue Bottle Coffee, Hog Island Oyster Company, Ferry Plaza Wine Merchant, Book Passage, Dandelion Chocolate, Miette Patisserie, Acme Bread, Imperial Tea Court, Gott's Roadside). | Free (public Overpass) |
| **3. Meta `sam-3.1`** | `POST api.meta.ai/v1/responses` on 640x640 90° perspective crop of pano `1104309667442811` facing Ferry Building (`prompt = "door"`) | `200 OK` | `2,558.1 ms` | **2 door masks/boxes** returned in `output[0].content[0].text` (`345 chars`): Box 0 `[145, 260, 158, 368]` (`c = 0.7037`, mask `109x14` `one_bit` base85) and Box 1 `[28, 250, 45, 373]` (`c = 0.7388`, mask `124x18` `one_bit` base85). `usage.images_processed = 1`. | `$0.0025` (1 image) |
| **4. Gemini Flash** | `POST generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent` on the same 640x640 crop (`"Is there a door in this image? Answer yes, no or insufficient."`) | `200 OK` | `7,921.6 ms` | Returned `"Yes"` (`promptTokenCount = 1104` [1089 image + 15 text], `candidatesTokenCount = 1`, `thoughtsTokenCount = 93`, `totalTokenCount = 1198`). Setting `thinkingBudget = 0` in Step 3 will eliminate thinking overhead. | `~$0.0002` |

---

## 4. Environment Manifest Schema (`SimEnvironment`)

Our environment manifest is a **strict superset** of Google XR Blocks `SimulatorEnvironmentManifest` (`/workspace/ref/xrblocks/SimulatorEnvironmentManifest.ts`). Any JSON produced by XR Blocks (`DefaultManifests.ts`) deserializes cleanly into `SimEnvironment`, and our enriched manifests preserve `name`, `scenePath`, `objects[].position`, `objects[].quaternion`, `locations`, and `lighting` while adding geospatial, provenance, perception, station, walkable, and room-recognition fields:

```json
{
  "schemaVersion": "1.0",
  "name": "Ferry Building Street Preview",
  "kind": "STREET",
  "geoAnchor": {
    "latitude": 37.79545,
    "longitude": -122.39373,
    "YawDegrees": 0.0
  },
  "provenance": {
    "createdAtEpochMs": 1730000000000,
    "builderVersion": "0.1.0",
    "sources": ["MAPILLARY", "OSM"]
  },
  "attribution": "Imagery (c) Mapillary contributors (CC-BY-SA 4.0); Map data (c) OpenStreetMap contributors (ODbL).",
  "objects": [
    {
      "id": "door-2481430444",
      "name": "Ferry Plaza Wine Merchant Entrance",
      "category": "DOOR",
      "subCategory": "main",
      "position": [50.2, 0.0, 22.8],
      "quaternion": [0.0, 0.0, 0.0, 1.0],
      "latitude": 37.795655,
      "longitude": -122.393159,
      "confidence": 0.89,
      "sources": ["OSM", "MAPILLARY", "SAM_3_1", "GEMINI_FLASH"],
      "text": "Ferry Plaza Wine Merchant",
      "linkedVenue": {
        "osmId": "node/3579005161",
        "name": "Ferry Plaza Wine Merchant",
        "category": "wine"
      },
      "evidence": [
        {
          "stationId": "1104309667442811",
          "bearingDegrees": 201.3,
          "box": [145, 260, 158, 368],
          "detectorConfidence": 0.704,
          "verifierResult": "YES"
        }
      ]
    }
  ],
  "signs": [
    {
      "id": "sign-1",
      "text": "Ferry Plaza Wine Merchant",
      "confidence": 0.92,
      "position": [50.0, 2.4, 22.7],
      "latitude": 37.795654,
      "longitude": -122.393161,
      "stationId": "1104309667442811",
      "bearingDegrees": 200.8
    }
  ],
  "stations": [
    {
      "id": "1104309667442811",
      "sequenceId": "seq-ferry-01",
      "latitude": 37.795778,
      "longitude": -122.393158,
      "compassAngle": 199.7,
      "capturedAtEpochMs": 1684026247000,
      "isPano": true,
      "position": [50.3, 0.0, 36.4]
    }
  ],
  "walkable": {
    "floorPolygon": [
      [-10.0, -10.0],
      [60.0, -10.0],
      [60.0, 50.0],
      [-10.0, 50.0]
    ]
  }
}
```

---

## 5. Module Plan, Cost Estimates, and Risks

### 5.1 Module Architecture (`perception/`)
All code lives under `perception/src/main/kotlin/org/scottishtecharmy/soundscape/sim/`:
1. **`sim/model` (Pure Kotlin, KMP-ready)**:
   - `SimEnvironment`, `SimObject`, `SimCategory`, `Sign`, `Station`, `GeoAnchor`, `Provenance`, `Evidence`, `LinkedVenue`, `Walkable`, `Fingerprint`, `XrBlocksLighting`, `XrBlocksLocation`.
2. **`sim/geo` (Pure Kotlin, KMP-ready)**:
   - `LocalEnu` (WGS84 <-> local East-North-Up metres around anchor; `< 1 cm` error within `1 km`).
   - `Angles` & `Quaternions` (scalar-first `(w, x, y, z)` matching Meta Wearables DAT `MotionSample`; yaw/pitch/roll; camera pixel ray -> world bearing & elevation).
   - `PanoramaProjection` (equirectangular pixel <-> `(bearing, elevation)` and perspective camera crop pixel <-> world ray).
   - `Geometry2D` (2-ray & N-ray least-squares triangulation with conditioning check; ray-polygon facade intersection; point-to-footprint-edge snapping; ground-plane distance `d = h / tan(pitch_below_horizon)`).
   - `Geohash` (precision-7 encode/decode and 8 neighbours).
3. **`sim/eval` (Pure Kotlin, KMP-ready)**:
   - `ObjectMatchingEval` (greedy distance-threshold matching, Precision, Recall, F1, mean/median/p90 position error).
   - `TextEval` (Levenshtein Character Error Rate `CER` and normalized exact match).
   - `RoomRecognitionEval` (confusion matrix + strict `zeroFalseMatches` check).
   - `NavigationEval` (Success weighted by Path Length `SPL` and success rate).
4. **`sim/sources` (Pure Kotlin interfaces, KMP-ready)**:
   - `Budget`, `MapillarySource`, `OverpassSource`, `OvertureClient` (interface + TODO), `PanoramaImageSource`, `SegmentationClient`, `Verifier`, `TextReader`, `Embedder`.
5. **`sim/builders` (Pure Kotlin, KMP-ready)**:
   - Outdoor: `StationSelector`, `ViewPlanner`, `StreetAggregator`, `StreetBuilder` (`Flow<BuildEvent>`), `AreaCache`.
   - Indoor: `CaptureFrame`, `CaptureCoverage`, `CategoryPrecheck`, `RoomCaptureBuilder`, `RoomFingerprint`, `RoomRecognizer`.
6. **`sim/jvm` (JVM-only implementations & CLI)**:
   - `HttpGateway` (Ktor CIO client + fixture recording/replay with auth header & signed URL scrubbing + exponential backoff).
   - `HttpMapillaryClient`, `HttpOverpassClient`, `JvmPanoramaImageSource` (`BufferedImage` equirectangular -> perspective projection), `MetaSam31Client`, `GeminiFlashClient`, `Cli` (`build-area`).

### 5.2 Cost Estimate per Street Area & per Indoor Room Scan
- **Per New Street Area (75 m radius, up to 12 stations)**:
  - Mapillary & Overpass queries: `$0.00` (free quotas; ~2–15 HTTP calls).
  - View planning: 12 stations x ~2 facade-facing views/station = ~20 perspective crops.
  - Meta `sam-3.1`: 20 crops x 3 prompts (`door`, `shop sign`, `steps`) = **60 calls** x `$0.0025` = **`$0.15`**.
  - Gemini Flash (`Verifier` + `TextReader`): ~30–50 candidate crops x `$0.00015` = **`~$0.006`**.
  - **Total per 75 m street area**: **`~$0.16`** (cached in `AreaCache` by geohash-7 for 90 days).
- **Per Indoor Room Scan (one 360° head-turn scan, ~18 frames)**:
  - Category precheck (1 Gemini Flash call on 4 keyframes): **`~$0.0005`**.
  - Meta `sam-3.1` (8–10 selected keyframes x 4 active categories): **32–40 calls** x `$0.0025` = **`$0.08–$0.10`**.
  - Gemini Flash (`Verifier` + `TextReader` + 4 `gemini-embedding-2-preview` calls): **`~$0.004`**.
  - **Total per indoor room scan**: **`~$0.09–$0.11`**.

### 5.3 Top Technical Risks & Mitigations
1. **Panorama GPS Drift & Single-Ray Depth Ambiguity**:
   - *Risk*: Street-view GPS in urban canyons can drift by 2–5 m, making ray-ray triangulation noisy when stations are nearly collinear.
   - *Mitigation*: Enforce conditioning angle threshold (`>= 10°` parallax) on multi-ray triangulation; fall back to single-ray intersection against OSM building footprint facades and snap within `5 m`.
2. **SAM 3.1 False Positives on Facade Windows/Panels**:
   - *Risk*: `sam-3.1` with prompt `"door"` can segment narrow ground-floor windows or decorative arches (confidence `0.70–0.75`).
   - *Mitigation*: Pass candidate crops through `GeminiFlashClient` (`Verifier` tri-state `YES`/`NO`/`INSUFFICIENT`) and require either `YES` or multi-view/OSM agreement.
3. **Monocular Ground-Plane Pitch Sensitivity Indoors**:
   - *Risk*: At shallow pitch angles (`< 5°` below horizon), `d = h / tan(pitch)` amplifies small IMU pitch errors into large distance errors.
   - *Mitigation*: Reject floor-contact rays above or within `4°` of the horizon (`MAX_RANGE_METRES = 15.0`), require lower-edge floor contact for `DOOR`/`COUNTER`/`STEPS`, and average across overlapping yaw views.
4. **Overpass API Latency & Rate Limiting**:
   - *Risk*: Public Overpass servers take 10–40 s on dense urban queries and return HTTP 429/504 on rapid back-to-back requests or unindexed `relation["name"]` queries.
   - *Mitigation*: Query only `way["building"]`, `relation["building"]`, `node["entrance"]`, `node["name"]`, and `way["name"]` (excluding route relations), and cache fixtures via `HttpGateway`.
5. **Near-Duplicate Indoor Rooms (e.g. "Room 214" vs "Room 216")**:
   - *Risk*: Visually identical office/hotel rooms share almost identical visual embeddings.
   - *Mitigation*: In `RoomRecognizer`, treat OCR-verified room/door sign tokens (especially alphanumeric identifiers like `"214"` vs `"216"`) as hard discriminative constraints alongside visual cosine similarity.
