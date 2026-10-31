# Liquid Battery

Glass-styled battery monitor for your phone and Bluetooth devices, with one unified home-screen widget.


## How battery data is obtained (never simulated)
Tried in this order for each *connected* paired device:
1. Value reported by the Android Bluetooth stack (hidden API, best effort, may be blocked on newer Android).
2. Headset battery report over the HFP protocol (`+IPHONEACCEV`, sent by some earbuds/headsets).
3. Standard GATT Battery Service 0x180F / characteristic 0x2A19 (BLE devices that implement it).

If none work, the app shows "Battery unavailable" (connection state is shown separately) and, optionally, the last real reading with its timestamp.


