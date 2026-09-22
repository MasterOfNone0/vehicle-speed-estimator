# Revision history reconstructed from surviving evidence

Read the [provenance classes](provenance.md) first. Product versions below are not
Git releases. Only B08 and B09 are preserved source baselines. Early version labels
without a surviving version declaration are identified as inferred/unconfirmed.

## Milestone overview

| Revision / milestone | Purpose | Failure or limitation found | Next response | Evidence |
| --- | --- | --- | --- | --- |
| Proposal / environment | Review architecture; inventory tablet before assuming sensor capability | Proposed orientation-assisted design was unsupported by actual hardware | Use direct GNSS and accelerometer-only, fixed-mount model | E-001/E-004 |
| Initial GNSS probe (v0.1-era; exact version not recovered) | Measure actual native speed/fix rate | Initial sessions recorded no fixes; stationary/moving condition needed clarification | Outdoor stationary/walk capture; measured ~1 Hz | E-004, C-03 |
| v0.2.0 | Measure accelerometer rates/noise | Some first captures were not stationary; gravity magnitude differed from expected | Use only confirmed table sessions for stationary noise; select 100 Hz | E-005 |
| v0.3.0 | Scripted RealDash TCP/CAN proof | Server listened on IPv6 while client used IPv4 | Explicit IPv4 binding | E-006 |
| v0.3.1 | Correct local connection | Connection worked; this still said nothing about estimation quality | Gauge binding, separate simulated raw channel | E-006 |
| 60 Hz demo iteration (exact patch version not recovered) | Demonstrate smoother rendering path | Presentation signal could be confused with genuine high-rate GNSS | Label both channels simulated; keep demo separate | C-06 |
| v0.4.0 | Combined GNSS/acceleration event logging | Indoor GPS absent; handheld walking unsuitable for vehicle calibration; poor initial fix | First mounted driving capture and provisional forward-axis fit | E-007 |
| v0.5.0 | First velocity/effective-bias filter | Mount-specific axis; no live RealDash output; uncertain ground truth | Integrate live publisher, retain explicit limitations | E-008 |
| v0.6.0 | Live estimator → RealDash at 60 Hz | Tablet handling while stopped created false speed | Add condition-based mount manager | E-009 |
| v0.7.0 | Mount-change handling and forced zero | Hard launches caused false holds; stability shortcut; arbitrary replay ceiling; timing mismatch | Later stationary confirmation, stability on every path, delayed correction, revised replay criterion | E-010 |
| v0.8.0 | Correct reported v0.7 defects | Moving startup/remount poisoned bias; measurement lockout; invalid speed still published | Supervisor, re-anchor/reset and independent fallback in v0.9 | E-011 |
| v0.9 development | Add conservative fault recovery | Early detector also classified sustained hard launch as handling | Remove ambiguous moderate-angle/noise branch; retain gross-angle and GPS checks | E-012 |
| v0.9.0 installed | Guarded startup, GPS fallback, quiet-stop re-enable | New-tablet scale question; handled bench not stationary; fusion never active in bench; long-run/accuracy gaps | Preserve open questions, do not call benchmark a fused-drive validation | E-013/E-014 |
| 2026-09-22 evidence edition | Preserve history and trace tests to intent | Missing historical baselines and recordings | Commit B09, archive sanitized results, assign retrospective IDs | E-015/E-017/E-020 |

## Early characterization: what was and was not established

The GNSS probe used direct `GPS_PROVIDER` requests with zero requested interval
and no batching. Two empty captures were not a 0 Hz performance result: they
contained no position/speed fixes and could not characterize steady-state rate.
The later stationary/walk session established approximately 1 Hz delivery under
those conditions, with speed and speed uncertainty supplied. Its approximately
8 ms callback age did **not** prove that the physical speed response had negligible
filtering lag; this distinction became important in v0.7.

The v0.2 table tests established useful sample-rate/noise observations at three
requested rates. They did not establish the car's forward axis, a calibrated
accelerometer scale factor, thermal behavior, or vibration response in the mount.
The assistant initially described the characterization tool as able to identify
the forward axis; later guidance correctly required driving excitation. That
capability claim should have been conditional, not implied by a stationary test.

## Protocol-only iterations

