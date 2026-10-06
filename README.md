# EDITH for Rokid Glasses

EDITH is an Android host for two user-selectable assistants targeting mainland Rokid Glasses: a general assistant and an interview assistant. Shared audio, speech, identity, model, storage, display, and cancellation facilities belong to the app; plugins own scene triggers, business context, prompts, and model decisions.

## Current state

- P0 hardware and SDK probing is recorded in `docs/project/`.
- P1 contains six Kotlin/JVM modules and 13 passing tests. The P2 recovery audit records gaps in configuration, cancellation, audio state, backpressure, and output clearing.
- P2-DESIGN-r3 and P2-PLAN-SET-r1 are approved for Inline Execution. DeepSeek Responses is for development testing; production model profiles will be user-configurable, with separate text/image/audio capabilities. See the project state for the active D: worktree and implementation checkpoint.
- Local development model settings are in the ignored `.local/p2-text-model.properties`; enter the API key locally. Production credentials will be configured in the app.
- Raw recordings, biometric samples, tokens, local model files, SDK packages, APKs, and temporary probe outputs are intentionally excluded from version control.

Start with `AGENTS.md`, then `docs/project/PROJECT_STATE.md` and `docs/project/HANDOFF.md` when resuming work.
