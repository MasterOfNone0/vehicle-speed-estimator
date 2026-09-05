# RealDash loopback test

This transport-only test verifies that RealDash can receive RealDash-CAN data
from another application on the same Android tablet. It does not use GNSS or an
estimator. For the live estimator path added in v0.6, see
[`realdash-live-output.md`](realdash-live-output.md).

The Android test publisher listens only on `127.0.0.1:35000` and sends CAN ID
`0x700` as a fixed-size RealDash-CAN 44 frame. It publishes a repeating scripted
drive with stopped, city acceleration/cruise, highway acceleration/cruise,
braking, and stopped segments:

- estimated speed changes at 60 Hz;
- simulated raw GNSS speed changes at 1 Hz and includes a small deterministic
  GNSS-like error;
- GNSS age runs from 0 to 999 ms;
- mode is 2 and flags is 1.

Use `realdash/vehicle-speed-estimator.xml` as the connection's custom channel
description file. The imported values appear in RealDash's ECU Specific input
category with the `Vehicle Speed Estimator:` prefix.

## Tablet procedure

1. Open Vehicle Sensor Probe and select **Open RealDash Connection Test**.
2. Select **Start Publisher** and allow notifications if Android asks.
3. In RealDash, add a RealDash-CAN Wi-Fi/LAN connection using IP address
   `127.0.0.1` and port `35000`.
4. Select **Custom Channel Description File** and choose
   `vehicle-speed-estimator.xml` from Downloads.
5. Connect and use the CAN monitor, if available, to confirm frame `0x700` is
   updating.
6. Bind a gauge to **Vehicle Speed Estimator: Estimated Speed** under
   **ECU Specific**.
7. Confirm the gauge follows the scripted drive smoothly from stopped to city
   speed, highway speed, and back to stopped.
8. Return to Vehicle Sensor Probe and select **Stop Publisher**.

If RealDash cannot connect to `127.0.0.1`, record the exact error before trying
another address. That result determines whether the production transport can
use loopback or must bind to a normal tablet network interface.

## Development-tablet result

Tested successfully on the Alldocube iPlay60 mini on 2026-09-02:

- RealDash connected to the publisher through `127.0.0.1:35000` on the same
  tablet.
- RealDash CAN Monitor displayed frame `0x700` at approximately 21 frames per
  second for the publisher's 20 Hz target.
- The displayed payload matched the estimated speed, stepped 1 Hz raw speed,
  GNSS age, mode, and flags layout in this document.

An initial implementation used `InetAddress.getLoopbackAddress()`, which
selected IPv6 `::1` on this tablet and could not be reached by RealDash when it
was configured for IPv4 `127.0.0.1`. The publisher therefore binds the IPv4
loopback address explicitly.

A follow-up 60 Hz scripted-drive demo also connected successfully. A RealDash
gauge bound to `Vehicle Speed Estimator: Estimated Speed` followed the stopped,
city-speed, highway-speed, and braking sequence while the original 1 Hz channel
remained independently available for comparison.
