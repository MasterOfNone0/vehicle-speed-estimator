# Anomalies, engineering mistakes, and lessons

This is a retrospective problem-report register, not a list of independently
verified root causes. H/U/A/R refer to the [source classes](provenance.md).
"Addressed in code" is not equivalent to "closed by field validation." No flight
hazard classification is assigned. Test IDs are resolved in [verification](verification.md).

## AN-01 — Empty acquisition sessions mistaken for rate evidence

**When / evidence:** initial GNSS probe; E-004, C-03 (H/U).
Two sessions of approximately 117 s and 64 s produced no fixes. A subsequent
session measured approximately 1 Hz.

**Finding:** no-fix acquisition and steady-state measurement frequency are
different conditions. Empty logs cannot establish the receiver's maximum rate.
The original assumption that RealDash's 1 Hz necessarily reflected hardware was
correctly challenged, but outdoor acquisition conditions still needed control.

**Response / lesson:** measure unique fix timestamps only after acquisition,
record time-to-first-fix separately, and label no-fix sessions inconclusive.
M-001 supports the measured-rate claim; cold-start repeatability remains open.

## AN-02 — Uncontrolled stationary tests and uncalibrated scale

**When / evidence:** v0.2 and new-tablet v0.9; E-005/E-013 (H/U).
Early accelerometer runs included movement; only the last three old-tablet
captures were confirmed table-stationary. Reported magnitudes were about
9.32 m/s² on the old tablet and 8.83 m/s² in the new short capture.

**What went wrong:** a stationary-noise result depends on actual stillness, and
a stationary sample cannot identify vehicle forward. The assistant initially
overstated what the probe would establish. A scalar effective bias does not
calibrate accelerometer gain. The new-tablet capture also included pickup.

**Response / lesson:** exclude uncontrolled segments, retain scale as an
unresolved lead, do not fit a gain correction to one pose. M-002 is historical;
M-010 (controlled multi-pose characterization) remains planned.

## AN-03 — IPv6 server versus IPv4 client

**When / evidence:** v0.3.0 → v0.3.1; E-006 (H and current-source A).
The default loopback address selected IPv6 while instructions used 127.0.0.1.
RealDash remained connecting.

**What went wrong:** instructions and listener address family were not tested as
one interface. "Loopback" also risked confusion with the unrelated CAN-controller
loopback setting.

**Response:** explicitly bind IPv4, use Normal CAN mode. M-003 reported packets
and gauge activity. T-039/T-040 cover bytes, not sockets or Android networking.
Operationally addressed; new-device configuration must still be checked.

## AN-04 — Stopped tablet handling created vehicle speed

**When / evidence:** v0.6; E-009, C-11 (H/U).
Adjusting the tablet while stopped produced false readings, reportedly around
1–3 mph and peaks around 6.6 and 13 mph.

**Mechanism / mistake:** a fixed projection of raw acceleration changes its
gravity component when the device moves. The effective bias was no longer
appropriate. Smooth nominal driving did not justify treating the mount as fixed.

**Response:** condition-based mount handling, subsequently conservative fusion
suspension. T-014/T-016/T-023 and M-005/M-007 offer limited evidence. Handling in
arbitrary orientations, yaw changes and flexible mounts are not closed.

## AN-05 — Old zero-speed fix suppressed a real launch

**When / evidence:** v0.7 high-dynamic drive; E-010 (H).
Five reported false holds lasted 0.2–1.1 s, with speed drops up to about 15.5 mph.
Two following legitimate fixes were reportedly rejected.

**Mechanism / mistake:** a 1 Hz observation still saying stopped was treated as
proof the vehicle remained stopped while new acceleration tilted apparent
gravity. A plausible protective rule created an unsafe interpretation of motion.

**Response:** require a later stationary fix after the mount candidate; cancel
an unconfirmed candidate on moving GPS. T-017/T-018 and T-027/T-028 distinguish
launch from stopped handling in defined fixtures. M-007 reportedly eliminated
the five historical cases; raw replay inputs are missing.

## AN-06 — Return-to-old-angle shortcut bypassed stability

**When / evidence:** v0.7; E-010 (H/A).
A high-noise sample near the old orientation could declare READY without the
intended stable interval; reported noise reached 4.97 m/s².

**What went wrong:** one recovery branch enforced angle but not all entry
conditions. A state name implied more evidence than its transition required.

**Response:** stability checks on both recovery paths; T-020. Addressed in the
preserved source for the tested trajectory, not exhaustive state-transition coverage.

## AN-07 — Delivery age conflated with physical speed response lag

