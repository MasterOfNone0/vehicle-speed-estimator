# E8A tablet baseline

> Audit clarification, 2026-09-22: the operator later confirmed picking up the
> tablet during the short capture. Treat the measurements below as a handled
> bench session, not a confirmed stationary calibration. No fused rows occurred.
> The raw bench CSV was not recovered during the audit; a surviving replay
> report is retained in the [engineering evidence package](evidence/README.md).

Read-only USB inspection on 2026-09-10. Serial numbers, accounts, and coordinates
are intentionally omitted.

| Property | Reported value |
| --- | --- |
| Manufacturer / model | ZULEISY / E8A |
| Android / API | 15 / 35 |
| CPU | arm64-v8a with 32-bit ARM compatibility |
| Display | 800 × 1340, 213 dpi |
| Available app storage at inspection | Approximately 104 GB |
| RealDash | Installed |
| GPS provider | Enabled; supports speed, bearing, altitude |
| Accelerometer | Silan acc_sc7a20, advertised 3.12–200 Hz |

There is no exposed gyroscope, magnetometer, gravity, linear-acceleration,
rotation-vector, or game-rotation-vector sensor. The advertised motion/gesture,
step, and discrete screen-orientation sensors do not replace an attitude sensor.

This passes the application's Android/API and required-hardware compatibility
check, not a driving-accuracy check. The previous tablet's measurements do not
establish this tablet's speed latency or acceleration scale.

## v0.9 installation and short capture

Version 0.9.0 (code 11) installed and launched. A roughly 52-second capture
measured 5,222 accelerometer samples at 99.90 Hz, no non-monotonic sample
timestamps, and approximately 0.8 ms mean delivery age. There were 35 GPS fixes
over 34.61 seconds (0.982 Hz), with speed uncertainty supplied. A separate
20-second TCP client received 1,201 correctly framed `0x700` packets, about
60 Hz. This checks the Android publisher, not the new tablet's RealDash gauge
configuration. Capture and publishing were stopped cleanly afterward.

The accelerometer was very quiet, but GPS speed peaked at 9.24 mph. If the
tablet was stationary, this is false GNSS motion; indoor reception is not
suitable for assessing speed accuracy. Mean reported acceleration magnitude
was 8.83 m/s² (standard deviation 0.036), versus approximately 9.81 m/s² expected
at rest. A confirmed stationary outdoor check and additional poses are needed
before treating acceleration scale or bias as trustworthy. No automatic scale
correction was applied from this single pose.

The supervisor remained `GPS_ONLY` after acquiring GPS; fusion did not qualify.
The publisher correctly repeated raw GPS, including its errors. Fallback is
protection against estimator runaway, not independent validation of GPS.
Private capture data remains outside the repository.
Offline replay of this capture through the shared supervisor and publisher
checks passed: all 5,257 records were processed, and published speed stayed
equal to raw GPS after initialization. This is a consistency check, not a
speed-accuracy result.
