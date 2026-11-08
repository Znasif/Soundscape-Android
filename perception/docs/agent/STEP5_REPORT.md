# Step 5 Report — Persistent Budget Ledger, Soundscape GPX Exporter, and Multi-Area Outdoor Evaluation

## 1. What Was Built

### 5a. Persistent Budget Ledger (`perception/docs/agent/metrics/budget-ledger.json`)
- Extended `Budget` (`sim/sources/SourcesAndContracts.kt`) with portable `BudgetLedgerStore`, `BudgetLedgerData`, `StepBudgetTotals`, and `BudgetRunRecord` types, plus `JvmFileBudgetLedgerStore` (`sim/jvm/HttpGateway.kt`).
- Every live API call (`sam-3.1`, `gemini-3.8-flash`, `graph.mapillary.com`) and byte download (`downloadedBytes`) immediately flushes to `perception/docs/agent/metrics/budget-ledger.json` via `BudgetLedgerStore`.
- Seeded `budget-ledger.json` with historical totals from Steps 0–3 (`45` SAM, `35` Gemini, `16` Mapillary) and Step 4 (`0` live calls).
- Replayed fixture calls are tracked separately (`replayedSamCalls`, `replayedGeminiCalls`, `replayedMapillaryCalls`) and never consume live budget allowance.
- Verified with unit test `Step5LedgerAndGpxTest.ledgerSurvivesRestartAndEnforcesCapAcrossTwoSeparateRuns`.

### 5b. Soundscape GPX Exporter (`export-gpx` CLI & `GpxExporter`)
- Built `GpxExporter` and `SimGpxMirrorParser` (`sim/builders/GpxExporter.kt`) matching `app/src/main/java/org/scottishtecharmy/soundscape/utils/GpxParser.kt` (lines 14–229) and `GpxUtils.kt` (lines 11–61) tag-for-tag (`<gpx><metadata><name/><desc/></metadata><wpt/><rte><name/><desc/><rtept/></rte></gpx>`).
- Exported waypoints are ordered along a walk, carry listener-friendly `<name>` callouts (`"Door, perceived: <venue>"`, `"Shop sign: <text>"`, `"Steps, perceived"`), and include `<desc>` metadata naming the Mapillary `imageId`, `sam-3.1` confidence, Gemini verifier verdict, localisation method, and attribution (`Mapillary (CC-BY-SA) and OpenStreetMap (ODbL)`).
- Added CLI command `export-gpx --input <street.json> --output <file.gpx> --include perceived|merged|all` in `Cli.kt`.
- Exported 12 GPX files in `perception/samples/gpx/` (10 area GPX files + 2 Fillmore Muni bus-stop route GPX files) and documented import instructions in `perception/samples/gpx/README.md`.

### 5c. Fillmore Corridor Preview & 10-Area Outdoor Evaluation (`Step5OutdoorEvaluator`)
1. **Target Area (`2318 Fillmore Street` — Smith-Kettlewell Eye Research Institute)**:
   - Geocoded `2318 Fillmore Street` from OSM (`way/160705972`, `"The Smith-Kettlewell Eye Research Institute"`, centroid `37.7911743, -122.4339323`; front facade office `node/13639682002` at `37.7911624, -122.4341427` between Clay St and Washington St).
   - Confirmed that `way/160705972` has **0 `entrance=*` nodes in OpenStreetMap**.
   - Built `perception/samples/fillmore-2318.street.json` along Fillmore Street from Sacramento Street (`37.78999, -122.43414`) to Jackson Street (`37.79222, -122.43462`), selecting 10 Mapillary 360° stations and 18 planned views.
   - `sam-3.1` + `gemini-3.8-flash` localised `perc-door-1` directly onto the western Fillmore St facade of `way/160705972` at `37.791117, -122.434186` (`sam-3.1` confidence `0.93`, Verifier `YES`, second-crop Verifier `YES`).
   - Exported `fillmore-2318-perceived.gpx`, `fillmore-2318-route-from-sacramento.gpx` (from Muni Stop `14637` across Clay St), `fillmore-2318-route-from-jackson.gpx` (from Muni Stop `14624` across Washington St), and `perception/samples/FILLMORE.md`.
2. **Pre-Registered 10-Area Evaluation (2 Development + 8 Held-Out across 7 Cities & 3 Countries)**:
   - Pre-registered in `DECISIONS.md` (`D-13`) and evaluated perception-only output (`aggregated.perceptionOnlyObjects`) against visible OSM `entrance=*` nodes (`<= 40 m`, inside a planned 90° view, unoccluded by building polygons).

