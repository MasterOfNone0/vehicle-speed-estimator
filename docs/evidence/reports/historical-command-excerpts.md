# Recovered command evidence — v0.9 development

Evidence E-012; selected visible command outputs from the development conversation
on 2026-09-11 UTC. Recovered on 2026-09-22. These are excerpts, not an original
complete console archive. Paths, hostname, device identifiers and unrelated
output were omitted. No private reasoning or raw conversation export is included.
The exact intermediate failing source tree was not separately preserved.

## Initial failed build/test (command exit 1)

```text
GuardedSpeedEstimatorTest > sustainedHardLaunchIsNotMistakenForRemounting FAILED
41 tests completed, 1 failed, 1 skipped
BUILD FAILED in 6m 4s
```

The inspected XML reported suite timestamp 2026-09-11T03:34:07.218Z, eight tests,
one failure, and no skipped tests in GuardedSpeedEstimatorTest. Failure excerpt:

```text
java.lang.AssertionError: GPS only; park with tablet mounted to enable fusion
```

The historical patch removed the moderate-angle-plus-noise handling branch.
This is AN-12, not an Android permission or networking failure.

## Subsequent passing suite (command exit 0)

```text
BUILD SUCCESSFUL in 2s
```

The following suite totals were printed from XML after that build:

| Suite | Timestamp UTC, 2026-09-11 | Tests | Failures / errors | Skipped |
| --- | --- | ---: | --- | ---: |
| GuardedSpeedEstimatorTest | 03:34:50.228Z | 8 | 0 / 0 | 0 |
| RealDashCanEncoderTest | 03:34:50.273Z | 2 | 0 / 0 | 0 |
| RealDashLiveTelemetrySelectorTest | 03:34:50.281Z | 10 | 0 / 0 | 0 |
| DriveCaptureReplayTest | 03:34:50.287Z | 1 | 0 / 0 | 1 |
| MountCalibrationManagerTest | 03:34:50.292Z | 7 | 0 / 0 | 0 |
| VehicleFrameTransformTest | 03:34:50.298Z | 3 | 0 / 0 | 0 |
| VelocityBiasKalmanFilterTest | 03:34:50.300Z | 10 | 0 / 0 | 0 |

Thus 40 passed and one private replay was skipped; "41 passed" would be incorrect.
A later provided-input replay was a separate execution.

## TCP client check (command exit 0)

```json
{"frames":1201,"seconds":20.00488933303859,"approx_hz":60.035316364202174,"headers":["0x11223344"],"ids":["0x700"],"speed_raw_range":[0,1488],"modes":[0,5],"flags":[12,95]}
```

The header is the decoded little-endian integer; wire bytes are 44 33 22 11.
This checks framing/rate and fallback-mode traffic, not accurate fused speed
or dashboard rendering on the new tablet.

## Provided-input replay (command exit 0)

XML timestamp 2026-09-11T03:45:00.881Z: one test, no skips/failures/errors.

```text
Replay: rows=5257 gps=35 fusedRows=0 maxGpsMps=4.13255978 maxPublishedMps=4.13255978 recoveries=0
```

The surviving report hashes are separately preserved in
[historical-replay-summary.json](historical-replay-summary.json).
The raw input was not found during this audit. Zero fused rows is significant:
the passing replay tested GPS-only behavior, not estimator accuracy.
