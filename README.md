# Vehicle Speed Estimator

An experimental Android application for characterizing tablet GNSS and motion
sensors, then producing a higher-rate vehicle-speed estimate.

> [!WARNING]
> This is an experimental speed display, not a validated vehicle instrument.
> Version 0.9.0 adds GPS fallback and recovery for the v0.8 startup/mount-handling
> failure. Mount alignment and driving accuracy still need validation.
> See [`docs/recovery-v09.md`](docs/recovery-v09.md).

The application now includes sensor characterization, a two-state vehicle-speed
estimator with delayed-GNSS correction and IMU replay, guarded stationary mount
handling, and live 60 Hz RealDash loopback output. A separate scripted-speed
publisher remains available for testing the RealDash transport without GNSS.
Polished dashboard UI and cloud logging remain out of scope.

## Local environment

- Android Studio Quail 4 (2026.1.4)
- Android SDK Platform 37.0
- Android SDK Build-Tools 36.0.0 and 37.0.0
- Android SDK Platform-Tools 37.0.1
- Minimum SDK level 26; target SDK level 35.

Local SDK paths, signing keys, APKs, and drive telemetry must not be committed.

## Status

The local Android environment and first development-tablet baseline are ready.
The current application contains separate GNSS and accelerometer
characterization probes, a combined timestamp-aligned sensor capture service,
a provisional 100 Hz velocity/bias estimator, a condition-based mount
calibration manager, a transport-only RealDash loopback test, and the live
publisher. See
[`docs/development-tablet.md`](docs/development-tablet.md) and
[`docs/gnss-rate-test.md`](docs/gnss-rate-test.md) and
[`docs/accelerometer-rate-test.md`](docs/accelerometer-rate-test.md) and
[`docs/combined-sensor-capture.md`](docs/combined-sensor-capture.md) and
[`docs/estimator-v1.md`](docs/estimator-v1.md) and
[`docs/mount-calibration-v08.md`](docs/mount-calibration-v08.md) and
[`docs/realdash-loopback-test.md`](docs/realdash-loopback-test.md) and
[`docs/realdash-live-output.md`](docs/realdash-live-output.md).

Version 0.9.0 adds guarded startup, GPS re-anchoring, degraded-output fallback,
and suspension after large tablet movement. The output source is visible in the
live screen and status flags. See the [recovery behavior](docs/recovery-v09.md)
and [new E8A tablet baseline](docs/tablet-e8a.md).

## Engineering evidence and revision history

The [engineering evidence package](docs/evidence/README.md) reconstructs the
known revisions, failures, lessons, test campaigns and requirement-to-test links.
It includes fresh verification results and a separate AI-training adaptation guide.
Earlier source releases and most private drive recordings were not preserved;
the package marks historical reports and evidence gaps rather than inventing them.
Its light assurance-style organization is **not DO-178C compliance**.

Validate the evidence links and artifact hashes with:

```shell
python3 tools/check_evidence.py
```

## Build

```shell
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`. Build output is
intentionally ignored by Git. On other operating systems, use a Java 21 or
newer installation instead of the macOS Android Studio runtime path above.

## License

No license has been selected. Public visibility does not grant permission to
copy, modify, or redistribute the project; add an explicit license before
inviting outside reuse or contributions.
