# As-implemented configuration and architecture

Scope: B09, the preserved v0.9.0 / code 11 source. This is a source review, not
another dynamic test. Paths below are links to repository artifacts; the manifest
records their hashes. B08 is available through its Git identifier in the package index.

## Boundaries and ownership

| Component | Responsibility and constraint |
| --- | --- |
| [CombinedCaptureService](../../app/src/main/java/dev/vehiclespeed/gnssprobe/CombinedCaptureService.kt) | Location and accelerometer acquisition, serialized processing, estimator calls, CSV rows and immutable snapshot publication |
| [VehicleFrameTransform](../../app/src/main/java/dev/vehiclespeed/gnssprobe/estimator/VehicleFrameTransform.kt) | Normalize forward direction and project raw Android acceleration; limited reference-gravity rotation |
| [MountCalibrationManager](../../app/src/main/java/dev/vehiclespeed/gnssprobe/estimator/MountCalibrationManager.kt) | Stationary change candidate, later GPS confirmation, settling/reorientation states |
| [VelocityBiasKalmanFilter](../../app/src/main/java/dev/vehiclespeed/gnssprobe/estimator/VelocityBiasKalmanFilter.kt) | Two-state velocity/effective-bias propagation and correction; optional historical correction/replay |
| [GuardedSpeedEstimator](../../app/src/main/java/dev/vehiclespeed/gnssprobe/estimator/GuardedSpeedEstimator.kt) | Own fusion eligibility outside the Kalman gate; quiet-stop initialization, reset/re-anchor, degraded GPS-only state |
| [RealDashLiveTelemetrySelector](../../app/src/main/java/dev/vehiclespeed/gnssprobe/RealDashLiveTelemetry.kt) | Independent last-line selection of fused, raw GPS, or unavailable output |
| [RealDashLiveService](../../app/src/main/java/dev/vehiclespeed/gnssprobe/RealDashLiveService.kt) | Local TCP service, publishes snapshots on a separate transport thread |
| [RealDashCanEncoder](../../app/src/main/java/dev/vehiclespeed/gnssprobe/RealDashCanEncoder.kt) and [XML](../../realdash/vehicle-speed-estimator.xml) | Wire contract, scaling and custom dashboard inputs |
| Activities | Diagnostic display and explicit user start/stop controls, not filter ownership |

Pure Kotlin estimator classes contain no Android UI dependencies but remain in
the single app module. Acquisition uses a HandlerThread/serialized executor,
not an independent fixed 100 Hz integration clock. Actual event timestamps set
delta-t. A volatile immutable snapshot separates publisher/UI reads from capture
state. CSV writing is **not** isolated behind a dedicated bounded logging queue;
it occurs on the capture path. Scheduling/storage stress has not been validated.

## Measurement model and observability

The implemented linear state is [v, b], with forward nonnegative speed v and
an **effective** acceleration bias b. Between samples:
v(next) = v + (a_projected − b) × delta-t. GPS speed corrects v and, through
covariance coupling, b. GPS sigma influences measurement variance, with defaults
and floors in source. Nonnegative clamping means this is not a signed reverse
velocity estimator.

There is no gyro-derived attitude or independent continuous gravity subtraction.
The fixed forward vector is approximately (−0.275750, −0.019941, −0.961022),
fitted on the first tablet/mount. Effective bias includes mounting gravity
projection, actual bias, and some changing grade effects. Sustained acceleration,
grade, scale error and orientation error cannot all be separated with these
measurements. Learning a quiet bias does not solve forward-axis yaw alignment.
Covariance models only the assumed system; small covariance is not proof that
the model is correct.

Raw Location speed, uncertainty and monotonic fix time remain separate from
predicted state. Sensor event time and location elapsed-realtime time share the
Android monotonic time domain in this implementation; arrival time is separately
recorded. No claim of calibrated inter-sensor latency is implied.

The standalone filter defaults extra receiver delay to 1.0 s, with up to 3.5 s
history. The v0.9 supervisor explicitly supplies **0.0 s** by default. Thus the
current Android path does not silently transfer the old tablet's inferred lag.
A past-state GPS correction plus acceleration replay is distinct from delaying
the publisher or altering the raw timestamp.

## Fusion supervisor policy at B09

These are implementation constants, not validated aerospace operating limits.

| Decision | Implemented condition |
| --- | --- |
| Usable raw speed | Finite 0–150 m/s; sigma absent or finite 0–5 m/s |
| GPS event freshness | Event not later than arrival; no more than 3 s old; increasing GPS event timestamps |
| Consecutive credible fixes | Gap 0.001–2.5 s; speed change no more than 1 + 12 × gap seconds (m/s) |
| Moving re-anchor | At least 3 consistent fixes spanning at least 1 s; does not itself enable fusion |
| Stationary candidate | Speed ≤0.30 m/s; sigma ≤1.5 m/s (absent sigma substitutes 1.5) |
| Sample gap | More than 0.25 s suspends fusion; duplicate/nonincreasing acceleration times do not propagate |
| Quiet mount | Magnitude 7–12 m/s²; gravity-noise RMS ≤0.30; absolute projected acceleration ≤4; manager READY |
| Quiet interval | Within 3° of anchor, at least 3 s; later GPS ≥2.5 s after interval start; stationary fixes span ≥2 s and credible count ≥3 |
| Enable | Re-anchor at current sample time to zero with sigma 0.1 m/s and measured quiet effective bias |
| Moving handling suspicion | Low-pass apparent gravity differs from last fusion reference by ≥55° for ≥0.2 s |
| Fusion GPS age | Most recent credible fix no more than 2 s old |
| State health | Finite speed 0–150, finite absolute bias ≤6 m/s², finite speed variance ≤25 (m/s)², agreement within GPS envelope |
| GPS envelope | 6 + 12 × age seconds m/s, age limited to 0–2 s |
| Suspension | Reset filter including bias/covariance/history; clear quiet/fusion reference; retain mount manager state; expose GPS_ONLY |

