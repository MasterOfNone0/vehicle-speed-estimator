# Reconstructed test campaigns

These records preserve reported observations; they are not newly performed tests.
H = historical report, U = operator clarification, A = surviving artifact/command.
Raw data for historical drives was not recovered. Dates are in
[provenance](provenance.md); exact APK hashes/configurations usually are unknown.
Aggregate speeds are included to explain engineering range/defects, not to
prescribe road maneuvers. Use bench simulation or controlled lawful testing.

## M-001 — Direct GNSS characterization

**Configuration:** first tablet, initial GNSS probe; E-004 (H/U).
No-fix captures lasted about 117 s and 64 s. The useful stationary-then-walk
capture contained 90 unique fixes over 88.955 s: 1.0005 Hz, median interval
1.0004 s, central 90% approximately 0.991–1.009 s. Speed and uncertainty were
reported on all fixes. Median delivery age 7.9 ms, speed sigma 0.547 m/s
(~1.22 mph), horizontal accuracy 2.92 m.

**Interpretation:** native delivery was roughly 1 Hz in this test, not merely
RealDash throttling. User saw about 2.34 mph walking. No maximum-capability,
time-to-first-fix repeatability or independent speed-accuracy claim.

## M-002 — Stationary accelerometer rate/noise

**Configuration:** first tablet, v0.2; only last three files confirmed on table;
E-005 (H/U). Requested 50/100/200 Hz yielded 49.95/99.90/199.78 Hz. Approximate
axis noise RMS: X 0.020, Y 0.019–0.022, Z 0.030–0.031 m/s². Delivery around
1.1 ms; quantization step around 0.0383 m/s²; magnitude around 9.32 m/s².

**Interpretation:** 100 Hz is feasible and inexpensive enough for a prototype.
No forward-axis identification, calibrated gain or mounted road-vibration result.
Do not infer all three sessions' exact duration from the planned procedure.

## M-003 — Scripted TCP/RealDash integration

**Configuration:** v0.3.0/v0.3.1 and later 60 Hz demo; E-006 (H).
Initial connect failure resolved by explicit IPv4. About 21 frames/s observed
for nominal 20 Hz. Later roughly 39-second script included stops, approximately
30 mph city and 58 mph higher-speed segments, with nominal 60 Hz output.

**Interpretation:** proof of local transport, schema and visible gauge response.
Both channels in this demo were artificial. No high-rate GNSS measurement or
fusion accuracy was established. Exact 60 Hz demo patch identifier not recovered.

## M-004 — Combined capture and first driving fit

**Configuration:** v0.4 acquisition; E-007 (H/U).

| Session | Reported acquisition | Limitation |
| --- | --- | --- |
| Indoor sanity | 211.86 s; 21,157 accel samples; 99.86 Hz; median/p95 gap 10.01 ms, max 20.019 ms; mean age 1.07 ms; zero nonincreasing timestamps; no GPS | Timing only |
| Walking/running | 91.6 s; 9,151 accel samples at 99.86 Hz; 73 GPS near 1.01 Hz; first fix 20.5 s; max GPS gap 1.18 s | Handheld orientation; initially poor roughly 9 mph fix, later around 5 mph activity |
| First mounted drive D1 | 12m56s; 77,483 accel samples at 99.87 Hz; 726 GPS at ~1 Hz; first fix 50.4 s; longest GPS interval 1.024 s | Raw capture/configuration not preserved publicly |

D1 reported maximum 46.2 mph, eight stops, GPS-derived acceleration approximately
+2.05/−2.80 m/s². Forward fit used 354 mostly straight intervals with correlation
0.86 and normalized vector (−0.2757504943, −0.0199408730, −0.9610223861).
Stopped orientation varied about 2.8°. Final ~11 s showed ~9.3° movement;
operator confirmed moving/picking up the tablet and a small hill.

**Subsequent v0.5 replay report:** 726 GPS accepted, more than 72,000 initialized
accelerometer events, finite/nonnegative states and covariance, post-correction
difference p95 0.74 m/s. Eight estimator tests were reported at that iteration.
This is consistency with a correction/fitting signal, not held-out truth.

## M-005 — Live v0.6 drive D2

**Configuration:** v0.6 live 60 Hz RealDash; E-009 (H/U).
About 21 minutes, 1,256 GPS fixes near 1 Hz, 126,149 accel samples near 99.9 Hz,
maximum reported 57.9 mph, no >250 ms acceleration gap. GPS all accepted.
Median absolute innovation about 0.45 mph; correction step p95 below 1.24 mph;
100 Hz step p95 below 0.04 mph; accel/GPS-derivative correlation about 0.83.
Routine stopped orientation difference ~2.9°.

Handling while stopped changed orientation by 20–28°, reaching ~40° and later
~73° at the end. False speed reached roughly 1–3 mph, 6.6 mph and 13 mph.
User confirmed adjusting RealDash while stopped.

**Disposition:** end-to-end function observed, handling defect found. Not a
clean acceptance campaign. v0.7 replay later reported two handling events and
6,596 zero-held acceleration rows, with all 1,256 GPS accepted.

