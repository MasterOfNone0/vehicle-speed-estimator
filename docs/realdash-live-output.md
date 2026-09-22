# Live RealDash output

Version 0.9 changes invalid-estimate handling: the speed channel uses fresh GPS
fallback or zero/unavailable. The source is explicit on the app's live screen,
mode code 5, and flag bit 6. Bit 0 now describes the published source's usability.
Connection and gauge mapping are unchanged. See [v0.9 recovery](recovery-v09.md).
> [!WARNING]
> This is an experimental speed display. A normal RealDash gauge does not
> interpret the validity flag: zero/unavailable looks like zero speed unless
> you also display status. Driving accuracy still needs validation; see
> [`known-limitations.md`](known-limitations.md).

Version 0.6.0 connected the combined GNSS/accelerometer estimator to the
same-device RealDash transport proven by the scripted loopback test. Version
0.8.0 adds delayed-GNSS correction and stronger stationary mount-movement
confirmation without changing the connection, XML, CAN ID, or gauge binding.

```text
Android GPS + accelerometer
            |
            v
CombinedCaptureService (sensor ownership, estimator, CSV)
            |
            v
RealDashLiveService (60 Hz publisher)
            |
            v
127.0.0.1:35000, CAN ID 0x700
            |
            v
RealDash custom XML and gauge
```

The combined capture service remains the only owner of GNSS, accelerometer, and
estimator state. The publisher only takes snapshots and encodes them. The
scripted publisher remains separate and cannot run on the shared port at the
same time.

## Tablet procedure

1. Mount the tablet in the orientation used for the calibration drive and keep
   the vehicle parked.
2. Open Vehicle Sensor Probe and select **Open Live RealDash Output**.
3. Select **Start Live Output**. This starts combined CSV capture and the 60 Hz
   loopback publisher together.
4. Wait for GPS and a quiet mounted stop to enable fusion, then switch to
   RealDash. If fusion is not ready, fresh GPS still reaches the speed channel.
5. Use the existing RealDash-CAN connection at `127.0.0.1:35000`, the existing
   `vehicle-speed-estimator.xml`, and the gauge already bound to
   **Vehicle Speed Estimator: Estimated Speed**.
6. Confirm RealDash connects and receives frame `0x700`. A parked test is enough
   to prove the live data path. A later drive is needed to evaluate estimator
   behavior under acceleration, braking, turns, and grade.
7. Return to Vehicle Sensor Probe and select **Stop Live Output**. This stops
   both services and closes the CSV.

The current XML does not need to be edited or re-imported after upgrading from
the scripted test. Both publishers intentionally use the same frame layout.

## Frame payload

CAN ID `0x700` carries eight payload bytes:

| Bytes | Value | Encoding |
| --- | --- | --- |
| 0-1 | Published speed (fused or GPS fallback) | unsigned little-endian, km/h x 100 |
| 2-3 | Raw Android GPS speed | unsigned little-endian, km/h x 100 |
| 4-5 | Raw GPS age | unsigned little-endian milliseconds |
| 6 | Estimator mode | mode code below |
| 7 | Status flags | bit field below |

Mode codes are stable parts of the v0.6 XML contract:

- `0`: uninitialized
- `1`: GPS correction
- `2`: predicting
- `3`: GPS degraded
- `4`: stationary
- `5`: GPS-only fallback (added in v0.9)

Status flags are:

- bit 0 (`0x01`): published source usable (fused or GPS fallback)
- bit 1 (`0x02`): raw GPS is fresh
- bit 2 (`0x04`): combined capture active
- bit 3 (`0x08`): mounting calibration is provisional
- bit 4 (`0x10`): estimator update is fresh
- bit 5 (`0x20`): mount calibration and fusion eligibility are ready
- bit 6 (`0x40`): GPS fallback

The publisher sends frames even while unavailable so the connection remains
observable. It uses fused speed only when freshness, eligibility, and the
independent GPS disagreement checks pass; otherwise it uses fresh credible GPS.
If neither is available, speed is zero and bit 0 clears. RealDash does not
automatically interpret that bit for a normal speed gauge; the app's live
screen and the separate Flags channel expose it for engineering checks.

## Historical mount handling in v0.8

The following describes v0.8, not v0.9's eligibility or output policy. The
[v0.9 supervisor](recovery-v09.md) now wraps this calibration manager and filter.

The calibration manager establishes an orientation anchor only after GNSS has
confirmed that the vehicle is stationary. A change that was already present
when the vehicle stopped is therefore treated as road grade, not tablet
movement. If orientation changes materially, a later low-speed GNSS fix must
confirm that the car remained stopped before the manager enters `MOUNT_MOVED`.
This prevents a hard launch between 1 Hz fixes from being treated as tablet
movement. During a confirmed handling event, RealDash output is invalid and the
estimate is held at zero while stationary GPS is fresh. The new orientation must
remain quiet for two seconds, followed by one second of bias settling, before
the manager returns to `READY`.

Moving GPS measurements use a one-second delayed correction followed by bounded
IMU replay. RealDash still receives the current estimate at 60 Hz; it does not
receive a deliberately delayed speed signal.

Rotation around gravity cannot be recovered from an accelerometer alone. Large
yaw changes still require straight-line driving evidence or an explicit
calibration in a future version.
