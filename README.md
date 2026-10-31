# Liquid Battery

Glass-styled battery monitor for your phone and Bluetooth devices, with one unified home-screen widget.

## Build (no Android Studio needed)
1. Put this folder in a GitHub repo (branch `main`).
2. The workflow `.github/workflows/build.yml` runs on every push and publishes `LiquidBattery.apk` on the repo's **Releases** page.
3. Download the APK, install it, open the app, allow the Bluetooth ("nearby devices") permission.
4. Long-press the home screen → Widgets → Liquid Battery. Resize it: 2x2 = small, wider = medium, tall = large.

Or in Android Studio: open the folder, Run. Needs JDK 17.

## How battery data is obtained (never simulated)
Tried in this order for each *connected* paired device:
1. Value reported by the Android Bluetooth stack (hidden API, best effort, may be blocked on newer Android).
2. Headset battery report over the HFP protocol (`+IPHONEACCEV`, sent by some earbuds/headsets).
3. Standard GATT Battery Service 0x180F / characteristic 0x2A19 (BLE devices that implement it).

If none work, the app shows "Battery unavailable" (connection state is shown separately) and, optionally, the last real reading with its timestamp.