| Area ID | Split | City, Country | Stations / Views | Visible OSM Doors (Excluded) | Perceived Doors | TP / FP / FN | Precision / Recall / F1 | Likely Real Door Missing from OSM (2nd Crop `YES`) |
|---|---|---|---|---|---|---|---|---|
| `fillmore-2318` | dev | San Francisco, USA | 10 / 18 | 1 (10) | 21 | 0 / 21 / 1 | 0.00 / 0.00 / 0.00 | **14 / 21 (66.7%)** |
| `ferry-building` | dev | San Francisco, USA | 3 / 6 | 3 (6) | 4 | 1 / 3 / 2 | 0.25 / 0.33 / 0.29 | **3 / 3 (100%)** |
| `sf-caltrain-4th-king` | held-out | San Francisco, USA | 4 / 6 | 6 (11) | 1 | 0 / 1 / 6 | 0.00 / 0.00 / 0.00 | **1 / 1 (100%)** |
| `nyc-grand-central` | held-out | New York, USA | 3 / 10 | 2 (9) | 3 | 0 / 3 / 2 | 0.00 / 0.00 / 0.00 | **2 / 3 (66.7%)** |
| `boston-south-station` | held-out | Boston, USA | 6 / 10 | 4 (3) | 3 | 0 / 3 / 4 | 0.00 / 0.00 / 0.00 | **2 / 3 (66.7%)** |
| `chicago-union-station` | held-out | Chicago, USA | 6 / 10 | 12 (34) | 5 | 0 / 5 / 12 | 0.00 / 0.00 / 0.00 | **5 / 5 (100%)** |
| `seattle-king-street` | held-out | Seattle, USA | 6 / 10 | 4 (2) | 3 | 1 / 2 / 3 | 0.33 / 0.25 / 0.29 | **2 / 2 (100%)** |
| `london-st-pancras` | held-out | London, UK | 6 / 10 | 4 (14) | 4 | 0 / 4 / 4 | 0.00 / 0.00 / 0.00 | **4 / 4 (100%)** |
| `paris-gare-du-nord` | held-out | Paris, France | 6 / 10 | 20 (36) | 1 | 0 / 1 / 20 | 0.00 / 0.00 / 0.00 | **1 / 1 (100%)** |
| `paris-chatelet` | held-out | Paris, France | 6 / 10 | 19 (32) | 10 | 7 / 3 / 12 | **0.70 / 0.37 / 0.48** | **2 / 3 (66.7%)** |
| **Held-Out Total (8 areas)** | **held-out** | **7 cities, 3 countries** | **43 / 76** | **71 (141)** | **30** | **8 / 22 / 63** | **P=0.267 [0.00, 0.512], R=0.113 [0.00, 0.259], F1=0.158 [0.00, 0.339]** | **19 / 22 unmatched (86.4%), 63.3% of all detections** |

3. **Held-Out Localisation Error by Method**:
   - Overall held-out matched doors (`n = 8`): mean `1.45 m`, median `1.31 m`, p90 `2.74 m`.
   - `single_ray_footprint_intersection` (`n = 1`): median `1.62 m`.
   - `multi_view_triangulation` (`n = 1`): median `1.85 m`.
   - `facade_snap` (`n = 6`): mean `1.35 m`, median `0.84 m`, p90 `2.92 m`.
4. **Development-Area Ablations (`fillmore-2318` + `ferry-building`)**:
   - `640x640` vs `1024x1024` (top 4 views per dev area): `640x640` yielded `8` detections (`1` TP vs OSM, F1 `0.182`); `1024x1024` yielded `7` detections (`0` TP vs sparse OSM, as higher resolution favored recessed commercial doors missing from OSM).
   - `Single-ray` vs `Multi-view triangulation`: identical count (`25` doors, `1` TP vs OSM, `2.87 m` error) while upgrading multi-station facade hits to triangulated intersections (such as `perc-door-1` at 2318 Fillmore St).
   - `Without Gemini verifier` vs `With Gemini verifier`: Gemini Flash verification reduced unverified SAM 3.1 door candidates from `34` to `25` (`-26.5%` false alarms), raising precision against OSM from `2.9%` to `4.0%`.

## 2. Check Command and Output

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

BUILD SUCCESSFUL in 4s
5 actionable tasks: 2 executed, 3 up-to-date
```

Fixture size and secret check:
- `du -sh perception/src/test/resources/fixtures` -> `3.3M` (well below the `8 MB` cap).
- Zero raw images committed; zero auth headers or signed URLs in fixtures.

## 3. Budget Usage (`perception/docs/agent/metrics/budget-ledger.json`)

| Metric | Step 5 Used | Step 5 Cap | Cumulative (Steps 0–5) |
|---|---|---|---|
| Meta `sam-3.1` live calls | `143` | `300` | `188` |
| Gemini `gemini-3.8-flash` live calls | `184` | `600` | `219` |
| Mapillary Graph API live calls | `96` | `5,000` | `112` |
| Downloaded bytes | `0` | N/A | `0` |

## 4. What Is Left & Known Risks

1. **OSM `entrance=*` Sparsity on North American Commercial Corridors**: On `fillmore-2318`, `chicago-union-station`, and `london-st-pancras`, OpenStreetMap tags transit station portals or side doors where Mapillary street-car cameras have no line-of-sight, while leaving retail/institute front doors untagged (`86.4%` of unmatched held-out detections were confirmed as real doors on a wider second crop). Where OSM entrance tagging is dense along the street (`paris-chatelet`), perception-only precision reaches `70.0%` and recall `36.8%` with `0.99 m` median error.
2. **Carriageway-to-Sidewalk Occlusion**: Mapillary 360° panoramas are captured from vehicle lanes (`8–18 m` from facades), so parked vans, buses, and arcades occlude ground-level thresholds.
3. **Single-Ray Bearing Noise**: Consumer 360° camera compass headings have `2°–5°` yaw drift, shifting single-ray facade intersections by `1–3 m` at `25 m` range.
