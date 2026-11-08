# Step 0 Report: Verify, Probe, Plan

## 1. What Was Accomplished
- **Codebase & Reference Audit**:
  - Reviewed `/workspace/ref/brief.md`, `/workspace/ref/report.md` (honoring all **[Review]** corrections), `/workspace/ref/builder-guide.txt`, and `/workspace/ref/xrblocks/*.ts`.
  - Audited Soundscape-Android (`StreetPreview.kt`, `GeoEngine.kt`, `AudioEngine.kt`, `LocationProvider.kt`, `EntranceMatching.kt`, `CheapRuler.kt`, `StringExt.kt`) and documented how node-to-node street movement, 2D/3D spatial audio placement, and tile-vertex entrance matching operate today in `perception/docs/agent/STEP0_PLAN.md`.
- **Primary Source Verification (2a–2e)**:
  - Verified Mapillary API v4 endpoints, fields, rate limits, and confirmed the absence of `door`/`entrance`/`steps` classes in both `map_features` (42 classes) and `detections` (152 non-sign classes).
  - Verified Overture Maps `places/place` and `buildings/building` (`v2.0.0`) schemas, Zephr's entrance contribution and `zephr-xyz/embedding-tiles` coverage, and `CDLA-Permissive-2.0` / `ODbL` licensing.
  - Verified Meta Model API (`POST https://api.meta.ai/v1/responses`) for `sam-3.1`, including single-noun-phrase prompt rules, `<|box;...|><|mask;...|>` response grammar, `$2.50 / 1,000 images` pricing, and `3,000 RPM` rate limit.
  - Verified Gemini API (`gemini-3.8-flash` and `gemini-embedding-2-preview`) endpoints and token structure.
  - Verified dataset/model licenses for ScanNet++, 3RScan, Aria Synthetic Environments (ASE), EFM3D (Aria Gen 1), and Zephr `jepa-entrance` (MIT).
- **Four Live API Probes on Ferry Building, San Francisco (`37.79545, -122.39373`, radius `75 m`)**:
  1. **Mapillary `/images` & `/map_features`**: `650` total images (`180` panoramas, `41` sequences, `1319973389000`–`1695600049000` epoch ms) in `7,857.6 ms`; `647` map features across 44 classes in `10,545.6 ms`.
  2. **Overpass API**: `78` elements (`2` buildings including 51-vertex `way/558731934` "San Francisco Ferry Building", `9` `entrance=*` nodes, `50` named POIs) in `40,979.0 ms`.
  3. **Meta `sam-3.1`**: 1 call on a 90° perspective crop (`640x640`) of panorama `1104309667442811` facing the Ferry Building with prompt `"door"` returned HTTP 200 in `2,558.1 ms` with `2` segmented door boxes/masks (`c = 0.7037` and `c = 0.7388`).
  4. **Gemini Flash (`gemini-3.8-flash`)**: 1 call on the same perspective crop returned HTTP 200 in `7,921.6 ms` with `"Yes"`.
- **Architecture & Decisions**:
  - Authored `perception/docs/agent/STEP0_PLAN.md`, `perception/docs/agent/DECISIONS.md`, `perception/docs/agent/PROGRESS.md`, and `perception/AGENTS.md`.

## 2. Pass/Fail Check Verification
- `perception/docs/agent/STEP0_PLAN.md` and `perception/docs/agent/DECISIONS.md` exist and cite primary sources with URLs: **PASS**
- All four live probes succeeded and their real outputs are recorded: **PASS**
- No files outside `perception/` are touched (`git status` shows only `perception/`): **PASS**

## 3. Budget Usage (Step 0)
| API | Used in Step 0 | Step Cap |
|---|---|---|
| Mapillary Graph API | `6` calls | `2,000` |
| Meta `sam-3.1` | `1` call | `80` (Step 0 max: `1`) |
| Gemini Flash (`gemini-3.8-flash`) | `1` call | `200` (Step 0 max: `1`) |

## 4. What Is Left (Next Steps)
- **Step 1 (`argon/step-1`)**: Standalone Gradle setup in `perception/`, `sim/model` (XR Blocks superset manifest), `sim/geo` (ENU, projections, ray triangulation, footprint intersection, quaternions, geohash), and `sim/eval` (matching, CER, room recognition, SPL).
- **Step 2 (`argon/step-2`)**: Outdoor data sources (`HttpGateway`, `MapillaryClient`, `OverpassClient`, `OvertureClient`), `StationSelector`, `ViewPlanner`, `StreetAggregator`, `StreetBuilder`, `AreaCache`, and CLI `build-area`.
- **Step 3 (`argon/step-3`)**: Panorama perception with `MetaSam31Client`, `GeminiFlashClient` (`Verifier` + `TextReader`), multi-ray / facade localisation, venue linking, and Ferry Building evaluation metrics.
- **Step 4 (`argon/step-4`)**: Indoor head-turn room capture (`RoomCaptureBuilder`), floor polygon extraction, `RoomFingerprint`, `RoomRecognizer`, synthetic room test suite, and `perception/README.md`.

## 5. Known Risks & Environment Notes
1. **GitHub Proxy Fine-Grained PAT Scope (`403 Forbidden` on `Znasif/Soundscape-Android` Write)**:
   - `git push -u origin argon/step-0` and `POST https://api.github.com/repos/Znasif/Soundscape-Android/pulls` return `403 Forbidden` (`remote: Permission to Znasif/Soundscape-Android.git denied to Znasif` / `Resource not accessible by personal access token`, `X-Accepted-Github-Permissions: contents=write`). All commits and branches (`argon/step-0`..`argon/step-4`) are preserved in `/workspace/Soundscape-Android`.
2. **Panorama GPS Drift**: Urban canyon GPS error (`2–5 m`) can skew multi-station triangulation when rays are nearly parallel; mitigated in Step 1/3 via conditioning checks and facade polygon snapping.
3. **SAM 3.1 False Positives on Windows/Arches**: Addressed in Step 3 via `Verifier` (`gemini-3.8-flash`) confirmation and OSM/multi-view agreement.
4. **Overpass Public Server Latency**: Addressed in Step 2 via scoped queries (`way["building"]`, `node["entrance"]`, `node["name"]`, `way["name"]`), exponential backoff, and recorded test fixtures.
