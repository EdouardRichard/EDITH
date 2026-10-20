# EDITH for Rokid Glasses

EDITH is an Android host for a Rokid Glasses assistant. Shared audio, speech, model, storage, display, and cancellation facilities belong to the app; the `plugin-smart-helper` plugin owns scene triggers, business context, prompts, model decisions, and tool choices. The interview assistant (P4) is shelved for this version (D57).

## Current state

- **0.21 is closed as "not fully passing"**: M2 0/10 (a measured failure), AC-13/16 device behavior failed, and several items remain `NotMeasured`. It is **not** a delivered release.
- The authoritative documentation set is five entries: `docs/project/STATUS.md`, the 0.21 baseline architecture spec under `docs/superpowers/specs/`, `docs/project/DECISIONS.md`, `docs/project/CONTRACTS.md`, and `docs/project/ACCEPTANCE.md`. Historical docs are retired; recover them from git history rather than restoring them to the working tree.
- Verification uses the Redmi K40 (`2696813b`, Android 13 / API 33) and the glasses `1901092544027662`. K70 is not a target device and is not separately accepted (D46/D65).
- Raw recordings, biometric samples, tokens, model files, SDK packages, APKs, and temporary probe output are excluded from version control. Credentials are never committed or recorded.

Start with `AGENTS.md`, then `docs/project/STATUS.md`.

## Glasses accessibility overlay (required, ADB only)

The vendor home HUD (time / temperature / signal / battery) is a system window at layer **271000**, above every
`TYPE_APPLICATION_OVERLAY`; a normal app overlay cannot cover it. EDITH therefore draws the panel through the glasses
accessibility service's `TYPE_ACCESSIBILITY_OVERLAY` (layer **311000**) plus a full-screen opaque backdrop while the
panel is visible. The transparent CXR scene Activity stays transparent.

**The glasses has no system settings page for this permission. It must be enabled with ADB.** Read the current value
first and preserve every existing service; never overwrite another line's accessibility service:

```powershell
$serial = "1901092544027662"
$component = "com.sheen.rokid.glasses/com.sheen.rokid.glasses.accessibility.GlassesAccessibilityService"

# 1. Read the current list (may be null or a colon-separated list) and append, never replace.
$old = (adb -s $serial shell settings get secure enabled_accessibility_services).Trim()
$new = if ([string]::IsNullOrWhiteSpace($old) -or $old -eq "null") { $component } else { "$old`:$component" }
adb -s $serial shell settings put secure enabled_accessibility_services $new

# 2. Enable accessibility globally and read back the binding, not just the setting value.
adb -s $serial shell settings put secure accessibility_enabled 1
adb -s $serial shell dumpsys accessibility | Select-String "Bound services|EDITH"
```

A written setting is not proof that the service is bound: `dumpsys accessibility` must show an EDITH bound service
before the panel can use the accessibility overlay. If it is not enabled, the panel falls back to the old
`TYPE_APPLICATION_OVERLAY` and the vendor HUD remains visible (the service still works, but the overlap returns).

## Phone lock-screen unlock (swipe + PIN)

Device-facing tool calls (`device`, `open`, `dial`) go through the phone's lock-screen gate. `shell` is deliberately not
gated: Shizuku can run a command without a UI, so raw shell stays available while the phone is locked.

- **Swipe lock** is dismissed without a credential (`wm dismiss-keyguard`).
- **PIN lock** uses the PIN configured in **设置 → 锁屏自动解锁**. It is stored only on this phone with an Android
  Keystore AES/GCM key, never written to logs or uploaded. The unlock sends the digits as **key codes**, not as an
  `input text` argument, and verifies the keyguard afterwards.
- The gate is single-flight (one PIN attempt even when several tools arrive), keeps the display awake for a bounded
  window, and waits for the accessibility service to reconnect before a `device` tool runs. A failed PIN is not retried
  automatically, to avoid tripping the platform lockout.
- Only numeric PINs of 4–16 digits are supported. Pattern locks and non-ASCII passwords cannot be entered by this path
  and are refused rather than half-supported.

The Shizuku server must already be running and EDITH must be authorized in Shizuku. The session foreground service
keeps the App alive; if the App process is killed while the phone is locked, a fresh Shizuku user-service bind is not
guaranteed, so start the session while the phone is unlocked.
