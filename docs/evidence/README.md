# Engineering evidence package

Prepared retrospectively on **2026-09-22** for engineering review and AI-training
task construction. This is a terrestrial, experimental, forward-speed estimator,
not an aerospace-qualified navigation system.

## Executive assessment

The project demonstrated approximately 1 Hz direct GNSS acquisition, approximately
100 Hz accelerometer acquisition, and a 60 Hz local telemetry path. It also
demonstrated why a smooth gauge and successful nominal replays are not sufficient
evidence of robustness: a moving startup followed by remounting caused the v0.8
estimator to diverge while its correction gate rejected the measurements that
could have recovered it. The consumer displayed the bad value despite an invalid
status flag. v0.9 adds guarded fusion eligibility, state reset, and independent
publisher fallback. It is **not independently validated for driving accuracy**.

Historical Git coverage is incomplete. At the start of this audit the remote had
one commit, containing v0.8. Earlier versions were iterations in a working tree,
not separately preserved releases. v0.9 was uncommitted locally. This package
preserves that implementation without inventing earlier commits or backdating
records. The recovered conversation substantially improves the narrative history;
it does not restore missing sensor recordings or exact early source snapshots.

## Reading order

1. [Evidence sources and confidence](provenance.md): what survives and what does not.
2. [Revision history](revision-history.md): milestones, failures, fixes, evidence limits.
3. [Failure records and lessons](anomalies-and-lessons.md): engineering and assistant errors.
4. [Architecture and configuration](configuration-and-design.md): actual implementation.
5. [Requirements and traceability](requirements.md): retrospective requirement baseline.
6. [Verification and test catalog](verification.md): tests, outcomes, and coverage limits.
7. [Recorded campaigns](test-campaigns.md): reported measurements and confounders.
8. [Open evidence gaps](evidence-gaps.md): remaining work, not implied completion.
9. [Training adaptation](training/authoring-notes.md): participant/evaluator separation.

Machine-readable material:

- [Traceability register](traceability.json): requirements, test symbols, manual checks.
- [Historical replay extraction](reports/historical-replay-summary.json).
- [Recovered command excerpts](reports/historical-command-excerpts.md).
- [Fresh verification summary](reports/current-verification.json).
- [Artifact manifest](manifest.json): baseline identifiers and file hashes.

## Baselines

| Baseline | Identifier | Meaning |
| --- | --- | --- |
| B08 | `5939a692209b72b32f89f3b993eb6e598e51bc99` | Existing initial Git commit; app version 0.8.0, code 10 |
| B09 | `0c347d8918006ac4b0babe98ebe298beebd7ed8c` | v0.9 working tree preserved during this audit; version 0.9.0, code 11 |
| Evidence edition | Git commit containing this directory | Retrospective records, not a new estimator revision |

The audit does not retune or repair the estimator. Runtime changes relative to B08
are the previously implemented v0.9 work. Documentation corrections identify
overclaims rather than silently rewriting historical observations.

## Light assurance organization, not compliance

We borrow a small set of useful practices: identified requirements, controlled
baselines, source/test links, recorded results, anomaly dispositions, and explicit
limitations. These are an organizational aid, **not a claim that any DO-178C
objective, software level, independence requirement, or certification process has
been satisfied**. No PSAC, safety assessment, qualified tool chain, structural
coverage/MC/DC analysis, or flight qualification is provided.

FAA [AC 20-115D](https://www.faa.gov/documentLibrary/media/Advisory_Circular/AC_20-115D.pdf)
recognizes DO-178C/ED-12C as an acceptable means for airborne software assurance.
That recognition does not make this retrospective package compliant. This package
does not reproduce the standard or assign its objectives to an arbitrary level.

## Interpretation rules

- A passing test establishes its assertions under its inputs, not general safety.
- An assistant's historical claim of a passed drive is a report, not raw evidence.
- GPS residuals measure consistency with a correction source, not independent accuracy.
- Transport rate is not measurement rate, display refresh rate, or useful fused availability.
- A condition absent from a log is not a demonstrated passing test.
- No failure found for a revision means **no failure documented**, not no failure existed.
- No private raw telemetry, route coordinates, serial numbers, accounts, signing keys,
  full conversation exports, or APKs are included in this public package.

Run `python3 tools/check_evidence.py` to check links between the register and test
symbols and verify the manifest. That checks package integrity, not certification.