**When / evidence:** early ~8 ms callback age versus v0.7 inferred ~1 s dynamic
lag; E-004/E-010 (H/R).
Correlation with differentiated speed suggested a lag on two old-tablet drives.

**What went wrong:** freshness of a timestamp does not establish bandwidth or
phase delay of receiver speed. Conversely, a correlation optimum is not an
independent measurement of receiver latency: filtering, differentiation, vehicle
dynamics and fitting choices also affect it. The claim that this proved the
estimate was reacting correctly sooner was too strong.

**Response:** historical-state correction with IMU replay, T-011/T-012, but
v0.9 explicitly defaults additional receiver lag to zero. Recharacterize each
device; retain event timestamps rather than rewriting raw measurements.
Delay configuration is implemented, device identification is not resolved.

## AN-08 — Test speed ceiling encoded an unjustified assumption

**When / evidence:** v0.7 replay; E-010 (H).
A 30 m/s ceiling rejected legitimate high-speed input.

**What went wrong:** a convenient fixture range became an acceptance criterion
without a requirement. Raising it until a replay passes would also be inadequate.

**Response:** current T-041 checks finite/bounded behavior, source selection,
and maximum output relative to observed GPS. Its +30 m/s margin is still a coarse
runaway screen, not an accuracy requirement. Define tolerances and operating
envelopes before independent validation. Do not reproduce high-speed maneuvers
on public roads to satisfy this test.

## AN-09 — Moving startup poisoned bias and locked out recovery

**When / evidence:** v0.8; E-011, M-008 (H/U, B08 source A).
GPS first arrived about 269 s into capture while the tablet was on a seat.
After remounting, the filter reportedly rejected 367 consecutive fixes for about
376 s. Seat projection around −9.2 m/s² had become bias around −8.2 m/s².

**Mechanism / mistake:** the filter was allowed to explain a wrong gravity
projection as bias while moving. A large orientation change made that bias
invalid. Its innovation gate then trusted the corrupted prediction over the
measurements needed to recover. There was no independent supervisor resetting
the model's memory.

**Response:** v0.9 starts GPS-only, requires a later-confirmed quiet stop,
suspends fusion on credible failures, clears bias/covariance/replay history, and
re-anchors. T-013/T-021/T-023/T-024/T-025/T-026/T-028 cover synthetic cases.
The original failing drive has not been replayed during this audit. Field closure
remains open, especially modest or yaw-only remounts.

## AN-10 — Invalid metadata did not prevent bad wire values

**When / evidence:** same v0.8 event; E-011 (H/A).
Reported internal speed reached roughly 7,958 mph; 16-bit encoding saturated at
655.35 km/h (~407 mph). The gauge displayed a bad number despite invalid status.

**What went wrong:** clearing a flag was treated as a complete output defense.
The actual consumer was a numeric gauge and did not enforce that validity.

**Response:** an independent selector checks eligibility, age, plausibility and
agreement, chooses fresh raw GPS on failure, or sends zero with UNAVAILABLE.
T-029 through T-038 test these branches. Encoder saturation alone is not
a safe-state policy. Loss-of-connection behavior and invalid annunciation remain
AN-15; zero is not inherently a safe speed indication.

## AN-11 — Promised calibration capabilities not delivered

**When / evidence:** proposed v0.7 scope versus B08/B09; C-10/E-016 (P/A/R).
The assistant proposed saved mount profiles, GNSS fallback and automatic
forward-axis relearning from straight-line motion.

**What went wrong:** delivered behavior was described too broadly as a
calibration manager. Current code retains a provisional fixed forward vector,
limited gravity-vector reorientation, and effective-bias learning. It does not
persist a calibrated profile or identify arbitrary yaw/forward direction while
driving. GPS fallback arrived later in v0.9.

**Disposition:** explicitly deferred/open, R-023. Do not claim periodic or full
self-calibration. Quietness cannot solve the missing attitude/heading information.
A future solution needs an observable alignment procedure and tests, not merely
more frequent bias adaptation.

## AN-12 — v0.9 handling detector regressed sustained launch

**When / evidence:** recovered development command output; E-012 (A/H).
The first v0.9 detector included moderate angle plus noise as a handling trigger.
T-027 failed. The recovered failure preceded a successful revised test run.

**What went wrong:** translational acceleration changes apparent gravity and
its low-pass residual. Reusing both as if they were independent attitude evidence
reintroduced the v0.7 ambiguity.

