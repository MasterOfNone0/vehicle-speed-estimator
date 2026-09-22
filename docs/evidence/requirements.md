# Retrospective requirements and traceability

IDs and statements below were reconstructed on 2026-09-22. They were not a
formally approved specification during development. The initial proposition was
explicitly a proposal; later design suggestions are separately identified.
No arbitrary DO-178C objective or assurance level is assigned.

The [JSON register](traceability.json) is the canonical machine-readable mapping.
Each requirement links source files, tests/manual campaigns, provenance and a gap.
Test IDs resolve to exact methods in [verification.md](verification.md); manual
IDs resolve in [test-campaigns.md](test-campaigns.md). Test and manual entries
also link back to their requirements. A reference to a planned test is not coverage.

Statuses: **unit-covered** = current named fixture assertions pass, not general
verification; **partial** = implementation/evidence exists but stated gaps remain;
**open** = no adequate execution; **proposed-only** = not implemented;
**scope-stated** = a declared boundary, not a tested capability.

## Requirement matrix

| ID | Intent | Current evidence | Status / unresolved boundary |
| --- | --- | --- | --- |
| R-001 | Measure device capabilities and actual callback rates before interpreting update rate. (original-proposal) | M-001, M-002, M-009, E-004, E-005, E-013 | partial: Measurements are historical and device/conditions specific; new-tablet sensor scale/latency remain open. |
| R-002 | Keep raw GNSS, estimated state, selected output source and validity distinguishable. (original-proposal) | T-030, T-032, T-034, T-037, M-003, M-009, E-003, E-016 | partial: Field names/acceptance semantics vary across versions; dashboard must expose fallback. |
| R-003 | Use monotonic event times and actual delta-t; reject stale/nonincreasing samples and do not integrate long gaps. (original-proposal) | T-025, M-004, M-009, E-003, E-007, E-013 | partial: Selected gap/order cases tested, not all delayed interleavings or shared-thread I/O jitter. |
| R-004 | Do not initialize velocity by integrating acceleration without a credible speed reference. (original-proposal) | T-004, T-021, T-029, E-003, E-015 | unit-covered: Unit fixture coverage, no exhaustive startup environment qualification. |
| R-005 | When fusion is eligible, predict velocity changes before the next GNSS observation. (original-proposal) | T-005, T-022, E-003, E-015 | unit-covered: Not a guarantee of high-rate accurate output or fusion availability in the new tablet. |
| R-006 | Correct velocity and effective bias with GNSS while preserving uncertainty and innovation metadata. (original-proposal) | T-006, T-007, M-004, M-005, M-007, E-008, E-009, E-010 | partial: Residuals after corrections are not independent accuracy evidence; model errors remain. |
| R-007 | Derive an explicit normalized forward projection and document mounting assumptions. (original-proposal) | T-001, T-002, T-003, M-004, E-007, E-016 | partial: One old-mount fitted axis; no arbitrary yaw or new-mount calibration proof. |
| R-008 | Converge to zero when credibly stopped without suppressing genuine launch. (original-proposal) | T-008, T-010, T-017, T-018, T-027, T-028, M-005, M-006, M-007, E-009, E-010, E-015 | partial: Forced-hold component path differs from wrapper; unreliable near-zero GNSS remains a limitation. |
| R-009 | Treat extra observation lag as device-specific; correct past state and replay if enabled. (derived-from-failure) | T-011, T-012, M-006, M-007, E-010, E-016 | partial: Old-tablet lag inferred; new-tablet default zero is uncharacterized, not proven exact. |
| R-010 | Keep transport/UI independent of the estimator; support same-device local TCP. (subsequent-user-request) | T-039, T-040, M-003, M-009, E-006, E-013, E-016 | partial: Packet encoder tests do not exercise sockets, lifecycle or live RealDash on every tablet. |
| R-011 | Preserve explicit units, byte layout, modes and XML input mapping. (subsequent-user-request) | T-030, T-034, T-039, T-040, M-003, M-009, E-006, E-012, E-015 | unit-covered: Byte fixture and observed packet checks; no complete consumer semantics proof. |
| R-012 | Make selected output available at >=10 Hz; 60 Hz is the current publisher target. (subsequent-user-request) | M-003, M-009, E-006, E-012 | partial: 20-second 60 Hz observation is not long-run timing nor 60 Hz independent measurements. |
| R-013 | Serialize estimator mutations and separate snapshot consumption from acquisition. (original-proposal) | E-016 | partial: Source inspection only; disk writes share capture path and concurrency stress is absent. |
| R-014 | Keep the public repository free of private raw telemetry, device identifiers and signing material. (original-proposal) | E-017, E-019 | partial: Ignored files and review are controls, not a guarantee future commits cannot leak data. |
| R-015 | Retain sufficient versioned event/configuration metadata for reproducible offline analysis. (original-proposal) | T-041, M-004, M-009, E-007, E-014, E-016 | partial: No versioned session manifest; missing old raw data; replay skipped in fresh run. |
| R-016 | Handle confirmed stationary mount changes without accepting unstable or moving apparent tilt as calibration. (subsequent-user-request) | T-003, T-014, T-015, T-016, T-018, T-019, T-020, T-023, M-005, M-006, M-007, E-009, E-010, E-015 | partial: Selected pitch fixtures; arbitrary yaw/flexible mounts not observable or validated. |
| R-017 | Do not classify the defined launch regression scenarios as stopped handling. (derived-from-failure) | T-017, T-018, T-027, M-006, M-007, E-010, E-012, E-015 | unit-covered: Covers fixtures/reported cases, not every sustained acceleration or grade combination. |
| R-018 | Enable or re-enable fusion only after a fresh, later-confirmed eligible quiet stop. (derived-from-failure) | T-021, T-022, T-023, T-025, T-026, T-028, T-038, E-003, E-015 | unit-covered: Rejecting one seat pose does not identify a truly correct forward axis. |
| R-019 | Independently select fresh raw GPS when fused output is ineligible; otherwise mark unavailable. (derived-from-failure) | T-029, T-031, T-032, T-033, T-035, T-036, T-037, T-038, M-009, E-003, E-012, E-015 | unit-covered: Fallback may inherit plausible GNSS error; consumer invalid behavior not covered. |
| R-020 | Discard corrupted bias, covariance and replay history on suspension/re-anchor to avoid measurement lockout. (derived-from-failure) | T-013, T-021, T-023, T-024, E-011, E-015 | unit-covered: Synthetic regression, original runaway capture unavailable. |
| R-021 | Prevent stale/unavailable telemetry from appearing as trustworthy live speed through the full consumer path. (derived-from-failure) | T-025, T-029, T-031, T-036, M-012, E-011, E-016 | partial: Producer tested, dashboard timeout/invalid annunciation and dead-service behavior open. |
| R-022 | Keep state/output finite and within declared fault bounds; never rely only on encoder saturation. (derived-from-failure) | T-009, T-013, T-021, T-024, T-035, T-037, T-040, T-041, M-008, M-009, E-011, E-014, E-015 | partial: Plausibility limits are not validated physical/accuracy limits; private replay currently skipped. |
| R-023 | Persist calibrated mounting and relearn forward alignment from suitable vehicle excitation. (subsequent-design-proposal) | E-009, E-016 | proposed-only: No implementation of saved profiles or full dynamic forward-axis learning; no acceptance test. |
| R-024 | Support extended coexistence with RealDash, including service timeout, screen-off, storage and restart behavior. (original-proposal) | M-012, E-016, E-018 | open: No endurance execution; dataSync time budget and shared-thread I/O risks. |
| R-025 | Characterize limits under hills, sustained acceleration, turns, vibration and GNSS degradation. (original-proposal) | T-007, T-015, T-016, T-025, T-027, M-004, M-005, M-006, M-010, M-011, E-007, E-010, E-016 | partial: Limited component fixtures and uncontrolled drive reports, not independent envelope validation. |
| R-026 | Define and demonstrate useful accuracy/latency against an independent synchronized reference. (retrospective-audit) | M-011, E-001, E-016 | open: No numerical acceptance tolerance approved and no independent reference data. |
| R-027 | Preserve identifiable source baselines and auditable requirement-to-test-to-result links. (retrospective-audit) | E-002, E-003, E-020 | partial: This retrospective matrix does not restore earlier baselines or preapprove requirements. |
| R-028 | Retain raw evidence privately and durable sanitized result records publicly. (retrospective-audit) | T-041, E-014, E-015, E-017 | partial: Historical input missing; new retention controls do not recover lost data. |
| R-029 | Apply GNSS speed/uncertainty plausibility while preserving missing-accuracy semantics. (original-proposal) | T-009, T-024, T-025, T-031, T-032, T-036, M-001, M-009, E-003, E-004, E-015 | partial: Missing sigma uses assumptions; multipath can remain plausible and pass. |
| R-030 | State scope honestly: forward terrestrial speed display, not signed navigation, guidance or control. (retrospective-audit) | T-009, E-001, E-019 | scope-stated: No reverse velocity, full attitude state, actuator output or aerospace qualification. |

## Approval and closure discipline

Before using this as a live development baseline, approve operating conditions,
acceptable error/latency, handling policy, unavailable indication and endurance
targets. Configuration thresholds are current choices, not automatically justified
requirements. A feature can be intentionally deferred (R-023) without pretending
it was delivered. R-026 cannot close using residuals against the correction signal.

The present matrix links 30 requirements and all 41 test methods, including the
skipped optional replay. This is trace-link completeness for the catalog, **not**
requirements coverage completeness, branch coverage, MC/DC or independent review.
