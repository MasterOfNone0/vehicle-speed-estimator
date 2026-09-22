# Training adaptation — author guidance

Audience: task authors, not the trainee. This package is an engineering history
source and a starting point for exercise design, not a completed aerospace
simulator. Keep the [participant brief](participant-brief.md) separate from the
[evaluator guide](evaluator-guide.md).

## Honest scope of the GNC framing

The real project estimates one terrestrial forward-speed scalar and an effective
acceleration bias for a display. It implements a small part of navigation, not
guidance or control. It has no attitude propagation from gyro, actuator model,
closed-loop vehicle controller, orbital dynamics or flight-qualified software.
Reframing the vocabulary must not create nonexistent engineering evidence.

A suitable fiction is a **one-axis navigation telemetry channel in an aerospace
ground-test/simulation rig**, with intermittent reference-speed observations,
body-mounted acceleration sensing and a simulated display consumer. If a real
flight-like six-degree-of-freedom GNC problem is wanted, that requires a separate
dynamics/sensor model, defined frames and gravity, guidance/control requirements,
and newly generated verification evidence. This package cannot supply those
merely by changing nouns.

| Real project | Training abstraction | Invariant to retain |
| --- | --- | --- |
| Tablet raw accelerometer | Body-mounted specific-force-like sensor in the simplified rig | Acceleration/gravity/attitude ambiguity; explicitly define synthetic measurement equation |
| Android GNSS speed | Low-rate independent reference observation | Timestamp, arrival time, uncertainty and processing lag are different |
| Rigid vehicle mount / tablet handling | Sensor-frame configuration change | Old projection/bias can become invalid |
| Effective acceleration bias | Lumped model offset | Not a full physical IMU calibration or all-observable state |
| Quiet stopped vehicle | Confirmed zero-motion calibration window | Low signal noise alone is insufficient while moving |
| Local RealDash channel | Telemetry/display sink | Consumer may ignore status; invalid numeric values matter |
| GNSS-only fallback | Reference-only degraded mode | Lower bandwidth can be preferable to unsupported prediction |
| Field drive | Historical operational report, or separately generated simulator run | Never confuse reported field metrics with synthetic truth |

## Evidence packaging and leakage controls

For a retrospective case study, distribute the full package and ask for an
evidence-quality critique. For a repair challenge, do **not** distribute the full
repository/history/docs to the participant: they reveal the solution.

Only B08 and B09 are real preserved source baselines. B08 can seed the failure
repair exercise; a copied, curated source bundle must be labelled "derived from
B08", with an exact content hash and every author edit recorded. Do not claim it
is an intact early release if files were changed. It is not appropriate to
fabricate a v0.6 or v0.7 source tree and label it recovered history.

Use an explicit allowlist for participant files. Exclude this evidence directory,
v0.9 recovery documents, fixed supervisor/selector implementation, answer-bearing
regression names, Git history and unrelated credentials/artifacts. B08 itself
already contains warning documents, so simply checking it out is not sufficient
for an answer-blind task. Preserve toolchain license notices and choose a project
license/permissions policy before outside reuse; the current repo has no license.

The evaluator should privately retain the mapping from fictional terms, source
baseline and fault injection to this historical case. All task roles should know
the scenario is fictional simulation. Public training data must not contain raw
user drives, route data, serials or full conversations.

## Constructing reproducible synthetic evidence

Historical captures are missing. Generate new fixtures only under an explicitly
documented simulator model and seed. Keep truth, noisy observations and the
algorithm's outputs as separate streams. Record configuration, units, sample
timestamps, delivery timestamps, uncertainty assumptions, reset events and
algorithm source hash. Do not "reconstruct" unavailable drive samples by
interpolating the few published aggregate numbers.

Useful scenario dimensions:

- Late first reference while already moving; biased sensor-frame projection.
- Large and small frame changes, including a yaw-only change that gravity cannot identify.
- Persistent physically valid acceleration after an old zero-speed observation.
- True stationary remount followed by noisy settling and a transient return to old angle.
- Reference outlier with poor uncertainty; plausible multipath-like error.
- Reference dropout, stale delivery, repeated/out-of-order timestamps, sensor gap.
- Wrong learned bias and overconfident covariance causing correction-gate lockout.
- Consumer ignores validity, or freezes the last number on transport loss.

Exact values and seeds belong in the new simulator specification, not falsely
attributed to the field. Include nominal and counterexample cases on both sides
of each guard. Keep held-out seeds/conditions for evaluation; passing the visible
hard-launch example alone must not earn general robustness credit.

## Light assurance-style deliverables

The exercise can require requirements with origins, design assumptions,
anomaly records, configuration IDs, reproducible tests/results, change impact,
and a justified residual-risk statement. Call these "assurance-inspired engineering
records", not a simulated certification or a satisfied software assurance level.
Reward appropriate uncertainty and explicit deferral over paperwork volume.

The supplied participant brief and rubric are drafts for a future task.
This audit has not built or run that simulator, validated its physics, created
a new AI task, or measured model performance on it.
