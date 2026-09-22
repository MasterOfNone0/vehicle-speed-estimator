# v0.9 recovery and output behavior

Version 0.9 adds a plain Kotlin supervisor shared by the Android capture service
and optional private-log replay. The two-state Kalman filter remains isolated.

The output is explicitly one of `FUSED`, `GPS_ONLY`, or `UNAVAILABLE`. The normal
RealDash speed bytes carry fresh GPS whenever fusion is suspended. If neither
source is usable, those bytes contain zero and the valid flag clears. A normal
gauge still displays zero in that case; it cannot distinguish an unavailable
speed without also showing the source/status channel.

Fusion begins only after a quiet parked interval: recent credible low-speed
GPS fixes, three seconds of steady acceleration, and a plausible projection
onto the provisional forward axis. A later GPS fix must confirm that interval.
The stationary acceleration mean initializes effective bias, preventing the
filter from learning gravity as sustained acceleration during a moving start.
A first fix while driving remains GPS-only until an eligible stop.

Large apparent attitude changes, missing/uncertain GPS, sensor gaps, rejected
corrections, excessive uncertainty, or predictions outside a GPS-relative
envelope suspend fusion. Suspension clears learned bias, covariance, and stored
IMU history. GPS remains usable independently of the filter's innovation gate;
three consistent observations spanning at least a second re-anchor the filter.
Fusion still needs a quiet stop before resuming. These conditions trade some
fusion availability for predictable recovery; a bump or very aggressive motion
can cause a conservative GPS fallback.

The publisher independently checks freshness, speed uncertainty, finite values,
absolute limits, and disagreement with GPS. Its speed limit is a fault boundary,
not proof of accuracy. This prevents the previous invalid-estimate saturation
from reaching a gauge even if a caller supplies inconsistent state metadata.

## Existing RealDash connection

- The TCP address, frame `0x700`, payload widths, and speed scaling are unchanged.
- Mode codes 0–4 remain unchanged; code 5 means GPS-only output.
- Flag bit 6 (`0x40`) marks GPS fallback. Bit 0 now means the published source is
  usable, which can mean either fusion or GPS. It does not certify accuracy.
- Mount-ready bit 5 also requires the supervisor's quiet-stop qualification.
- Publishing continues at 60 Hz. GPS-only fallback repeats measured speed at
  that rate; it does not create additional GPS measurements.

Three CSV columns were added: `fusion_ready`, `fusion_reason`, and
`recovery_count`. Existing raw GPS and sensor columns remain separate, with no
coordinates added. In `GPS_ONLY` mode the speed-state field holds the last
credible GPS observation and is explicitly labeled as such.

## New tablet and remaining limitations

Additional receiver-speed lag defaults to zero in the supervisor. The older
tablet's approximate one-second lag is not assumed for new hardware. The
optional delayed-correction implementation remains available for a measured
device profile.

The forward axis remains provisional. Quiet data cannot prove that a tablet is
correctly aligned with the car, and accelerometer-only data cannot resolve yaw
or separate sustained acceleration from gravity in every case. Changing vehicle
mounting still calls for a short mounted capture. A successful bench check does
not validate speed accuracy on hills or during driving.

Focused regression tests cover seat startup and remounting, prediction after
parked bias initialization, movement invalidation, stale GPS, sensor gaps,
recovery-history reset, sustained hard acceleration, and the actual encoded
fallback payload. Forty unit tests passed; Android lint completed with warnings
and no errors. The prior
private captures were no longer in temporary storage for this build, so no new
replay result for those files is claimed.
