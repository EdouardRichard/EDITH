# EDITH for Rokid Glasses

EDITH is an Android host for a Rokid Glasses assistant. Shared audio, speech, model, storage, display, and cancellation facilities belong to the app; the `plugins-smart` plugin owns scene triggers, business context, prompts, model decisions, and tool choices. The interview assistant (P4) is shelved for this version (D57).

## Current state

- **0.21 is closed as "not fully passing"**: M2 0/10 (a measured failure), AC-13/16 device behavior failed, and several items remain `NotMeasured`. It is **not** a delivered release.
- The authoritative documentation set is five entries: `docs/project/STATUS.md`, the 0.21 baseline architecture spec under `docs/superpowers/specs/`, `docs/project/DECISIONS.md`, `docs/project/CONTRACTS.md`, and `docs/project/ACCEPTANCE.md`. Historical docs are retired; recover them from git history rather than restoring them to the working tree.
- Verification uses the Redmi K40 (`2696813b`, Android 13 / API 33) and the glasses `1901092544027662`. K70 is not a target device and is not separately accepted (D46/D65).
- Raw recordings, biometric samples, tokens, model files, SDK packages, APKs, and temporary probe output are excluded from version control. Credentials are never committed or recorded.

Start with `AGENTS.md`, then `docs/project/STATUS.md`.
