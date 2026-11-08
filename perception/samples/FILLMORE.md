# 2318 Fillmore Street (Smith-Kettlewell Eye Research Institute) — Field Preview Summary

## 1. Geocoded Target & OSM Baseline
- **Building Footprint**: OSM `way/160705972` (`name="The Smith-Kettlewell Eye Research Institute"`, `addr:housenumber=2318`, `addr:street="Fillmore Street"`, `wikidata=Q30279651`), centroid `37.7911743, -122.4339323`.
- **Front Facade Office Node**: OSM `node/13639682002` (`name="Smith-Kettlewell Eye Research Institute"`, `office=research`, `wheelchair=yes`) on the western facade facing Fillmore Street at `37.7911624, -122.4341427` (between Clay Street and Washington Street).
- **Key OSM Finding**: `way/160705972` has **0 `entrance=*` nodes in OpenStreetMap**. Standard OSM-only navigation knows the building polygon and office POI pin, but has no tagged doorway node on the Fillmore Street sidewalk.
- **Corridor Coverage**: Fillmore Street from Sacramento Street (`37.78999, -122.43414`) to Jackson Street (`37.79222, -122.43462`), radius `185 m` (`10` Mapillary 360° stations, `18` planned facade views).

## 2. Nearest Muni Stops & Exported GPX Files
1. `perception/samples/gpx/fillmore-2318-perceived.gpx` — All perceived doors and storefront signs along the Fillmore corridor ordered south-to-north.
2. `perception/samples/gpx/fillmore-2318-route-from-sacramento.gpx` — Sidewalk walking route (`135 m` northbound) from **Fillmore St & Sacramento St** (`node/6368210990`, Muni Routes 22 & 24, Stop `14637`) across Clay Street to `2318 Fillmore Street`.
3. `perception/samples/gpx/fillmore-2318-route-from-jackson.gpx` — Sidewalk walking route (`125 m` southbound) from **Fillmore St & Jackson St** (`node/6368210995`, Muni Routes 22 & 24, Stop `14624`, past `Washington St & Fillmore St` Stop `16916`) across Washington Street to `2318 Fillmore Street`.

