# Soundscape Street Preview Prototype GPX Files (`perception/samples/gpx/`)

These GPX 1.1 files are exported by `export-gpx` (`org.scottishtecharmy.soundscape.sim.builders.GpxExporter`) to match Soundscape-Android's native GPX importer (`GpxParser.kt` + `GpxUtils.kt` + `IntentParser.parseGpx`). Each waypoint (`<wpt>` and `<rtept>`) includes:
- `<name>`: Listener-friendly callout (e.g. `"Door, perceived: Smith-Kettlewell Eye Research Institute"`, `"Shop sign: PAIGE"`).
- `<desc>`: Perception provenance (`Mapillary imageId`, `sam-3.1` confidence, `gemini-3.8-flash` verifier result, localisation method) and CC-BY-SA / ODbL attribution.

## How to Try These GPX Files on Your Android Phone (No Laptop Required)

1. **Download the GPX file on your phone**:
   - Open this repository branch (`argon/step-5`, `argon/step-6`, or `argon/step-7`) in **Chrome** or the **GitHub Android app**.
   - Navigate to `perception/samples/gpx/` and tap one of the `.gpx` files:
     - **For 2318 Fillmore Street (Smith-Kettlewell Eye Research Institute)**:
       - `fillmore-2318-perceived.gpx` — All perceived doors and signs along Fillmore Street (Sacramento St to Jackson St), ordered south-to-north.
       - `fillmore-2318-route-from-sacramento.gpx` — Walking route (`135 m` northbound) from Muni Stop **Fillmore St & Sacramento St** (Routes 22/24, Stop `14637`) across Clay Street to `2318 Fillmore Street`.
       - `fillmore-2318-route-from-jackson.gpx` — Walking route (`125 m` southbound) from Muni Stop **Fillmore St & Jackson St** (Routes 22/24, Stop `14624`) across Washington Street to `2318 Fillmore Street`.
   - Tap the ** Raw** button (or three-dot menu -> **Download**) to save the `.gpx` file to your phone's `Downloads` folder.

2. **Import into Soundscape-Android**:
   - Tap the downloaded `.gpx` notification in Chrome (or open your **Files** app -> `Downloads` -> tap the `.gpx` file).
   - Select **Soundscape** when Android prompts *"Open with..."* (`ACTION_VIEW` on `content://` with `application/octet-stream`).
   - Soundscape will parse the `<metadata>` route name/description and `<rtept>`/`<wpt>` waypoints and open the **Route Details** screen.

3. **Walk the Route in Real Life or Preview with Street Preview**:
   - **In-person walk at 2318 Fillmore Street**: Tap **Start Route** in Soundscape, put your phone in your pocket, and walk from the Muni bus stop along the Fillmore Street sidewalk. Soundscape will place the 3D spatial audio beacon on each waypoint in sequence (bus stop -> curb crossings -> perceived entrance at `2318 Fillmore Street`).
   - **Remote preview with Street Preview**: From the Soundscape home screen, choose **Street Preview**, search for `2318 Fillmore Street, San Francisco` (or any evaluated station area), and step along the street intersections while checking the imported markers and callouts.

## Exported GPX Files in This Directory

| File | Area / Route | Split |
|---|---|---|
| `fillmore-2318-perceived.gpx` | 2318 Fillmore Street Corridor (Sacramento St to Jackson St) — perceived doors & signs | Development (Target) |
| `fillmore-2318-route-from-sacramento.gpx` | Walking route: Fillmore & Sacramento Muni Stop (`14637`) -> Clay St crossing -> `2318 Fillmore St` | Development (Target Route) |
| `fillmore-2318-route-from-jackson.gpx` | Walking route: Fillmore & Jackson Muni Stop (`14624`) -> Washington St crossing -> `2318 Fillmore St` | Development (Target Route) |
| `ferry-building-perceived.gpx` | San Francisco Ferry Building | Development |
| `sf-caltrain-4th-king-perceived.gpx` | San Francisco 4th & King Caltrain Station | Held-out |
| `nyc-grand-central-perceived.gpx` | New York Grand Central Terminal (42nd St) | Held-out |
| `boston-south-station-perceived.gpx` | Boston South Station | Held-out |
| `chicago-union-station-perceived.gpx` | Chicago Union Station | Held-out |
| `seattle-king-street-perceived.gpx` | Seattle King Street Station | Held-out |
| `london-st-pancras-perceived.gpx` | London St Pancras International | Held-out |
| `paris-gare-du-nord-perceived.gpx` | Paris Gare du Nord | Held-out |
| `paris-chatelet-perceived.gpx` | Paris Chatelet - Rue de Rivoli | Held-out |

## Attribution

Imagery (c) Mapillary contributors (CC-BY-SA 4.0); Map data (c) OpenStreetMap contributors (ODbL).
