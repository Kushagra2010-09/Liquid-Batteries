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

## Limitations (honest list)
- Many devices (a lot of smartwatches and cheap earbuds) report battery only to their own companion app through a proprietary protocol. Liquid Battery cannot read these.
- **boAt Lunar Vista: compatibility is untested.** Use Devices → *Probe* on the watch. It lists the GATT services the watch exposes. If `0x180F` is present, battery will work; if not, it needs the manufacturer protocol.
- Widget updates: Android limits background work. Periodic refresh is at best every 15 minutes, plus a refresh on Bluetooth connect/disconnect, charger events, opening the app, and tapping the widget's refresh arrow.
- Widget transparency is exact on Android 12+. On Android 9-11 the widget uses a fixed translucent background.
- True backdrop blur is not possible inside widgets; the glass look is layered translucency with highlights.
- Widget settings (theme, transparency, accent, device list) apply to all Liquid Battery widgets.
- This project was written without being compiled or run on hardware. First build may need small fixes.
