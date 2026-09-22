# Combined sensor capture

Version 0.9 adds `fusion_ready`, `fusion_reason`, and `recovery_count` to the
existing CSV. See [recovery behavior](recovery-v09.md). A `GPS_ONLY` speed state is
a held raw observation, not an IMU prediction.

The combined capture milestone records direct GPS-provider observations and raw
accelerometer samples in one event-stream CSV. Both event types use Android's
elapsed-realtime nanosecond timebase so they can be aligned offline without
inventing interpolated measurements.

The Android foreground service requests:

- GPS-provider location updates with no requested interval, minimum interval,
  distance, or batching delay;
- the default accelerometer at 100 Hz with no batching delay.

The CSV contains separate `GPS` and `ACCEL` rows. Empty columns are intentional:
a GPS row is not presented as an accelerometer sample, and an accelerometer row
is not presented as a GPS fix. Latitude and longitude are not recorded.

Starting with application version 0.5.0, estimator diagnostics are appended to
the same event rows. These include longitudinal acceleration, corrected
acceleration, estimated speed, effective bias, last innovation, speed variance,
mode, and whether a GNSS observation was accepted. Raw GNSS speed remains a
separate field and is never overwritten with an estimate.

Version 0.7.0 also records mount-calibration mode, detected orientation change,
gravity-noise estimate, forced-zero hold state, adjustment count, and the active
forward-axis vector. These fields make a mount-handling event explainable
without adding coordinates or substantially expanding the logger. Version
0.8.0 changes estimator timing and mount confirmation but does not add CSV
columns or increase logging volume.

Files are written under the application's external Documents directory in
`combined-sessions`. Stop the capture from the application before collecting a
file so its final buffered records are flushed.

For the first fixed-mount capture, record at least one stationary engine-off
session and one stationary engine-idling session. A later short drive can use
the same capture mode.

## Initial sanity result

The first indoor stationary capture on the development tablet ran for 211.86
seconds and recorded 21,157 accelerometer rows:

- effective accelerometer rate: 99.86 Hz;
- median and 95th-percentile sensor interval: 10.01 ms;
- maximum sensor interval: 20.019 ms;
- mean callback delivery delay: 1.07 ms;
- non-increasing accelerometer timestamps: 0;
- GPS rows: 0, as expected without an indoor fix;
- latitude/longitude columns: absent.

A subsequent 12-minute-56-second driving capture verified both streams together:
the accelerometer remained near 99.87 Hz and 726 GPS observations arrived near
1.00 Hz without a gap longer than 1.024 seconds after acquisition.
