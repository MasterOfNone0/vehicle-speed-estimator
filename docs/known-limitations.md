# Known limitations

Version 0.9.0 addresses the failure mechanism below with a fusion supervisor and
an independent publisher fallback. See [v0.9 behavior](recovery-v09.md) for the
conditions, tests, and remaining mounting/accuracy limitations. The account below
describes the historical v0.8 failure, not a successful validation of v0.9.

## Version 0.8.0 startup and mount-handling failure

Version 0.8.0 is an engineering prototype and must not be used as a trustworthy
vehicle-speed source.

A private 36-minute capture began with the tablet loose on a vehicle seat. The
first direct GNSS speed arrived 269 seconds after capture started, when the
vehicle was already traveling. The filter interpreted the seat orientation's
gravity projection as longitudinal acceleration and learned approximately
`-8.2 m/s²` of effective acceleration bias. Moving the tablet into its mount
while driving invalidated that bias, but the mount manager intentionally did
not recalibrate during vehicle motion.

The resulting estimate diverged. The innovation gate then rejected 367
consecutive GNSS observations over approximately 6 minutes 16 seconds instead
of recognizing that the estimator was wrong. Recovery occurred only when the
stationary path applied an ungated zero-speed observation. Private telemetry is
excluded from this repository.

The RealDash payload creates a second fail-safe problem. Version 0.8.0 clears a
validity flag when the estimate is degraded, but still places the degraded
estimate in the normal estimated-speed bytes. RealDash gauges bound only to the
speed channel do not automatically honor the validity flag. The wire encoding
clamps speed to 655.35 km/h, so a diverging estimate can appear as a gauge
pinned near 407 mph.

## Remediation implemented in v0.9

The supervisor and publisher now:

- fall back to fresh raw GNSS speed, or zero when unavailable, whenever the
  fused estimate is invalid;
- re-anchor to several consistent, credible GNSS observations instead of
  rejecting measurements indefinitely;
- invalidate and reset learned acceleration bias after unmistakable tablet
  handling or a large orientation change, including while moving;
- remain in a GNSS-only startup mode when the first fix arrives while moving,
  enabling IMU fusion only after mount stability is established;
- bound internal state and published speed to prevent unbounded divergence.

Earlier fixed-mount captures remain useful for estimator development, but their
successful replays do not override this failure case.
