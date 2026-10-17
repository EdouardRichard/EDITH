# F4 question WAV generator (EDITH 0.21 Phase 7)

Generates the **synthetic** WAV used by F4 / M2 to ask the fixed question, so the run does not
depend on a human speaker. Nothing in here is recorded user audio, and nothing binary is
committed: the WAVs land in gitignored `.local/` and are pushed straight to the phone.

Design input: `docs/superpowers/specs/2026-10-17-edith-0.21-phase-7-measurement-design.md` §2.2.
Task: `.superpowers/sdd/2026-10-17-edith-0.21-phase-7-measurement/task-6-brief.md`.

## Fixed inputs (pre-registered — do not change)

| Item | Value |
|---|---|
| Text | `打开音乐，播放李克勤的月半小夜曲` |
| Voice | `Microsoft Huihui Desktop` (zh-CN, Windows SAPI) |
| Rates | `-2` (slower), `0` (normal), `+2` (faster) — SAPI `SpeechSynthesizer.Rate` |
| Requested format | 16 kHz / mono / 16-bit PCM |

The three variants change **speaking rate only**. The text, the voice, and the question wording
must stay identical across the whole measurement. This is the pre-registration rule from design
§2.2: the text may not be rewritten at any point, and the three variants exist only to give the
preflight ASR a better chance of a usable final transcript. If none of the three variants is
recognized as a usable final transcript, F4/M2 is recorded as `NotMeasured`; the text is still
not changed. Variant selection happens in the preflight, not here, and not during the run.

## Generate

Run from the repository root (the default `-OutDir` is relative to the current directory). The p7-f4
scripts target **Windows PowerShell 5.1**, the host used for the Phase 7 run, and must be invoked with
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File` (not bare `pwsh`; PowerShell 7 was not the
measured host, though the same `-File` arguments work there):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/p7-f4/New-QuestionWav.ps1
```

Output (gitignored):

```text
.local/p7-f4/wav/p7-question-music-rate-2.wav
.local/p7-f4/wav/p7-question-music-rate0.wav
.local/p7-f4/wav/p7-question-music-rate2.wav
```

The script selects `Microsoft Huihui Desktop`, or fails listing the installed voices. It asks for
16 kHz / mono / 16-bit PCM; if that voice refuses the format it falls back to the voice default,
prints a `WARNING`, and always reports the **actual** sample rate / channels / bits parsed from the
WAV `fmt ` chunk (the injector resamples, but evidence records what was really produced). Every
file is re-verified as `RIFF`/`WAVE` with a non-empty `data` chunk, and its size and SHA-256 are
printed.

To choose another directory:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/p7-f4/New-QuestionWav.ps1 -OutDir .local/p7-f4/wav-2
```

## Push to the phone

Only the phone `2696813b`; never the glasses. Do not touch the other WAVs already under
`/data/local/tmp/edith-audio/` — push adds files and overwrites only these three names.

```powershell
adb -s 2696813b push .local/p7-f4/wav/p7-question-music-rate-2.wav /data/local/tmp/edith-audio/
adb -s 2696813b push .local/p7-f4/wav/p7-question-music-rate0.wav /data/local/tmp/edith-audio/
adb -s 2696813b push .local/p7-f4/wav/p7-question-music-rate2.wav /data/local/tmp/edith-audio/
adb -s 2696813b shell ls -l /data/local/tmp/edith-audio/p7-question-music-*.wav
```

The injected round then uses `edith.DEBUG_INJECT` with the chosen variant after the wake word
(design §2.3 step 5).

## Notes

- `New-QuestionWav.ps1` is intentionally **UTF-8 with BOM**. Windows PowerShell 5.1 reads
  BOM-less `.ps1` files as CP936, which would corrupt the CJK literal; the script also asserts the
  literal's codepoints so such a mistake fails loudly instead of synthesizing different words.
- The WAVs are covered by the repository's `**/*.wav` ignore rule and live under `.local/`; commit
  only this README and the generator.
- `Microsoft Huihui Desktop` must be installed (`Get-InstalledVoice` / Control Panel → Speech).
