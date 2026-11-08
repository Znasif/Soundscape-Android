# Step 2 Report: Outdoor Sources and Street Builder

## 1. What Was Built
1. **`Budget` & Source Interfaces (`sim/sources/Sources.kt`)**:
   - Implemented `Budget` enforcing hard per-step caps (`maxMapillaryCalls`, `maxSamCalls`, `maxGeminiCalls`) and throwing `BudgetExceededException` past the cap.
   - Defined portable KMP-ready interfaces `MapillarySource`, `OverpassSource`, `OvertureClient` (plus `DeferredOvertureClient` with decision record `D-02` and `TODO`), `PanoramaImageSource`, `SegmentationClient`, `Verifier`, `TextReader`, and `Embedder`.
2. **`HttpGateway`, `HttpMapillaryClient`, `HttpOverpassClient` (`sim/jvm/`)**:
   - `HttpGateway`: supports deterministic fixture recording and offline replay (`src/test/resources/fixtures/ferry/`), exponential backoff retry on HTTP `429` and `5xx`, auth-header scrubbing (`Authorization`, `x-goog-api-key`), and signed CDN URL scrubbing (`thumb_*_url` / `https://scontent...` -> `"https://stub.mapillary.example/thumb.jpg"`).
   - `HttpMapillaryClient`: fetches images in bbox, image metadata by ID, map features in bbox, and per-image detections using `Authorization: OAuth $MAPILLARY_TOKEN`.
   - `HttpOverpassClient`: fetches OSM building footprint polygons, `entrance=*` nodes, and named POIs within radius `R` of `(lat, lon)`.
3. **Outdoor Street Builders (`sim/builders/`)**:
   - `StationSelector`: selects up to 12 panorama stations spaced `>= 8 m` apart, preferring `is_pano = true`, proximity to building footprints, and newer `captured_at`.
   - `ViewPlanner`: evaluates 8 cardinal/intercardinal yaw directions (`0°, 45°, ..., 315°`, `90°` hFOV) per station and retains only views intersecting a building footprint within `40 m`.
   - `StreetAggregator`: merges OSM entrances and Mapillary map features into `SimObject` and `Sign` items, deduplicates within `3 m`, snaps entrances to the nearest building footprint edge within `5 m`, and links entrances to OSM venues via fuzzy text / spatial matching.
   - `AreaCache`: precision-7 geohash cache with deterministic rebuild rules (newer `captured_at` in cell, `builderVersion` change, or cached entry `> 90 days` old).
   - `StreetBuilder`: orchestrates the pipeline and emits `Flow<BuildEvent>` (`FetchingSources`, `StationsSelected`, `ViewsPlanned`, `PerceptionProgress`, `CacheHit`, `Completed`).
4. **CLI (`sim/jvm/Cli.kt`) & Ferry Building Sample**:
   - Implemented `build-area --lat <deg> --lon <deg> --radius <m> --out <file> [--record <dir>] [--replay <dir>]`.
   - Ran live against Ferry Building (`37.79545, -122.39373`, radius `75 m`), producing `perception/samples/ferry-building.street.json` (`8` 360° stations, `8` planned facade views, `251` objects, `48` signs) and scrubbed offline test fixtures in `perception/src/test/resources/fixtures/ferry/`.

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
> Task :processTestResources UP-TO-DATE
> Task :compileTestKotlin
> Task :compileTestJava NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test

BUILD SUCCESSFUL in 3s
5 actionable tasks: 3 executed, 2 up-to-date
```
All 21 unit tests pass with zero network calls during `./gradlew -p perception test`, `perception/samples/ferry-building.street.json` validates cleanly, and no files outside `perception/` are touched.

## 3. Budget Usage (Step 2)
| API | Used in Step 2 | Step Cap |
|---|---|---|
| Mapillary Graph API | `2` calls | `2,000` |
| Meta `sam-3.1` | `0` calls | `80` |
| Gemini Flash | `0` calls | `200` |

## 4. What Is Left
- **Step 3 (`argon/step-3`)**: `JvmPanoramaImageSource` (equirectangular -> rectilinear perspective projection), `MetaSam31Client`, `GeminiFlashClient` (`Verifier` + `TextReader`), live Ferry Building perception run (`--perceive`), evaluation against OSM `entrance=*` nodes (`perception/docs/agent/metrics/STEP3-ferry-building.json`), and offline unit tests.
- **Step 4 (`argon/step-4`)**: Indoor head-turn room capture (`RoomCaptureBuilder`), floor polygon extraction, `RoomFingerprint`, `RoomRecognizer`, synthetic room test suite, and `perception/README.md`.

## 5. Known Risks
1. **Panorama Facade Occlusion**: Trees or market stalls in front of the Ferry Building arcade can partially occlude doors from distant stations; mitigated in Step 3 by combining multi-station sightings with OSM entrance data.
2. **GitHub Proxy PAT Scope (`403 Forbidden` on `Znasif/Soundscape-Android`)**: Commits and branch refs (`argon/step-2`) are preserved in `/workspace/Soundscape-Android`.
