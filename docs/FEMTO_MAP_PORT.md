# Femto map-style attribution and migration notes

CarTaxiLauncher uses the **Femto Car Launcher** light/dark MapLibre JSON styles from
https://github.com/seijikohara/femto-car-launcher (branch `develop`, retrieved 2026-10-20).
Femto Car Launcher is © its respective contributors and distributed under Apache License 2.0.
The original project and full license are at https://github.com/seijikohara/femto-car-launcher/blob/develop/LICENSE .

Third-party map/style notices from the original project apply:
- OpenStreetMap database © OpenStreetMap contributors, ODbL 1.0: https://www.openstreetmap.org/copyright
- OpenFreeMap tile service: https://openfreemap.org/
- OpenMapTiles style and design attributions: https://github.com/openmaptiles
- Any attribution embedded in the copied JSON `sources` must be retained.
- OpenMapTiles Positron / Dark Matter visual design: CC BY 4.0 where applicable (see Femto's `app/config/licenses/openmaptiles_style.json`).

The two imported `femto_light.json` and `femto_dark.json` are **style data**.
No proprietary Femto icons, raster assets or Google Maps integration have been copied.
The map is still rendered by the existing **native MapLibre Android** map instance,
because CarTaxiLauncher is an Android Auto navigation Surface app whereas Femto is
an Android head-unit HOME launcher with a WebView + WebGL MapLibre GL JS backend.
Transplanting the entire Femto WebView implementation would need significant
JavaScript build/runtime/bridge work and Android Auto host compatibility testing.

UI: Distance, AVG, Vị trí readouts follow Femto's general information hierarchy.
Trip distance is filtered from GPS movement; AVG is distance over tracked elapsed
GPS time, including stopped time while receiving fixes. Vị trí currently reports
the recognized **road name**, not an address from reverse geocoding. Trip data is
stored locally and never uploaded. The ↺ touch target resets the trip.

This branch is an **experimental style and dashboard port**; a passing compiler
does not establish visual accuracy/performance on vehicle hardware. Preserve the
previous release APK until tested on the head unit.