## M-006 — High-dynamic v0.7 drive D3

**Configuration:** different vehicle/mount use, v0.7; E-010 (H/U).
About 45.9 minutes total, 34.8 driving, 277,303 timestamp-valid records.
Acceleration ~99.90 Hz, no gap >21 ms. GPS near 1 Hz; max gap 1.047 s.
Reported peak GPS 114.2 mph and estimate 113 mph. 2,046 of 2,084 driving
GPS updates accepted; 38 rejected, reportedly 36 for uncertainty. Eighteen
stopped intervals totaling about 640 s.

**Failures:** five launch false holds of 0.2–1.1 s; up to 15.5 mph drop;
two following good GPS updates rejected. Return-to-original-angle path declared
ready while noisy (~4.97 m/s²). An arbitrary 30 m/s replay bound failed.
No independently quantified turn/grade accuracy was established.

A lag search reportedly found approximately 1 s best alignment, correlation
0.91 here and 0.97 on an earlier drive. Treat as device-specific inference,
not certified time delay or evidence of physically accurate anticipation.

## M-007 — v0.8 replays of D2/D3

**Evidence:** E-010 (H); original inputs and complete run outputs unavailable.
Reported elimination of all five false launch holds. D3 historical GPS
innovation p95 1.91 m/s; correction step p95 0.61 m/s (~1.36 mph). D2 also
reported no regression.

**Interpretation:** useful regression reports for specific faults. These metrics
refer to changed timing/algorithm semantics and are not directly comparable to
earlier current-state residuals. They did not exercise moving seat-start recovery.

## M-008 — v0.8 startup/remount runaway D4

**Evidence:** E-011 (H/U); vulnerable B08 source retained.
About 36 minutes, reported capture ~50.8 MB. This was **not planned injection**:
the user thought the slow-acquiring system was not working, left the tablet on
a seat, then mounted it after seeing speed.

| Approximate capture time | Reported event |
| --- | --- |
| 269 s | First GPS while moving, ~31.8 mph |
| 285 s | Tablet handling/remount |
| 290 s | Persistent correction rejection begins |
| 301 s | Encoded speed reaches maximum |
| 667 s | Stationary GPS finally recovers speed |

Seat projection around −9.2 m/s² produced learned bias around −8.2. Changed pose
then generated apparent +9 m/s². Reported 367 rejected GPS fixes over ~376 s;
internal peak about 7,958 mph, encoded ceiling 655.35 km/h (~407 mph).
Mount state remained READY and recalibration count zero while moving.
Post-recovery GPS acceptance reportedly 98.9%, peak estimate 52.9 mph vs GPS
51.1 mph. These last numbers do not undo the earlier serious failure.

## M-009 — New-tablet v0.9 bench and packet check

**Configuration:** E8A Android 15/API 35, accelerometer only; E-012/E-013/E-014
(A/H/U). Version 0.9.0/code 11 was installed. About 52.26 s capture:
5,222 accel samples, 99.90 Hz, maximum gap ~10 ms, zero nonincreasing times,
mean arrival age 0.80 ms. Mean axes approximately (−0.908, 0.324, 8.781) m/s²;
reported magnitude 8.834 m/s² with standard deviation 0.036.

35 GPS fixes over 34.61 s (~0.982 Hz), peak 9.24 mph, mean sigma 1.258 m/s,
max sigma 1.857; 22 fixes with sigma ≤1.5 and five near-zero fixes.
All acceleration rows had fusion_ready=false: 3,480 GPS_ONLY and 1,742
UNINITIALIZED. User later confirmed pickup, so this is not a controlled
stationary or scale-calibration experiment.

Separate client: 1,201 correctly framed packets /20.004889 s =60.0353 Hz,
modes 0/5. Capture/publishing reportedly stopped afterward, with no application
crash found in that check. Does not establish multi-day operation.

The surviving optional replay report processed 5,257 rows/35 GPS, fusedRows=0,
max published=max raw GPS=4.13255978 m/s, recoveries=0. This is a fallback
consistency result. That CSV is missing; today's optional replay is skipped.

## Proposed campaigns — NOT EXECUTED

| ID | Purpose | Minimum evidence to retain |
| --- | --- | --- |
| M-010 | Confirm new-tablet stationary acquisition/scale and mounted eligibility | Conditions and poses, raw private data/hash, version/config, counts/rates, source-mode timeline |
| M-011 | Independent speed accuracy and robustness, including grade/turns/normal launch/braking | A synchronized independent reference with characterized uncertainty, predeclared tolerances, held-out scenarios and dropout/recovery outcomes |
| M-012 | Long-duration coexistence with RealDash | Time budget/service behavior, screen-off/restart/disconnect/reconnect, storage pressure, sample gaps, stale indication and battery/thermal observations |

Future test design must separate simulated fault injection from physical drives.
Never handle the tablet or operate diagnostics while driving; use a passenger
or a stationary/controlled setup. A proposed campaign has no pass/fail result.
