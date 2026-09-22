# Evidence sources and confidence

This inventory was assembled on 2026-09-22. IDs were assigned retrospectively.
Dates in recovered conversation summaries below are UTC; older notes sometimes
use local calendar dates. A date is not an exact APK build identifier.

## Evidence classes

| Class | Meaning | What it cannot establish |
| --- | --- | --- |
| A — artifact | Inspected source, Git object, report, or captured command result | That the implementation is correct for all inputs |
| H — historical report | Earlier engineering note or visible assistant response | That its analysis can now be reproduced without the original data |
| U — operator observation | User's description or clarification of test conditions | An instrumented ground-truth time series |
| R — retrospective analysis | Reasoned interpretation made in this audit | A contemporaneous finding or a newly executed experiment |
| P — proposal | Intended behavior, test, or recommendation | Implementation, execution, or acceptance |

These classes are about provenance, not numerical confidence scores. Mixed entries
state which part is direct evidence and which part is interpretation.

## Source register

| ID | Source / locator | Class and scope |
| --- | --- | --- |
| E-001 | Initial user proposition and architecture review, 2026-09-02; summarized in C-01 below | U/P; the proposition was explicitly not authoritative requirements |
| E-002 | B08 Git object, app build file, source, tests and `docs/` | A; sole original source baseline, v0.8.0 |
| E-003 | B09 Git object | A; preserved v0.9 source and tests, committed during audit |
| E-004 | [Development tablet](../development-tablet.md); C-02/C-03 | H/U; sensor inventory and direct GNSS characterization |
| E-005 | C-04; [accelerometer procedure](../accelerometer-rate-test.md) | H/U; reported 50/100/200 Hz results, not raw samples |
| E-006 | [Loopback notes](../realdash-loopback-test.md); C-05/C-06 | H plus A in current server code; IPv6/IPv4 defect and demonstration |
| E-007 | [Combined capture](../combined-sensor-capture.md); C-07/C-08 | H/U; walking and first driving campaign |
| E-008 | [Estimator notes](../estimator-v1.md); C-09 | H plus A for B08/B09 algorithms; initial estimator replay |
| E-009 | C-10/C-11; [live output](../realdash-live-output.md) | H/U; v0.6 deployment and stopped-handling failures |
| E-010 | C-12/C-13; [mount manager](../mount-calibration-v08.md) | H; v0.7 hard-launch/stability defects and v0.8 replay reports |
| E-011 | [Known limitations](../known-limitations.md); C-14 | H/U plus A for vulnerable B08 code; startup/remount divergence |
| E-012 | [Recovered command excerpts](reports/historical-command-excerpts.md); C-16 | A/H; v0.9 development-test failure and successful subsequent build |
| E-013 | [New tablet notes](../tablet-e8a.md); C-15/C-16/C-17 | A/H/U; hardware check, short capture, later pickup clarification |
| E-014 | [Historical XML extraction](reports/historical-replay-summary.json) | A; two surviving local result files; raw input missing |
| E-015 | [Fresh verification](reports/current-verification.json) | A; new unit/lint/build execution against B09 |
| E-016 | Code review against B08/B09, [configuration](configuration-and-design.md) | A/R; as-implemented behavior and untested paths |
| E-017 | Audit of Git/ref history, tracked/ignored paths and recorded temporary capture path | A; no early commits/tags; bench CSV not at recorded path; not proof of deletion everywhere |
| E-018 | Official references linked in configuration and package overview | External primary sources; interpretation limited to cited claims |
| E-019 | Operator's new request for public engineering package and Git push, 2026-09-22 | U; authorization and intended training use |
| E-020 | [Traceability register](traceability.json) | R; links created during this audit, not historical requirement approval |

## Recovered conversation digest

These are deliberately selective, public-safe summaries of user messages and
visible assistant responses. They are **not** verbatim full transcripts. Internal
reasoning, private identifiers, screenshots and command environment details are
excluded. The full conversation is not required for reading this package, but
the digest cannot replace missing raw captures.

