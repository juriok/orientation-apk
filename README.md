# Orientacija

Android orienteering app for Slovenia. Shows your GPS position on Slovenian state
topographic maps, and on your own maps — including photographs of paper sheets, which the
app georeferences so your live position projects onto them.

[![Download APK](https://img.shields.io/badge/download-Orientacija%202.0-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/juriok/orientation-apk/raw/main/Orientacija-2.0.apk)

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![APK 5.3 MB](https://img.shields.io/badge/APK-5.3%20MB-blue)
![Maps CC BY 4.0](https://img.shields.io/badge/map%20data-GURS%20CC%20BY%204.0-lightgrey)

## Install

1. Download **[Orientacija-2.0.apk](https://github.com/juriok/orientation-apk/raw/main/Orientacija-2.0.apk)** on your phone.
2. Open it. Android will ask you to allow installs from this source — this is normal for an
   app that does not come from Play; grant it for your browser or file manager.
3. On first run, allow **location** access. Choose *Precise*, and *While using the app* is
   enough unless you record tracks with the screen off, which needs *Allow all the time*.

Requires Android 8.0 (API 26) or newer. Needs a data connection the first time you visit an
area, to fetch map tiles — use **Offline download** beforehand if you are heading somewhere
without signal.

## Map layers

| Layer | Source | Notes |
|---|---|---|
| **DTK 50** | GURS, composite | 1:50,000 up close, overview sheets when zoomed out. See below. |
| **Ortofoto** | GURS `SI.GURS.ZPDZ:DOF025` | 25 cm aerial imagery. Very useful when matching a photographed map to the ground. |
| **OpenTopoMap** | opentopomap.org | Worldwide fallback; keeps working past the border and if GURS is down. |

GURS data is CC BY 4.0. The services publish EPSG:3857, so each XYZ tile maps to one WMS
`GetMap` call and drops straight into a normal tile pyramid — see `map/MapLayers.kt`.

### Why DTK 50 is a composite layer

Each GURS layer is only drawn inside its own scale band, and **outside that band the server
returns a blank white image rather than an error** — so there is nothing for the client to
detect or fall back on. Probed against the live service:

| Layer | Renders at |
|---|---|
| DPK 1000 / DPK 750 | z9–13 |
| DPK 500 | z11–13 |
| DPK 250 | z12–13 |
| DTK 50 | z14–16 |
| DOF 025 (ortho) | z9–18 |

A plain DTK50 tile source therefore turns the screen white the moment you zoom out past
z13, and DPK 250 offered on its own is blank at every zoom you would normally navigate at.
`GursTopoTileSource` picks the right layer per tile, giving an unbroken map from country
view down to 1:50,000. Nothing in the catalogue renders below z9, so the map clamps there
rather than letting you zoom out into nothing.

### About DTK 25

DTK 25 is **not available as a live service**. GURS no longer lists it among maintained
state map products; the surviving rasters date from 1996 and are drawn in the legacy
D48/GK (EPSG:3912) datum. The one third-party tile server that used to publish it
(`maps.komelt.dev`) is offline.

If you have the sheets as image files, import them through **Lastne karte** — the same
pipeline as any custom map. The app can display D48/GK coordinates for reading them.

## The custom-map feature

Import a map image or photograph a paper sheet, then calibrate it against a reference map
by matching control points. Once calibrated, it becomes a layer with your live position on
it, and the base map can be switched off entirely (**Sloji → Brez podlage**).

### Why control points instead of dragging

Dragging a photo over the map applies only scale, rotation and translation — 4 degrees of
freedom. A photo of a paper map is taken at an angle, so its distortion is projective:
8 degrees of freedom.

Matching points lets the app solve the full transform:

| Points | Model | Corrects |
|---|---|---|
| 2 | similarity | position, scale, rotation, mirroring |
| 3 | affine | + shear and non-uniform scale |
| **4+** | **homography** | **+ perspective from the camera angle** |

Simulated on a 4.9 × 3.5 km sheet photographed with a 10.7% perspective stretch (a modest
camera angle), a perfectly executed drag-align is still **≈330 m out** at its worst point.
Four control points reduce that to zero. Beyond four, points are fitted by least squares.

### How the error figure is computed

The app reports a **cross-validated** expected error, not the raw residual at the control
points. This matters more than it sounds: the raw residual measures how well the transform
reproduces the points it was built from, which it can always do well — at the model minimum
it is exactly zero no matter how wrong the map is. Measured across random trials, the raw
residual understated the true error across the sheet by three- to six-fold.

Leave-one-out cross-validation refits the transform without each point in turn and measures
the error at the held-out point, which is an honest estimate of what happens away from your
control points.

Two safeguards sit behind that number:

- A **reflection-aware** similarity fit. Image y runs downward and world y runs upward, so
  the true transform always mirrors. A rotation-only fit still passes through two control
  points exactly — reporting zero error — while mirroring the rest of the sheet. That was a
  5.3 km error at zero apparent residual.
- A **vanishing-line check**. A homography fitted to noisy points can put its singularity
  inside the sheet, sending part of the image to infinity. Over 400 trials this check never
  rejected a good fit and caught errors up to 3600 km. When it trips, the app falls back to
  an affine fit, which loses perspective correction but stays bounded.

### Getting a good calibration

- Use **6 or more** points where you can. Four is the minimum for perspective correction,
  but at exactly four the fit is exactly determined and its accuracy cannot be measured at
  all — the app will tell you so rather than show a reassuring zero.
- Spread them into the corners. Points bunched in the middle leave the edges unconstrained.
- Pick unambiguous features: road junctions, building corners, summits, bridges.
- Switch the reference to **Ortofoto** when matching man-made features — often easier than
  reading contours.
- Photograph the sheet flat and in even light. The maths handles camera angle, but not a
  curled or folded page — that is not a projective distortion.
- Aim for an expected error under ~25 m. Over 50 m the app warns you.

## Other features

- **Lidar shaded relief** — blends the GURS lidar relief over any base layer with a
  MULTIPLY composite, so contours and true terrain shape read together. Strength is
  adjustable. Available from z11 down.
- **Track recording** — runs in a foreground service so it keeps logging with the screen
  off and the phone pocketed. Autosaves every 20 points, so a killed process costs seconds
  rather than the whole run. Distance and ascent filter GPS jitter rather than summing it.
- **Course mode** — an ordered sequence of controls built from your waypoints. Shows leg
  number, distance, azimuth and how far to turn; punches automatically within 25 m (10–40 m
  configurable) with a vibration, and records splits.
- **Map rotation** — rotate-to-heading or locked north-up.
- **Offline download** — cache the visible area for use with no signal.
- **Coordinates** — tap the top panel to cycle WGS84 decimal, D96/TM, MGRS, degrees/minutes, D48/GK.
- **Waypoints** — long-press the map, or the **+** button. GPX import/export.
- **Target bearing** — tap a waypoint marker to set it as target.
- **Base map dimming** — a middle setting between full base map and none, so a custom sheet
  stands out while the surrounding terrain stays readable as context.

## Building

Requires JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

```bash
gradle assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

For a release build, create `keystore.properties` in the project root:

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Neither that file nor the keystore is in this repo — both are gitignored and kept locally.
Without them, `assembleRelease` falls back to the debug key so the APK still installs.

## Layout

```
geo/Transform2D.kt        control-point fitting: similarity / affine / homography
geo/CoordinateSystems.kt  D96/TM, D48/GK, UTM 33N, MGRS
geo/GeoMath.kt            Web Mercator, distance, bearing
map/MapLayers.kt          WMS-as-XYZ tile sources
map/OfflineDownloader.kt  area pre-caching
custom/CalibrationActivity.kt  two-pane control-point UI
custom/CalibratedMapOverlay.kt draws the warped image onto the map
custom/ImagePointView.kt       pan/zoom image view reporting image-pixel taps
```
