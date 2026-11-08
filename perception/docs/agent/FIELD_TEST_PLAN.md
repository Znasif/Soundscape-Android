# Hands-Free Field Test Plan: 2318 Fillmore Street (Smith-Kettlewell Eye Research Institute)

This field test plan provides a complete, reproducible protocol for validating the Soundscape-Android perception data pipeline at **2318 Fillmore Street, San Francisco, CA 94115** (**The Smith-Kettlewell Eye Research Institute**, OSM `way/160705972` and `node/13639682002`) both in **phone-only GPX / Street Preview mode** and with **Ray-Ban Meta smart glasses (`mwdat-camera` + `mwdat-motion`)**.

---

## 1. Pre-Field Phone-Only Checklist (GPX Import & Street Preview)

Before stepping outside, verify all three generated Fillmore GPX routes (`perception/samples/gpx/fillmore-2318-*.gpx`) on an Android phone running Soundscape-Android with bone-conduction or open-ear headphones (e.g., Shokz OpenRun or Ray-Ban Meta open-ear speakers):

1. **Transfer the GPX files to the phone**:
   ```bash
   adb push perception/samples/gpx/fillmore-2318-osm.gpx /sdcard/Download/
   adb push perception/samples/gpx/fillmore-2318-perceived.gpx /sdcard/Download/
   adb push perception/samples/gpx/fillmore-2318-all.gpx /sdcard/Download/
   ```
2. **Import into Soundscape-Android (`GpxUtils.parseGpxFile`)**:
   - Open Soundscape-Android -> **Routes** -> **Import a route** -> select `fillmore-2318-osm.gpx`, `fillmore-2318-perceived.gpx`, and `fillmore-2318-all.gpx`.
   - Confirm that:
     - `fillmore-2318-osm.gpx` imports cleanly with `0` waypoints on `way/160705972` (reflecting the baseline OSM gap where Smith-Kettlewell has zero mapped `entrance=*` nodes).
     - `fillmore-2318-perceived.gpx` imports with **`10` waypoints** (`6` perception-derived doors + `4` perception-derived signs).
     - `fillmore-2318-all.gpx` imports with **`38` waypoints** (`28` corridor OSM entrances + `6` perceived doors + `4` perceived signs).
3. **Indoor Bench Dry-Run in Street Preview**:
   - In Soundscape-Android, start **Street Preview** at `Fillmore St & Sacramento St` (`37.789989, -122.434141`), facing North (`0°`) along Fillmore Street.
   - Activate the `fillmore-2318-perceived.gpx` route and step north through the Clay Street intersection (`37.79055, -122.43426`) toward `2318 Fillmore Street` (`37.791174, -122.433932`).
   - Verify that spatial audio beacons and localized TTS callouts pan smoothly to the right (eastern sidewalk of Fillmore Street, `~40–95°` clock-face direction `2–3 o'clock`) as the virtual position approaches `door-perc-5` (`37.791103, -122.434121`) and `door-perc-6` (`37.791240, -122.434149`).

---

## 2. Outdoor Bus-Stop Walk Protocol (`Fillmore St & Sacramento St` -> `2318 Fillmore St` -> `Fillmore St & Jackson St`)

### 2.1 Route & Equipment
- **Start Point (South Bus Stop)**: Muni Route 22 northbound stop at **Fillmore St & Sacramento St** (`node/6368210990`, stop ref `14637`, `37.7899894, -122.4341405`).
- **Target Destination**: **The Smith-Kettlewell Eye Research Institute**, `2318 Fillmore Street` (`way/160705972`, western facade along Fillmore St between Clay St and Washington St, `37.7911624, -122.4341427`).
- **End Point (North Bus Stop)**: Muni Route 22 stop at **Fillmore St & Jackson St** (`node/6368210995`, stop ref `14624`, `37.7922243, -122.4346215`), passing **Washington St & Fillmore St** (`node/6384667509`, stop ref `16916`, `37.7915282, -122.4345260`).
- **Mode**: Hands-free phone in pocket, open-ear audio active, `fillmore-2318-perceived.gpx` route running. A sighted companion walks 1.5 m behind with a printed copy of Table 2.2 and a laser/tape measure to record ground-truth entrance offsets without interfering with the blind walker.

### 2.2 Printable Field Verification Table (`fillmore-2318-perceived.gpx`)

