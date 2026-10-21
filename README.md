# CarDash MVP 0.2

Tesla-style Android car dashboard MVP for an Android tablet.

## Current UI
- landscape fullscreen interface
- left driving cluster with speed, RPM, fuel, range, coolant, battery voltage and consumption
- road/car visualization in the cluster
- large right-side map area (OpenStreetMap WebView)
- turn instruction and speed-limit overlays
- media card and YouTube entry point
- draggable YouTube picture-in-picture window
- bottom navigation inspired by modern EV infotainment systems

## Vehicle data
The app supports paired Bluetooth Classic OBD/ELM327-style adapters through `ObdClient`.
It currently reads standard PIDs for speed, RPM, coolant temperature, battery voltage, fuel level/fuel rate where supported, and estimates range/consumption where possible.

## Power / ACC behavior
- power disconnected: waits 8 seconds, enters Parking Mode, dims the screen and pauses WebViews/OBD
- power restored: wakes the screen, restores brightness and reconnects the last OBD device

## Demo mode
The app starts with simulated vehicle data so the UI can be tested without a car or OBD adapter.

## Build APK without Android Studio
A GitHub Actions workflow is included at `.github/workflows/build-apk.yml`. Every push to `main` builds a debug APK and uploads it as the `CarDash-MVP-debug` artifact.

## Diagnostics mode (0.3)
Open **Діагностика** from the bottom navigation to inspect tablet/app details, selected Bluetooth OBD device, live vehicle state and raw OBD TX/RX traffic. Use **EXPORT LOG** to save a `.txt` file and share it for debugging.
