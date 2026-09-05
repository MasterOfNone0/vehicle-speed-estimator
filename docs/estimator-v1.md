# Velocity estimator V1

Application version 0.5.0 added a provisional Android-only vehicle-speed
estimator to the combined capture service. Version 0.6.0 can publish that state
to RealDash without changing the estimator mathematics. Version 0.7.0 added an
external forced-stationary input used only by the mount calibration manager.
Version 0.8.0 compensates for the measured GNSS observation delay and hardens
mount-change confirmation against vehicle launches.

## State and update model

The filter is a two-state linear Kalman filter:

```text
state = [vehicle velocity, effective longitudinal acceleration bias]
```

At each accelerometer event, velocity is propagated using the measured event
timestamp and the bias-corrected longitudinal acceleration. Android's direct
GPS speed on both test drives trailed the corresponding acceleration by about
one second. A moving GPS observation therefore corrects the stored state from
one second earlier, after which the retained accelerometer samples are replayed
to the present. The history is bounded to 3.5 seconds. If adequate history is
not yet available, the observation corrects the current state normally.

Stationary GPS observations are intentionally applied at the present time so
zero-speed convergence is not delayed. Android's reported speed uncertainty,
when available, controls measurement covariance. The raw GPS observation and
the current estimate remain separate values.

The effective bias is allowed to vary slowly. It represents accelerometer
offset plus gravity leakage caused by mounting pitch, road grade, and gradual
vehicle attitude changes. This does not make hills directly observable; it
limits how long grade-induced error can accumulate between GPS corrections.

The filter remains uninitialized until the first acceptable GPS speed arrives.
It never integrates acceleration from an assumed zero speed.

## Provisional vehicle-frame transform

The first driving capture showed that the vehicle-forward signal was not one
raw Android axis. The current development transform is:

```text
forward = -0.275750 X -0.019941 Y -0.961022 Z
```

It was inferred from 354 good, mostly straight driving intervals and had a
correlation of approximately 0.86 with GPS-derived acceleration. It is specific
to that tablet orientation. A different rigid mount requires a new transform.

## Modes and zero speed

The estimator reports:

- `UNINITIALIZED` before an acceptable GPS observation;
- `PREDICTING` during accelerometer propagation;
- `GPS_CORRECTION` on an accepted GPS update;
- `GPS_DEGRADED` for an excessively uncertain or gated GPS update;
- `STATIONARY` after credible near-zero GPS speed persists for 1.5 seconds.

While stationary, speed is held at zero when corrected acceleration remains
small and the effective bias adapts slowly. A clear launch acceleration exits
stationary mode before the next GPS observation. During a confirmed tablet
handling event, the calibration manager overrides that launch behavior, holds
exactly zero while stationary GPS remains fresh, and adapts bias quickly until
the adjusted mount becomes ready.

## Current safeguards

- Actual monotonic event timestamps determine every propagation interval.
- Backward or duplicate timestamps do not propagate the state.
- A callback gap longer than 250 ms is not integrated as one large step.
- Estimated forward speed is clamped at zero rather than becoming negative.
- GPS uncertainty changes measurement covariance dynamically.
- Very uncertain measurements and extreme innovations are rejected.
- Moving GNSS observations correct historical state before IMU replay.
- Raw measurements and estimated values use separate fields in memory and CSV.

## Verification status

Deterministic unit tests cover the transform, initialization, acceleration
propagation, GPS correction, bias learning, stationary convergence, launch
detection, uncertainty rejection, nonnegative speed, delayed-GNSS replay,
mount-change confirmation, road-grade separation, transform rotation, handling
stability, and RealDash validity metadata.

The private 12-minute-56-second driving capture was also replayed through the
actual Kotlin filter without adding the telemetry to the repository. All 726
GPS observations were accepted, more than 72,000 initialized accelerometer
events propagated the state, and all state and covariance values remained
finite and nonnegative. The 95th-percentile difference between an accepted GPS
observation and the immediately corrected estimate was approximately 0.74 m/s.

The 21-minute v0.6 drive was replayed privately through the v0.7 implementation.
The manager detected two stationary handling/repositioning events, held speed at
exactly zero for 6,596 accelerometer rows, and detected the final tablet pickup
as `MOUNT_MOVED`. All 1,256 GNSS observations were accepted and all state values
remained finite. The telemetry itself remains outside the repository.

Version 0.8.0 was replayed through that capture and a separate 46-minute F87 M2
capture containing highway driving, pulls, braking, turns, hills, and stop/go
traffic. On the F87 capture the 95th-percentile historical GPS innovation was
1.91 m/s and the 95th-percentile correction step was 0.61 m/s. All five false
mount-change holds produced by hard launches in v0.7 were eliminated; confirmed
holds occurred only during actual stopped tablet handling. The older capture
also passed without regression. Private telemetry remains outside the
repository.

A later startup test deliberately began with the tablet loose on a seat and
moved it into the mount after GNSS initialized while the vehicle was already
moving. That sequence exposed a critical fail-safe defect: the filter learned
gravity leakage as approximately `-8.2 m/s²` of bias, rejected 367 consecutive
valid GNSS updates after the orientation changed, and allowed the internal
estimate to diverge until a stationary zero-speed update forced recovery. This
means the preceding successful replays do not establish general robustness.
Version 0.8.0 must not be treated as validated for normal use. Details and the
required remediation are in [`known-limitations.md`](known-limitations.md).