| Waypoint ID | Category | Predicted Label / Text | Predicted Lat, Lon | Target Building (`OSM`) | Observed Ground Truth (`TP` / `FP`) | Measured Offset (m) | Audio Timing & Side Correct? (`Y`/`N`) | Clarity (`1–5`) | Field Notes |
|---|---|---|---|---|---|---|---|---:|---|
| `door-perc-2` | `DOOR` | `Door` (south of Clay St) | `37.790339, -122.433969` | Commercial facade south of Clay St | `[ ] TP  [ ] FP` | `____ m` | `[ ] Y  [ ] N` | `___` | |
| `sign-1` | `SIGN` | `"2217"` (west side address sign) | `37.790257, -122.434232` | `2217 Fillmore St` storefront | `[ ] TP  [ ] FP` | `____ m` | `[ ] Y  [ ] N` | `___` | |
| `sign-2` | `SIGN` | `"2215"` (west side address sign) | `37.790218, -122.434230` | `2215 Fillmore St` storefront | `[ ] TP  [ ] FP` | `____ m` | `[ ] Y  [ ] N` | `___` | |
| `door-perc-3` | `DOOR` | `Door` (near Clay St corner) | `37.790929, -122.434092` | East side north of Clay St | `[ ] TP  [ ] FP` | `____ m` | `[ ] Y  [ ] N` | `___` | |
| `sign-4` | `SIGN` | `"P R"` | `37.790922, -122.434131` | Storefront north of Clay St | `[ ] TP  [ ] FP` | `____ m` | `[ ] Y  [ ] N` | `___` | |
| `door-perc-4` | `DOOR` | `The Smith-Kettlewell Eye Research Institute Entrance` | `37.791009, -122.434106` | `way/160705972` (south wing) | `[ ] TP  [ ] FP` | `____ m` | `[ ] Y  [ ] N` | `___` | |
| `door-perc-5` | `DOOR` | `Smith-Kettlewell Eye Research Institute Entrance` | `37.791103, -122.434121` | `way/160705972` (`4.2 m` from `node/13639682002`) | `[ ] TP  [ ] FP` | `____ m` | `[ ] Y  [ ] N` | `___` | Main entrance candidate |
| `door-perc-6` | `DOOR` | `Smith-Kettlewell Eye Research Institute Entrance` | `37.791240, -122.434149` | `way/160705972` (north wing) | `[ ] TP  [ ] FP` | `____ m` | `[ ] Y  [ ] N` | `___` | |
| `door-perc-1` | `DOOR` | `The Smith-Kettlewell Eye Research Institute Entrance` | `37.791306, -122.434160` | `way/160705972` (north corner) | `[ ] TP  [ ] FP` | `____ m` | `[ ] Y  [ ] N` | `___` | |
| `sign-3` | `SIGN` | `"2325"` (west side address sign) | `37.791254, -122.434401` | `2325 Fillmore St` across street | `[ ] TP  [ ] FP` | `____ m` | `[ ] Y  [ ] N` | `___` | |

### 2.3 Machine-Readable Field Result Schema (`perception/field/fillmore-<date>.json`)
After each walk, record results into `perception/field/fillmore-<YYYY-MM-DD>.json` following the committed example in `perception/field/fillmore-2026-11-08-example.json`:
- `testDate`: ISO-8601 date (`"YYYY-MM-DD"`)
- `testerId`: anonymized tester identifier
- `deviceModel`: phone & headset/glasses hardware
- `routeGpxFile`: GPX filename tested
- `outdoorWaypoints`: array of `{ waypointId, category, groundTruthVerdict ("TP"|"FP"), measuredHorizontalErrorMeters, correctSideOfStreet, audioClarityScore1To5, notes }`
- `missedRealEntrances`: array of real entrances on `2318 Fillmore Street` not present in the GPX (`{ description, latitude, longitude }`)
- `indoorScans`: array of indoor room scan trials (`{ roomId, scanDurationSeconds, horizonCoverageFraction, personFramesDropped, doorErrorMeters, receptionDeskErrorMeters, revisitOutcome ("MATCHED"|"ABSTAINED"|"FALSE_MATCH"), revisitYawErrorDegrees }`)

---

## 3. Indoor Ray-Ban Meta Glasses Capture Protocol (2318 Fillmore Street Lobby & Office)

1. **Pre-Scan Setup**:
   - Pair the Ray-Ban Meta smart glasses to the Android phone via Meta Wearables Device Access Toolkit (`mwdat-camera` `720x1280` @ 30 Hz + `mwdat-motion` fused quaternion & gyro).
   - Measure and enter the wearer's eye height above the floor in Soundscape settings (`cameraHeightMeters`, e.g. `1.65 m` standing or `1.15 m` seated; Step 6 showed that a `0.50 m` uncalibrated height offset adds `+0.19 m` to `+0.50 m` of 3D ground-plane projection error).