The network method was sound, but the first concrete implementation used a
default loopback address while instructions prescribed IPv4. The fix was a small
interface correction, not a new transport. The 20 Hz and 60 Hz demonstrations
proved an observed path from scripted packets to a gauge, not a GNSS/IMU estimator.
Examples using CAN ID `0x620` or 10/20 Hz were proposals; the implemented contract
uses `0x700` and eventually 60 Hz. Do not combine proposal payload layouts with
the later XML. See [configuration](configuration-and-design.md).

## v0.4–v0.6: first useful estimator and overconfident conclusions

The combined capture enabled one event timeline and kept measurements separate.
A first drive yielded a provisional normalized forward vector approximately
`(-0.276, -0.020, -0.961)` from 354 mostly straight intervals. v0.5 introduced
the two-state filter and replay. v0.6 connected it to the established transport.

The first live drive had favorable timing and correction-step statistics, but
also false motion during stopped handling. Calling that drive validation of the
complete system was incorrect. It was a mixed outcome: observed end-to-end
functionality plus a significant known defect. Using the same GPS data to fit the
axis, correct the state and assess agreement made independent accuracy claims
especially inappropriate. The original architecture review had already warned
against that conflation; the later reporting did not consistently honor it.

## v0.7: a remedy introduced false constraints

The mount manager used near-zero GPS and apparent gravity changes to decide when
to hold zero and adjust mounting. At ~1 Hz GPS, a previously stopped observation
can still be the newest observation during a real launch. Five reported launches
therefore produced short false holds; reported drops reached about 15.5 mph.
Separately, passing briefly through the old orientation could bypass the intended
stability requirement even at about 4.97 m/s² noise.

The review also reported ~1 s lag inferred from acceleration/speed correlation.
This suggested historical correction/replay, not blindly shifting every tablet's
GPS timestamps forever. An old replay assertion capped speed at 30 m/s and failed
on legitimate higher-speed input. A test fixture's operating range had become an
unjustified product limit.

The proposed v0.7 feature list included persistent saved calibration, GPS fallback,
and automatic forward-axis relearning from driving. The actual preserved successor
uses a fixed provisional axis, limited gravity-based reorientation and scalar bias.
It does not implement that full plan. [AN-11](anomalies-and-lessons.md#an-11--promised-calibration-capabilities-not-delivered)
records this requirements/implementation mismatch.

## v0.8: nominal replay success missed the recovery failure

v0.8 required a later low-speed observation to confirm a mount-change candidate,
required stability on both settling paths, and added delayed GNSS correction with
IMU replay. Historical reports said the earlier drive and the high-dynamic drive
replayed successfully, removing the five false launch holds.

A later drive exposed a different initial condition: first GPS while moving with
the tablet on a seat, followed by a large mounting change. The filter retained a
bias learned in the seat pose; its outlier gate repeatedly rejected GPS instead
of recognizing that its own state was wrong. The publisher transmitted the
diverging value despite clearing validity. A stationary update eventually
recovered velocity. It was not successful moving mount recalibration.

The existing estimator note called this a test that “deliberately began” on a seat.
The operator's account describes an unplanned response to delayed acquisition.
This package corrects the interpretation: do not present it as a designed,
controlled fault-injection experiment.

## v0.9 and audit edition

v0.9 separates the decision to permit fusion from the filter's correction gate.
It starts GPS-only, learns an effective bias after an eligible quiet stop, clears
bias/covariance/history on suspension, and independently selects the published
source. A three-fix re-anchor does not by itself authorize moving recalibration.
The default extra receiver-lag parameter becomes zero for the new device.

During v0.9 development, the sustained-hard-launch test failed against a detector
that also used moderate apparent tilt plus noise. That branch was removed. The
test then passed, but the remaining gross-angle detector is not proof of complete
handling detection, and small/yaw-only changes remain problematic.

The new tablet's short bench proved acquisition and packet generation, not fused
performance: it never enabled fusion. The user later confirmed picking it up, so
the whole capture cannot be called stationary or used to prove a sensor-scale
defect. The low magnitude remains an investigation lead.

The audit preserves B09 today and reruns its existing suite. It does not fabricate
v0.1–v0.7 source commits, backdate a v0.9 release, or claim missing drive replays
have been rerun. Newly recorded test evidence is separate from historical reports.
