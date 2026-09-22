# Open evidence gaps and proportionate closure plan

The package reconstructs useful history, not a clean bill of health. Priorities
below are engineering triage, not certification severity categories. No work in
this list was executed merely by documenting it.

## Before trusting a new mounted fused-speed display

| Gap | Why it matters | Smallest useful closure evidence |
| --- | --- | --- |
| Independent consumer fail behavior (R-021, AN-15) | Numeric zero, raw fallback and a frozen last value can be mistaken for actual speed | Stationary bench: stale GPS, stopped publisher, disconnect/reconnect and process restart; observe gauge/source/invalid indication |
| New-tablet eligibility and mounting (R-007/R-018) | Bench never fused; fixed axis belongs to an earlier setup | Confirmed mounted quiet capture, eligibility timeline, then a controlled ordinary motion sequence; preserve configuration and any failure |
| Scale/offset/lag characterization (R-001/R-009) | 8.83 m/s² and inferred lag are not explained by compatibility | Confirmed stillness across several poses; compare magnitude and repeatability; characterize dynamic delay only with an appropriate reference |
| v0.9 integrated recovery (R-020) | Synthetic tests cover simplified remounts, not the original failing drive | Recover private D4 if available; otherwise deliberately synthetic fault sequence, then controlled stationary handling test; clearly distinguish both |
| Observability/axis limitation (R-023/R-025) | A quiet stop cannot identify yaw or separate every grade/acceleration error | Choose explicit mounting restrictions or implement a separately tested excitation-based procedure; do not tune around an unobservable state |

A working fallback is still useful when fusion is unavailable. Measure and report
how often fusion is actually available; do not improve that number by weakening
eligibility without an evaluated tradeoff.

## Before claiming accuracy or improved information

There is no approved maximum speed error, dynamic lag, correction-step bound,
dropout duration or false-stationary tolerance. Select them for the intended
display use **before** tuning against an acceptance data set. A speed-error bound
cannot be inferred from how smooth a gauge looks.

Use an independent speed reference with documented uncertainty and time alignment.
Record the reference latency as well as the tablet's; otherwise "anticipation"
may simply compare two filters with different delays. Split alignment/tuning
data from held-out evaluation. Include ordinary starts/stops, sustained changes,
grades, turns and degraded reception within a safe controlled environment.
Report distributions and worst-case excursions, source transitions, duration
unavailable, time to recover and fused availability. GNSS innovation remains a
diagnostic, not the truth label.

For training, the simulator can provide exact synthetic truth, but that makes
results simulator validation only. Never relabel synthetic observations as
recovered road measurements.

## Before multi-day friend testing

Minimum practical additions/review, without a formal assurance program:

1. One session manifest: app version/code, source commit or release ID, device
   profile alias, transform/delay settings, schema version, start/end status,
   operator notes and raw-file hash. No serial number or route in public records.
2. Straightforward start/stop/export and a visible logging/fusion/source state.
   Agree the private transfer method and retention window. Do not collect more
   personal telemetry than necessary.
3. Check long-duration coexistence, background-service time limits, screen-off,
   power/thermal behavior, disk-full/slow-storage, process death and reconnect.
   The current shared acquisition/logger path deserves a focused stress test.
4. Retain the failing file privately before reruns or log rotation replace it;
   publish sanitized findings and the responsible source baseline.
5. Review the lint data-extraction/backup policy for private captures. A clean
   Git repository is not itself a complete device-data privacy policy.

Do not require exhaustive aerospace-style process to run an experimental display.
A short documented bench plus ordinary mounted trial can be appropriate once the
operator understands the limits. Do not ask a driver to adjust or observe tooling
while driving.

## Historical reconstruction gaps that may remain permanently open

- No preserved v0.1–v0.7 source/APK baselines; no exact patch label for the 60 Hz demo.
- No complete original drive CSVs recovered, including D4.
- No authenticated chain of custody, tool qualification, independent reviewer,
  requirements approval history or structural coverage report.
- Incomplete association between session, exact binary, device settings and
  transform/lag configuration.
- No controlled proof of every hill, turn, rough-road or sustained-acceleration
  claim; operator descriptions identify scenarios, not synchronized truth labels.

Potential recovery sources are the user's tablet exports/backups or other
explicitly supplied files. This audit did not search all devices/accounts.
New evidence should be appended with its origin and hash, not silently used to
upgrade old claims. If unavailable, leave the gap visible.

## Lightweight change-impact record for the next revision

For each behavior change retain: triggering anomaly, affected requirements,
source commit, tests added/changed, expected difference, actual result and residual
risk. A short Markdown record is enough. Review both sides of a protective
threshold: detected faults **and** legitimate motion it must not suppress.
Re-run previous failure regressions and distinguish runtime fixes from
documentation-only changes. Version changes in field semantics as well as bytes.

The current edition performs documentation/reconstruction and preserves B09.
It does not change estimator behavior, install a new APK, close these gaps or
demonstrate any DO-178C compliance.