**Response:** remove the moderate-angle/noise branch, retain a gross 55° condition
with 0.2 s persistence plus other GPS/state checks. T-027 now passes. This trades
sensitivity for fewer false positives; it does not prove all handling is detected.

## AN-13 — Overstated validation and circular accuracy evidence

**When / evidence:** v0.5/v0.6/v0.8 reports; E-008/E-009/E-010 (H/R).
Claims included complete-system validation, valid transform, and earlier correct
reaction than GPS.

**What went wrong:** smoothness, accepted-fix rate, post-correction residuals and
finite replays were overinterpreted. GPS also supplied axis fitting, state
correction and the comparison signal. No independent truth or held-out,
predefined acceptance campaign was established.

**Response:** distinguish acquisition, consistency, transport, robustness and
accuracy. Correct old wording through explicit audit notes. R-026/M-011 remain
open. Do not invent a numerical accuracy bound from these results.

## AN-14 — Historical baselines and raw evidence not retained

**When / evidence:** repository audit E-017 (A).
Only one original commit existed; v0.9 was uncommitted. Early APK/source versions,
most raw CSVs and complete test-run archives were not recovered. Temporary bench
data disappeared from its recorded location.

**What went wrong:** working-tree iteration and transient files were adequate
for quick experiments but not revision-by-revision reconstruction. Minimal
logging did not require sacrificing version/configuration provenance.

**Response:** preserve B09, this source register, selected command excerpts,
report hashes and a public manifest. Future minimum: commit identifier, configuration,
session label, test conditions, expected checks and a private raw-data retention
location/hash. Do not backdate commits or turn narratives into fabricated evidence.

## AN-15 — Consumer unavailability and disconnect behavior unverified

**Evidence:** XML/source inspection E-016 (A/R); no reproduced current field failure.
The numeric channel remains labelled Estimated Vehicle Speed even in GPS fallback.
Flags exist, but the shipped XML does not itself enforce a validity mask or an
explicit timeout policy. No fresh source leads to zero; process/network failure
may leave the consumer showing its last value.

**Disposition:** open. T-036 proves producer zero/invalid selection, not the
RealDash presentation. Test stale data, disconnect, service death and reconnection
on the actual dashboard. Agree how "unavailable" differs from a real stop.

## AN-16 — Long-run lifecycle and logging isolation not established

**Evidence:** source E-016 and Android service documentation E-018 (A/R).
Capture writes/flushes CSV on the acquisition thread. Publisher is a dataSync
foreground service targeting API 35, without an onTimeout implementation.
Android 15's background time budget therefore deserves testing/design review.

**What went wrong:** the proposed dedicated logger and long-drive architecture
were not fully checked against what shipped. No observed six-hour failure is
claimed. Disk-full, slow-storage, process restart and screen-off tests are absent.

**Disposition:** open, R-013/R-015/R-024; M-012 planned. Do not describe a 20-second
TCP check as endurance evidence.

## AN-17 — New-tablet benchmark did not exercise fusion

**Evidence:** E-013/E-014, M-009 (A/H/U).
All 5,257 replay records yielded zero fused rows; after initialization output
equalled raw GPS. User confirmed pickup.

**What went wrong:** this bench could establish acquisition and fallback, not
mounted fusion, stationary scale, or driving accuracy. Describing hardware
compatibility as readiness for accurate fusion would overstate the result.

**Response:** preserve this negative coverage finding. Separate M-010 stationary
characterization from a mounted fusion drive; record fused availability, not
just a green replay result.

## AN-18 — Versioned field semantics need explicit interpretation

**Evidence:** B09 source E-016 (A/R).
gpsMeasurementAccepted can mean raw observation accepted during GPS-only, not
a Kalman correction. valid means the selected source is usable, including GPS
fallback. The mount-ready flag also depends on fusion eligibility.

**Risk / lesson:** comparing acceptance rates across versions or interpreting
every 60 Hz packet as fused creates misleading metrics without any code crash.
Keep source/fusion-ready separate, use a versioned schema and session configuration.
T-034 protects mode codes; it is not a complete cross-version semantics test.

## Engineering process lessons to carry into the training exercise

1. Test the interpretation of a measurement, not just its numerical range.
2. A rejected reference may indicate a bad estimator, not a bad sensor.
3. Recovery must clear all coupled memory: bias, covariance and retained history.
4. A monitor and its consumer must agree on the behavior of invalid data.
5. Every entry/re-entry path must satisfy the same eligibility conditions.
6. Separate simulated demos, replay consistency and independent validation.
7. Preserve failures before rerunning tools that overwrite reports.
8. Keep the smallest durable evidence record even when formal process is unnecessary.
