# Verification record and test catalog

## Fresh execution — 2026-09-22

B09 was tested without runtime edits during the evidence audit. Command:

```sh
env -u DRIVE_CAPTURE_CSV ./gradlew testDebugUnitTest lintDebug assembleDebug \
  --rerun-tasks --no-configuration-cache --console=plain
```

Use an appropriate JAVA_HOME (this run used Android Studio's bundled Java 25).
Gradle 9.4.1, Android Gradle Plugin 9.2.0, min SDK 26 / target 35 / compile 37.
Result: exit 0; BUILD SUCCESSFUL, 31 seconds, all 49 actionable tasks executed.
**40 tests passed, 1 skipped, 0 failures.** The skipped test requires private
telemetry which was not recovered. No tablet install or device run occurred in
this audit. Build success does not establish driving behavior.

Lint completed with **115 warnings and no errors**: 97 SetTextI18n, five each
DiscouragedApi and LockedOrientationActivity, three ObsoleteSdkInt, and one each
AndroidGradlePluginVersion, DataExtractionRules, MissingApplicationIcon,
OldTargetApi and UnnecessaryRequiredFeature. These are not silently waived as
acceptable production findings. Most are diagnostic-UI/maintenance issues; the
backup/data-extraction warning deserves privacy review before distributing logs.

The [sanitized report](reports/current-verification.json) records each method's
outcome, suite timestamp, report hashes and debug APK hash. The APK is not committed.
Hashes identify artifacts, not a reproducible or authenticated build. Duplicate
filesystem-conflict copies of XML were excluded; only canonical suite filenames
from the new run were counted.

## Test catalog

Every row is a real B09 @Test method. Exact assertions and tolerances are in the
linked source; prose states scope without replacing them. Fixtures are synthetic
unless explicitly labelled private replay. Originating failures are mapped in
[anomalies-and-lessons.md](anomalies-and-lessons.md).

### VehicleFrameTransformTest

Source: [VehicleFrameTransformTest.kt](../../app/src/test/java/dev/vehiclespeed/gnssprobe/estimator/VehicleFrameTransformTest.kt).

| ID / method | Assertion scope | Requirements / current result |
| --- | --- | --- |
| T-001 — `normalizesForwardAxis` | Nonunit vector becomes unit length and projects the chosen parallel sample. | R-007; passed |
| T-002 — `developmentTransformRejectsStationaryGravityReference` | One historical reference-gravity vector projects near zero; not proof of new-mount alignment. | R-007; passed |
| T-003 — `reorientationPreservesForwardProjectionAcrossGravityChange` | One specified pitch/gravity change preserves a near-zero gravity projection; yaw untested. | R-007, R-016; passed |

### VelocityBiasKalmanFilterTest

Source: [VelocityBiasKalmanFilterTest.kt](../../app/src/test/java/dev/vehiclespeed/gnssprobe/estimator/VelocityBiasKalmanFilterTest.kt).

| ID / method | Assertion scope | Requirements / current result |
| --- | --- | --- |
| T-004 — `remainsUninitializedUntilGpsSpeedArrives` | Acceleration alone does not initialize velocity. | R-004; passed |
| T-005 — `accelerationChangesVelocityBeforeNextGpsObservation` | Specified acceleration predicts increasing velocity between GPS updates. | R-005; passed |
| T-006 — `gpsCorrectionPullsPredictionAndLearnsPositiveBias` | GPS correction moves state toward measurement and updates bias. | R-006; passed |
| T-007 — `repeatedGpsCorrectionsLearnSustainedEffectiveBias` | Repeated prescribed offsets drive effective-bias adaptation, not physical source identification. | R-006, R-025; passed |
| T-008 — `confirmedStopConvergesExactlyToZeroAndLaunchExitsStationaryMode` | Synthetic stop converges to zero, launch acceleration exits zero hold. | R-008; passed |
| T-009 — `rejectsVeryUncertainGpsAndNeverCreatesReverseSpeed` | Excessive uncertainty rejected and forward speed remains nonnegative. | R-022, R-029, R-030; passed |
| T-010 — `externalStationaryHoldSuppressesMountMovementAndRelearnsBias` | Standalone filter forced-hold path; not the current v0.9 wrapper integration path. | R-008; passed |
| T-011 — `delayedGpsCorrectsHistoricalStateThenReplaysAccelerationToNow` | Configured artificial delay corrects retained historical state and repropagates. | R-009; passed |
| T-012 — `zeroDelayRetainsConventionalCurrentStateCorrectionForComparison` | Zero delay retains conventional correction behavior. | R-009; passed |
| T-013 — `explicitReanchorDiscardsOldBiasCovarianceAndReplayHistory` | Re-anchor discards earlier filter memory and allows a fresh starting state. | R-020, R-022; passed |

### MountCalibrationManagerTest

Source: [MountCalibrationManagerTest.kt](../../app/src/test/java/dev/vehiclespeed/gnssprobe/estimator/MountCalibrationManagerTest.kt).

| ID / method | Assertion scope | Requirements / current result |
| --- | --- | --- |
| T-014 — `stationaryMountChangeIsDetectedHeldAndReorientedAfterItStabilizes` | Specified 20-degree pitch change at a stop leads to hold then reorientation. | R-016; passed |
| T-015 — `gravityChangeThatExistsBeforeStoppingIsTreatedAsRoadGrade` | Preexisting 20-degree grade before a stop does not trigger remount; not changing-hill accuracy. | R-016, R-025; passed |
| T-016 — `apparentGravityChangeWhileGpsIsMovingDoesNotRecalibrate` | Moving GPS prevents the specified apparent-gravity change being treated as stationary recalibration. | R-016, R-025; passed |
| T-017 — `movingGpsCancelsAStationaryMountSuspicionAsALaunch` | Later moving fix cancels unconfirmed stopped-handling suspicion. | R-008, R-017; passed |
| T-018 — `mountChangeWaitsForALaterStationaryGpsFixBeforeHoldingZero` | No zero hold until a later near-zero observation confirms candidate. | R-008, R-016, R-017; passed |
| T-019 — `confirmedMountChangeIsNotCancelledByLaterVehicleMotion` | Confirmed change remains unresolved after motion but hold-at-zero is released. | R-016; passed |
| T-020 — `passingThroughOriginalAngleWhileBeingHandledDoesNotBecomeReady` | Noisy return through old angle cannot bypass settling checks. | R-016; passed |

### GuardedSpeedEstimatorTest

Source: [GuardedSpeedEstimatorTest.kt](../../app/src/test/java/dev/vehiclespeed/gnssprobe/estimator/GuardedSpeedEstimatorTest.kt).

| ID / method | Assertion scope | Requirements / current result |
| --- | --- | --- |
| T-021 — `seatStartupAndRemountWhileDrivingCannotRunAwayOrLockOutGps` | Synthetic long moving seat-start/remount stays GPS-only, accepts fixes, then qualifies at a stop. | R-004, R-018, R-020, R-022; passed |
| T-022 — `quietParkedMountEnablesPredictionBeforeNextGps` | Specified quiet mounted stop enables fusion and subsequent acceleration changes speed before GPS. | R-005, R-018; passed |
| T-023 — `movingLargeRotationDiscardsBiasAndRemainsGpsOnlyUntilParked` | Specified gross rotation suspends fusion, clears bias and requires a later stop. | R-016, R-018, R-020; passed |
| T-024 — `rejectedGpsCannotKeepBadPredictionAliveBetweenCallbacks` | Large mismatch suspends prediction and exposes raw GPS state rather than gate lockout. | R-020, R-022, R-029; passed |
| T-025 — `staleFixesAndLongSensorGapCannotEnableFusion` | Long accelerometer gap, stale and out-of-order GPS do not restore fusion. | R-003, R-018, R-021, R-025, R-029; passed |
| T-026 — `quietSeatPoseDoesNotQualifyAsMounted` | One chosen seat pose fails quiet-mount eligibility; not all seat poses. | R-018; passed |
| T-027 — `sustainedHardLaunchIsNotMistakenForRemounting` | 8 m/s² for five seconds after a stop remains fused and reaches about 40 m/s; synthetic. | R-008, R-017, R-025; passed |
| T-028 — `oneZeroSpeedFixCannotAuthorizeStationaryBiasLearning` | A single old zero-speed fix cannot qualify a quiet interval for fusion. | R-008, R-018; passed |

### RealDashLiveTelemetrySelectorTest

Source: [RealDashLiveTelemetrySelectorTest.kt](../../app/src/test/java/dev/vehiclespeed/gnssprobe/RealDashLiveTelemetrySelectorTest.kt).

| ID / method | Assertion scope | Requirements / current result |
| --- | --- | --- |
| T-029 — `uninitializedCapturePublishesZeroWithInvalidMetadata` | Uninitialized source yields numeric zero and invalid metadata. | R-004, R-019, R-021; passed |
| T-030 — `freshPredictingStateIsValidAndConvertedToKph` | Eligible fused state converts m/s to km/h and marks valid. | R-002, R-011; passed |
| T-031 — `staleAcceptedCorrectionFallsBackToFreshGps` | Old accepted-correction timestamp disqualifies fusion but allows fresh GPS fallback. | R-019, R-021, R-029; passed |
| T-032 — `degradedModePublishesFreshGpsInsteadOfTheEstimate` | Degraded filter uses available fresh raw GPS. | R-002, R-019, R-029; passed |
| T-033 — `mountMovementSelectsGpsFallback` | Mount-not-ready state cannot publish fused estimate. | R-019; passed |
| T-034 — `modeCodesRemainStableForTheXmlContract` | Mode code values remain compatible with the explicit mapping. | R-002, R-011; passed |
| T-035 — `runawayEstimateNeverReachesTheWireEvenIfOtherFlagsLookGood` | Selector rejects implausible estimate despite otherwise permissive flags. | R-019, R-022; passed |
| T-036 — `noFreshCredibleSourcePublishesZeroAndUnavailable` | Missing credible fresh source yields zero/invalid/UNAVAILABLE, not consumer blanking. | R-019, R-021, R-029; passed |
| T-037 — `plausibleButDisagreeingEstimateFallsBackAndSourceIsExplicit` | Finite plausible but inconsistent estimate falls back, explicitly identifying source. | R-002, R-019, R-022; passed |
| T-038 — `startupMountStateCannotBypassFusionEligibility` | Mount READY alone cannot bypass fusionReady=false. | R-018, R-019; passed |

### RealDashCanEncoderTest

Source: [RealDashCanEncoderTest.kt](../../app/src/test/java/dev/vehiclespeed/gnssprobe/RealDashCanEncoderTest.kt).

| ID / method | Assertion scope | Requirements / current result |
| --- | --- | --- |
| T-039 — `encodesLiveTelemetryUsingDocumentedLittleEndianLayout` | Exact fixed-format little-endian bytes and units. | R-010, R-011; passed |
| T-040 — `clampsInvalidAndOutOfRangeValues` | NaN/negative/excessive inputs clamp in encoder; not a substitute for source policy. | R-010, R-011, R-022; passed |

### DriveCaptureReplayTest

Source: [DriveCaptureReplayTest.kt](../../app/src/test/java/dev/vehiclespeed/gnssprobe/estimator/DriveCaptureReplayTest.kt).

| ID / method | Assertion scope | Requirements / current result |
| --- | --- | --- |
| T-041 — `replaysPrivateDriveCaptureWhenProvided` | Optional private replay checks finite/bounded output, fallback equality and invalid-zero behavior; no minimum fused availability. | R-015, R-022, R-028; skipped |

## What the current suite does not establish

- There is no instrumented Android UI/service, socket-disconnect or end-to-end dashboard test in this run.
- No independent ground-truth speed, defined accuracy tolerance, complete fault envelope, uncertainty consistency test, or structural coverage report exists.
- The hard-launch fixture covers five seconds at 8 m/s² in a simple orientation; it does not cover all grade/acceleration combinations.
- Grade/transform fixtures cover selected pitch changes, not full attitude estimation, reverse speed, arbitrary yaw or flexible mounting.
- The replay can pass with zero fused rows. It is a boundedness/fallback check, not proof the estimator contributed useful information.
- The old forced-stationary filter test does not imply that B09's supervisor uses that path.
- Most tests exercise component outputs; they are not an exhaustive transition/path/boundary enumeration.

## Historical verification versus current verification

[Recovered command excerpts](reports/historical-command-excerpts.md) preserve the
v0.9 hard-launch failure and following pass. [Historical XML extraction](reports/historical-replay-summary.json)
preserves one skipped and one provided-input replay report. The latter passed
with zero fused rows. Early reports of eight or fifteen tests and old drive
replays are historical claims, not preserved complete suites for those revisions.

To summarize a future executed run without publishing hostnames or raw logs:

```sh
python3 tools/summarize_verification.py --date YYYY-MM-DD --baseline FULL_SOURCE_COMMIT
```

That script only summarizes existing reports. Review source/configuration and
run timestamps before archiving; it does not itself prove tests ran against the
specified commit. The package checker validates links/hashes, not estimator behavior.