2. **Guided 360° Head-Turn Scan (`CaptureCoverage` + `RoomCaptureBuilder`)**:
   - Stand inside the **2318 Fillmore Street entrance lobby** (and repeat in one upstairs research room / waiting area).
   - Trigger **Scan Room** hands-free. Rotate head slowly through `360°` (`<= 0.8 rad/s`, ~15–20 seconds for a full turn).
   - Follow `CaptureCoverage` spoken clock-face prompts (`"Turn slowly toward 3 o'clock"`, `"Tilt head slightly down toward 12 o'clock to capture the floor boundary"`, `"Tilt head slightly up toward 6 o'clock to check high signs"`) until `horizonCoverageFraction >= 0.85` (`>= 15 / 18` horizon slots).
   - Verify localized audio callouts for the lobby **entrance door (`DOOR`)**, **reception desk (`RECEPTION_DESK` / `COUNTER`)**, **elevators/stairs (`LIFT` / `STAIRS`)**, and **room signs (`SIGN`)**.
3. **Revisit & Near-Duplicate Room Recognition Test (`RoomRecognizer`)**:
   - Exit and re-enter the scanned room from a different doorway or facing angle (`~30–60°` yaw offset) and run a short **2–3 keyframe revisit scan**. Verify that `RoomRecognizer` matches the stored room and aligns landmark callouts within `<= 15°` yaw error.
   - Enter an **unscanned room** (or an adjacent numbered office, e.g., Room 216 vs Room 214) and run a 2–3 keyframe scan. Verify that `RoomRecognizer` **abstains (`RecognitionResult.Abstained`)** with **zero false matches**.

---

## 4. Privacy, Bystander Consent & On-Device Person Dropping

Because cloud calls to Meta `sam-3.1` (under Contributor-tier free credits) and Google `gemini-3.8-flash` transmit image crops off-device:
1. **Verbal Notice & Consent**: Before initiating an indoor scan at 2318 Fillmore Street, announce `"Scanning room layout for audio navigation"` and ensure no unconsenting bystanders are in view.
2. **Mandatory On-Device Person/Face Filtering Before Any Cloud Call**:
   - Every accepted `CaptureFrame` must pass through an on-device person/face detector (ML Kit Pose/Face Detection or MediaPipe Object Detector running locally on Android) **before** `MetaSam31Client` or `GeminiFlashClient` is called.
   - If any person or face is detected (`personConfidence >= 0.35`), the frame is **dropped immediately on-device** (`personFramesDropped++`), never encoded into a cloud request, and `CaptureCoverage` keeps that angular slot open so the user is prompted to re-scan the slot once clear.
3. **Zero Raw Image Persistence**: Raw camera JPEGs exist only in ephemeral RAM during `RoomCaptureBuilder.buildRoom` and are zeroed/discarded immediately after the `SimEnvironment` JSON manifest is produced.

---

## 5. Pass / Fail Go/No-Go Thresholds

| Subsystem | Metric | Pass (Go) Threshold | Fail (No-Go) Condition |
|---|---|---|---|
| **GPX Route Import** | Soundscape `parseGpxFile` waypoint count & order | 100% of `<wpt>` & `<rtept>` nodes load without error | Any XML parse failure or missing waypoint |
| **Outdoor 2318 Fillmore Entrance Localisation** | Horizontal distance from best perceived entrance (`door-perc-5`) to true Smith-Kettlewell entrance | `<= 4.0 m` and on the correct (eastern) sidewalk | `> 5.0 m` error or mapped to wrong side of Fillmore St |
| **Outdoor Corridor Precision & Recall** | Perceived doors (`door-perc-1`..`6`) & signs (`sign-1`..`4`) verified in situ | Precision `>= 60%`, at least `1` true Smith-Kettlewell entrance localised within `4 m` | `0` true Smith-Kettlewell entrances detected or Precision `< 50%` |
| **Indoor Lobby / Room Reconstruction** | 3D horizontal error on static landmarks (`DOOR`, `RECEPTION_DESK`, `STAIRS`) | Median error `<= 0.75 m`, p90 error `<= 1.50 m` | Median error `> 1.00 m` |
| **Indoor Room Re-Recognition Safety** | False-match rate on unseen or differently numbered rooms | **`0.0%` false matches** (`noFalseMatch == true`, hard blocker) | **`>= 1` false match** on any unseen or differently numbered room |
| **Privacy Gate** | Cloud transmission of frames containing people/bystanders | **`0` frames** with people sent to Meta or Gemini | Any frame containing a bystander transmitted off-device |
