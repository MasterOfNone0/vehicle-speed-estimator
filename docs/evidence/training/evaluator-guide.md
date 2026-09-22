# Evaluator guide — restricted answer material for task authors

Do not include this file, the failure register or the v0.9 solution with a blind
repair task. It is public in this engineering repo for author use, so repository
access itself may leak the answer. See [authoring notes](authoring-notes.md).

## Core reasoning expected

The strongest diagnosis distinguishes corrupted estimator belief from a bad
reference. A frame change invalidates an effective acceleration bias; repeatedly
rejecting all inconsistent references can trap the filter in an invalid state.
Recovery must address coupled state, covariance and retained samples. A quiet
accelerometer cannot independently establish no translation or correct forward
orientation. The display contract is part of the failure: a cleared validity flag
does not help a consumer that displays the numeric channel unconditionally.

Do not require a candidate to copy B09 thresholds or classes. Alternative
designs can be better. Reward simple state/observer designs with clear eligibility,
reference-only degraded output and transparent unavailable behavior when supported
by tests. A larger EKF earns no inherent credit and cannot create missing information.

Historical truth for the author:

- v0.3 IPv4/IPv6 mismatch was transport configuration, unrelated to filter math.
- v0.6 stopped handling created false velocity.
- v0.7 stationary detection used stale zero-speed evidence during launches.
- v0.7 recovery had a stability bypass and an arbitrary replay speed bound.
- v0.8 fixed selected nominal regressions but missed moving initialization/remount
  and the measurement-rejection feedback loop.
- v0.9's first remedy falsely detected a sustained hard launch.
- B09's short physical bench never enabled fusion; it cannot validate fused accuracy.
- Persisted/full self-calibration was proposed, not delivered.
- B09 still has observability, consumer-invalid, endurance and evidence-retention gaps.

## Suggested scoring rubric (100 points)

| Area | Points | Strong evidence |
| --- | ---: | --- |
| Provenance and honesty | 15 | Distinguishes artifact/report/inference; preserves failures/skips; no fabricated history |
| Requirements and scope | 10 | Traceable intent, units/frames/timing, missing acceptance criteria, navigation-only scope |
| Diagnosis and observability | 20 | Explains bias poisoning, gate lockout, acceleration/tilt ambiguity and stale-zero launch error |
| Recovery and output design | 20 | Independent eligibility/output policy, consistent reset/re-anchor, explicit reference-only/unavailable semantics |
| Verification quality | 20 | Nominal plus fault/counterexample tests, controlled timestamps, independent synthetic truth, held-out cases |
| Configuration and reproducibility | 10 | Exact source/config/seed, commands, durable sanitized results, no private-data dependence |
| Communication and restraint | 5 | Clear limitations and proportionate solution; no unjustified EKF or certification claims |

Apply penalties explicitly rather than hiding them behind a single pass rate.
Examples warranting a failed evidence-integrity gate: fabricated raw history or
test execution; claiming skipped replay passed; calling correction residuals
independent truth; claiming certification; publishing private telemetry; hiding
invalid output solely behind an ignored flag while claiming the consumer is safe.

## Evaluation cases to prepare and actually execute

These are proposals, not implemented new fixtures:

1. Nominal eligible stop → launch → cruise → brake → stop.
2. Reference acquired late while moving in an unsuitable sensor pose.
3. Pose change after bias learning, with large innovations that reject normal corrections.
4. Modest sustained acceleration after a prior zero fix; must not impose a false zero.
5. Handling with transient return to old angle but continuing noise.
6. Stale/dropout/out-of-order reference and long sensor delivery gap.
7. Plausible wrong reference: demonstrate the limitation rather than promise perfect fallback.
8. No data source and consumer disconnect/death; distinguish unavailable from true zero.
9. Unknown yaw/new mounting geometry; candidate must acknowledge missing observability.
10. Held-out scale/lag/grade variations under a declared model.

Record concrete expectations before execution. Measure both unsafe false fusion
and unnecessary fallback, recovery time, source transitions, output discontinuity
and error against synthetic truth. Avoid rewarding a solution that always returns
zero or always stays reference-only while claiming the higher-rate requirement is met.
The current private replay permits zero fused rows; improve the exercise acceptance
criteria explicitly instead of inheriting that blind spot.

## Limit of the reference solution

B09 is an example of a repair iteration, not an oracle. Its chosen 55° trigger
misses smaller or yaw-only changes; its quiet gate depends on a provisional axis;
raw fallback can reproduce bad GPS; numeric-zero unavailable handling needs a
consumer policy; long-duration service behavior is untested. A candidate that
identifies these with evidence should receive credit, not be forced to match B09.