| ID / date UTC | Observation / statement | Qualification |
| --- | --- | --- |
| C-01 / Sep 02 | User asked for minimal logging/verification, a clean public repo, tablet characterization before estimator design, and later remote testing. Initial review warned that GPS residuals are not independent validation. | Proposal and design intent; not a formally approved baseline |
| C-02 / Sep 02 | First tablet inspection reported Android 15, accelerometer only, no gyro or continuous orientation sensors. | Hardware report, reflected in existing notes |
| C-03 / Sep 03 | Two early GPS sessions had zero fixes. A later session reported 90 fixes/88.955 s, 1.0005 Hz; user confirmed stationary then walking, around 2.34 mph. | Empty sessions did not measure rate; walking is not driving validation |
| C-04 / Sep 03 | User clarified only the last three accelerometer files were table-stationary. Assistant reported 49.95/99.90/199.78 Hz and approximately 9.32 m/s² magnitude. | Earlier movement files cannot establish stationary noise; scale not independently calibrated |
| C-05 / Sep 03 | v0.3.0 protocol demo could not connect; v0.3.1 explicitly bound IPv4 instead of default IPv6. RealDash monitor reportedly received about 21 frames/s. | Connection recovery, not estimator performance |
| C-06 / Sep 03 | User requested a more realistic 50–60 Hz demo; a 60 Hz simulated drive was installed and observed in RealDash. | Both speed channels were simulated |
| C-07 / Sep 03 | v0.4 capture: indoor accelerometer-only sanity check, then a handheld walking/running test with 73 GPS fixes. | Moving orientation and poor initial fix prevent vehicle-axis conclusions |
| C-08 / Sep 03 | First 12m56s drive: 726 GPS, 77,483 accelerometer samples; fitted forward vector from 354 intervals; user confirmed a hill and end-of-test tablet movement. | Calibration was fitted using the same correction-source data; no independent reference |
| C-09 / Sep 03 | v0.5 reported eight estimator tests and replay success; scalar effective-bias model and provisional axis were installed. | Replay consistency only; early source snapshot missing |
| C-10 / Sep 03 | Assistant proposed saved calibration, GNSS fallback and automatic forward-axis relearning, then recommended integrating v0.6 first and adding calibration management afterward. | Scope/sequencing changed; not all promised features were delivered |
| C-11 / Sep 03 | v0.6 long drive appeared smooth, but handling while stopped produced false motion up to about 13 mph. User confirmed adjusting the tablet. Assistant said it “validates the complete system.” | That validation claim was too strong; symptoms and operator clarification are useful evidence |
| C-12 / Sep 03 | v0.7 high-dynamic drive review reported five launch-triggered zero holds, a high-noise return-to-old-angle loophole, apparent ~1 s speed response lag, and a replay failing its 30 m/s ceiling. | Historical analysis; vehicle identity and raw route omitted |
| C-13 / Sep 03 | v0.8 build/replays reportedly removed the five launch holds; 95th-percentile correction step ~0.61 m/s. | Limited to those data sets; not general fault recovery evidence |
| C-14 / Sep 04 | User described leaving tablet on a seat during slow acquisition, then remounting while driving. Analysis reported 367 rejected fixes and runaway until a stationary correction. | This was an operationally discovered failure, **not a planned deliberate fault-injection experiment** |
| C-15 / Sep 11 | New tablet debugging initially unavailable; photo showed TalkBack settings rather than Android system Developer options. Subsequent inspection reported model E8A with accelerometer only. | Setup issue, not estimator failure; user's “same brand” was not treated as hardware equivalence |
| C-16 / Sep 11 | v0.9 development test failed on sustained hard launch, was revised, then passed. Build/install, ~100 Hz acquisition and 60 Hz packet checks were recorded. | Command evidence recovered; see E-012/E-014 |
| C-17 / Sep 11 | After questions about the bench results, user stated “i picked up the tablet.” | Invalidates any claim that the entire new-tablet capture was confirmed stationary |
| C-18 / Sep 22 | Audit found one remote commit and uncommitted v0.9, no version-by-version history or trace matrix. | Motivates this retrospective package; no history is fabricated |

## Recovery boundaries

- Conversation retrieval reached the initial proposition. Some turns had no visible
  final answer; absence was not filled in with invented results.
- A new file-name search in Documents did not locate the old private drive CSVs.
  The recorded temporary v0.9 bench CSV was also absent. This is a bounded search,
  not a claim that no backup or tablet copy exists.
- The tablet was not interrogated or used for a new drive during this audit.
- Surviving historical XML was summarized before rerunning Gradle because build
  reports are replaceable outputs. Original SHA-256 values are retained; local
  hostname and stack-trace paths are omitted.
- Exact per-version APKs, signing provenance, source trees before B08, and complete
  test-run archives were not recovered. Narrative reports are not substitutes.