## 3. Perceived Entrances & Signs Found Along the Corridor
| ID | Category | Name / Text | Coordinates (`lat, lon`) | Mapillary `imageId` | `sam-3.1` Conf | Verifier | Method |
|---|---|---|---|---|---|---|---|
| `perc-door-1` | `DOOR` | Smith-Kettlewell Eye Research Institute Entrance | `37.791117, -122.434186` | `1184002609210177` | `0.93` | `YES` | `MULTI_RAY_TRIANGULATION_FACADE_SNAP` |
| `perc-door-2` | `DOOR` | Islais Entrance | `37.791251, -122.434153` | `1184002609210177` | `0.91` | `YES` | `MULTI_RAY_TRIANGULATION_FACADE_SNAP` |
| `perc-door-5` | `DOOR` | Smith-Kettlewell Eye Research Institute Entrance | `37.791205, -122.434203` | `1116762269018404` | `0.93` | `YES` | `MULTI_RAY_TRIANGULATION_FACADE_SNAP` |
| `perc-door-7` | `DOOR` | Le Labo Entrance | `37.790393, -122.434036` | `1167706340838604` | `0.91` | `YES` | `FACADE_SNAP` |
| `perc-door-15` | `DOOR` | Reformation Entrance | `37.791508, -122.434257` | `665715115056494` | `0.93` | `YES` | `FACADE_SNAP` |
| `perc-door-21` | `DOOR` | Taco Primo Entrance | `37.791153, -122.434455` | `1116762269018404` | `0.91` | `YES` | `FACADE_SNAP` |
| `perc-door-12` | `DOOR` | Detected Entrance | `37.791721, -122.434137` | `832869624430867` | `0.89` | `YES` | `FACADE_SNAP` |
| `perc-door-18` | `DOOR` | Tryst Entrance | `37.790782, -122.434432` | `870175101020949` | `0.86` | `YES` | `FACADE_SNAP` |
| `perc-door-4` | `DOOR` | Islais Entrance | `37.791269, -122.434221` | `1116762269018404` | `0.73` | `YES` | `MULTI_RAY_TRIANGULATION_FACADE_SNAP` |
| `perc-door-28` | `DOOR` | Margaret O'Leary Entrance | `37.791833, -122.434333` | `982812832518604` | `0.81` | `YES` | `FACADE_SNAP` |
| `perc-door-25` | `DOOR` | Paige Entrance | `37.79031, -122.434244` | `1167706340838604` | `0.79` | `YES` | `SINGLE_RAY_FOOTPRINT_FACADE_SNAP` |
| `perc-door-10` | `DOOR` | Nest Entrance | `37.790818, -122.434072` | `9261504077257367` | `0.79` | `YES` | `FACADE_SNAP` |
| `perc-door-3` | `DOOR` | Islais Entrance | `37.791298, -122.434216` | `1116762269018404` | `0.86` | `INSUFFICIENT` | `FACADE_SNAP` |
| `perc-door-9` | `DOOR` | Chouquet's Entrance | `37.791826, -122.434557` | `982812832518604` | `0.77` | `YES` | `FACADE_SNAP` |
| `perc-door-29` | `DOOR` | Margaret O'Leary Entrance | `37.791771, -122.434314` | `982812832518604` | `0.71` | `YES` | `FACADE_SNAP` |
| `perc-sign-26` | `SIGN` | Sign: PAIGE | `37.790338, -122.43425` | `1167706340838604` | `0.68` | `YES` | `FACADE_SNAP` |
| `perc-door-13` | `DOOR` | Detected Entrance | `37.791726, -122.434047` | `832869624430867` | `0.68` | `YES` | `FACADE_SNAP` |
| `perc-door-22` | `DOOR` | Pets Unlimited Entrance | `37.791238, -122.434441` | `1116762269018404` | `0.65` | `YES` | `SINGLE_RAY_FOOTPRINT_FACADE_SNAP` |
| `perc-door-23` | `DOOR` | Taco Primo Entrance | `37.791189, -122.434454` | `1116762269018404` | `0.63` | `YES` | `FACADE_SNAP` |
| `perc-door-8` | `DOOR` | Hair Fairies Entrance | `37.79042, -122.434045` | `1167706340838604` | `0.62` | `YES` | `FACADE_SNAP` |
| `perc-sign-27` | `SIGN` | Sign: PAIGE | `37.79037, -122.434257` | `1167706340838604` | `0.62` | `YES` | `FACADE_SNAP` |
| `perc-door-17` | `DOOR` | Reformation Entrance | `37.791613, -122.434153` | `665715115056494` | `0.59` | `YES` | `FACADE_SNAP` |
| `perc-door-24` | `DOOR` | Mayflower Market Entrance | `37.792304, -122.434426` | `714805729602458` | `0.63` | `INSUFFICIENT` | `FACADE_SNAP` |

## 4. What Is Uncertain & What the Owner Should Check in Person
1. **Exact Main Entrance vs Side Service Door on `way/160705972` (`2318 Fillmore St`)**:
   - Because `way/160705972` has a wide western frontage (`~25 m` along Fillmore Street) with recessed glass entry doors and adjacent ground-floor bays, verify while standing on the east sidewalk whether the primary recessed glass entrance aligns within `2–3 m` of `37.791117, -122.434186` and whether there is an intercom/push-plate button on the right or left jamb.
2. **Street-Car / Bus Occlusion & Awning Shadows**:
   - Mapillary 360° panoramas on Fillmore Street are captured from the street carriageway (`~7–11 m` west of the east sidewalk building line). Parked vehicles and storefront awnings on the 2200–2300 blocks partially occlude lower door thresholds, which can shift single-ray facade intersections by `1.5–3.0 m` along the wall.
3. **Clay Street & Washington Street Crossing Callouts**:
   - When walking `fillmore-2318-route-from-sacramento.gpx` and `fillmore-2318-route-from-jackson.gpx`, check whether Soundscape's waypoint arrival callouts trigger cleanly at both curb ramps of Clay Street (`37.790728, -122.434195` -> `37.790848, -122.434218`) and Washington Street (`37.791612, -122.434402` -> `37.791485, -122.434368`).

## 5. Attribution
- Imagery (c) Mapillary contributors (CC-BY-SA 4.0); Map data (c) OpenStreetMap contributors (ODbL).