The implementation's quiet criteria reject the tested seat pose; they do not
prove arbitrary quiet poses are mounted correctly. The health predicate bounds
variance above; the package does not claim an exhaustive covariance-positive-
semidefinite proof. A 150 m/s fault boundary is not a validated vehicle speed range.

Mount manager details and thresholds remain in source and
[historical v0.8 notes](../mount-calibration-v08.md). Importantly, the v0.9 wrapper
does not use the standalone filter's forced-stationary override the same way
v0.7/v0.8 did. The old forced-hold unit test is component evidence, not proof that
v0.9's integrated path exercises that feature.

## Publication and consumer contract

The selector allows fused output only with active capture, fusion-ready,
mount READY, usable mode, finite/plausible estimate, estimate age ≤500 ms,
raw GPS and last accepted GPS age ≤3,000 ms, and agreement within the envelope.
Otherwise it repeats usable fresh raw GPS; with neither source it sends
zero, valid=false, source=UNAVAILABLE. Raw fallback does not require the
supervisor's three-fix consistency test, so it can repeat plausible but wrong GPS.

The 60 Hz transport cadence does not increase independent GPS information;
GPS-only can repeat one observation roughly sixty times. Gauge rendering cadence
was visually demonstrated but not measured as an independent display timing trace.

| Field | B09 encoding |
| --- | --- |
| Listener | IPv4 127.0.0.1:35000, tablet-local TCP |
| Frame | 16 bytes: header bytes 44 33 22 11, little-endian 32-bit ID 0x700, 8-byte payload |
| Payload 0–1 | Selected published speed, unsigned km/h ×100 |
| Payload 2–3 | Raw GPS speed, unsigned km/h ×100 |
| Payload 4–5 | GPS age milliseconds, unsigned saturation at 65,535 |
| Payload 6 | Mode: 0 uninitialized, 1 correction, 2 prediction, 3 degraded, 4 stationary, 5 GPS-only |
| Payload 7 | Bit 0 valid selected source; 1 raw fresh; 2 capture active; 3 provisional calibration; 4 estimate fresh; 5 mount AND fusion ready; 6 GPS fallback |
| Numeric representability | Encoder max 655.35 km/h; independent selector applies stricter observation/state checks |
| RealDash | Separate custom-input XML; Normal CAN mode; physical CAN bitrate is not governing TCP cadence |

The existing input name "Estimated Vehicle Speed" describes the selected channel,
which can contain raw fallback. A consumer must consult source/status to tell.
The XML does not implement an explicit invalid-value mask or timeout. Producer
zero/invalid behavior does not prove what the dashboard displays after a dead
process or broken socket.

## Logging and configuration limitations

The combined CSV is an event stream, not repeated full-rate GPS measurements.
It includes event/arrival time, raw measurements and sigma, state/innovation,
mount state, fusion readiness/reason and recovery count. v0.9 adds three fields
to the existing schema; it does not add a versioned session manifest. The current
replay uses simple comma splitting, relying on the present uncomplicated fields.

Combined captures omit latitude/longitude; the separate GNSS diagnostic probe can
contain location data. All raw captures remain private regardless of whether
coordinates exist. Event timing, travel patterns and filenames may also be sensitive.

There is no durable per-session association of APK hash, source commit, hardware
profile, forward transform or delay setting. Code hashes in this package fix
today's source, not the unknown configuration of every old capture.

## Android service and endurance risk

The manifest declares capture as a location foreground service, but the live and
demo publishers as dataSync foreground services. The app targets API 35.
Android documents a total six-hour background time budget per 24 hours for
dataSync services on Android 15+, with a timeout callback requiring a timely stop.
See the official [foreground-service timeout documentation](https://developer.android.com/develop/background-work/services/fgs/timeout).
No onTimeout handling was found in these services. This is an inspection risk,
not evidence that this app already failed after six hours. Suitability of service
type, permissions, screen-off behavior, restart/reconnect and storage failure
handling need a focused long-duration campaign.

## Proposal versus delivered scope

| Earlier intent | Preserved implementation / gap |
| --- | --- |
| Hardware orientation/gravity sensor fusion | Neither tablet exposes required attitude sensors; accelerometer-only projection |
| Saved calibration and automatic forward-axis identification | Not implemented; fixed initial vector and limited reorientation |
| Separate full-rate logger | Event CSV exists, but writing shares acquisition path |
| Every drive reproducible offline | Replay harness exists; raw data/configuration retention incomplete |
| Complete high-rate independent velocity | Interpolation-free propagation when eligible; falls back to low-rate GPS information |
| Comprehensive validation | Component tests, selected field reports and bench results; no independent truth campaign |
| RealDash deferred until complete Android validation | Transport integrated earlier for feedback; this did not close estimator validation |
