# The EmbeddingGemma tokenizer is **BPE**, not unigram — task 6b root cause

- **Date**: 2026-10-13 (Phase B execution session, second sitting)
- **Checkpoint**: `edith-0.2-phase-b-exec-20261013-03`
- **Model**: `.local/models/embeddinggemma/sentencepiece.model` (4,683,319 B, SHA not re-measured here)
- **Tooling**: `.local/p3-kws-tools/venv` → `sentencepiece` 0.2.2
- **Supersedes**: the "Viterbi objective is open" ruling in
  `.superpowers/sdd/2026-10-13-edith-0.2-phase-b-core/progress.md`

## The finding

`TrainerSpec.model_type == 2 == BPE`, read from the model's own wire format (field 3 of top-level
field 2). `sentencepiece_model.proto` defines `UNIGRAM = 1; BPE = 2; WORD = 3; CHAR = 4;`.

    TrainerSpec.model_type = 2  ->  BPE
    byte_fallback = 1 ; unk_id=3 bos_id=2 eos_id=1 pad_id=0

**For a BPE model, sentencepiece never executes `unigram_model.cc` — it executes `bpe_model.cc`.**
`phone-app/src/main/kotlin/edith/phone/litert/SentencePieceUnigram.kt` therefore implements the wrong
algorithm, and every question in the previous handoff about "which Viterbi direction" rested on a
false premise. There was no comparison operator to flip.

## Why the score table looked impossible

`get_score()` is not a log-probability in this model; it is a monotone function of the **BPE merge
rank**. That is why `score == 494 - id` holds exactly for 236249 of 262144 pieces:

| id | piece | `get_score()` | `494 - id` |
|---|---|---|---|
| 494 | `▁t` | −0.0 | 0 |
| 495 | `in` | −1.0 | −1 |
| 500 | `re` | −6.0 | −6 |
| 1111 | `val` | −617.0 | −617 |
| 9259 | `Hello` | −8765.0 | −8765 |
| 41856 | `EDIT` | −41362.0 | −41362 |

A sum over merge ranks is meaningless, so a sum-based Viterbi could never match. The 25895 exceptions
are the control band (ids 0..237, score 0.0), the byte band (ids 238..493, score 0.0) and the
single-character band from id 236743, which sits on the same line shifted by 31.

## What the oracle actually does

`bpe_model.cc` (v0.2.2), implemented 1:1 in `.local/t6b-bpe.py`:

1. split the normalized text into symbols — one per character (no `UNUSED` pieces exist in this model)
2. for every adjacent pair, look up the concatenation; if it is a real non-reserved piece, push a
   candidate scored by that piece's table score
3. repeatedly pop the best candidate (higher score wins; **ties go to the smaller left index**),
   merge it, and push the two newly-adjacent pairs
4. output the surviving symbols in order

Result: **68 of 69 strings reproduce `sp.encode` exactly** (`.local/t6b-bpe.txt`). For comparison,
over the same corpus the current Kotlin algorithm (max-sum Viterbi) matches 19 of 68, and the owner's
minimise hypothesis matches 5 of 68.

Disputed strings now reproduce: `Hello World → Hello|▁World`, `EDIT → EDIT`, `EDITH → ED|ITH`,
`café → caf|é`, `retrieval → ret|rie|val`, `Rokid → Rok|id`, `belie → bel|ie`,
`unbelievable → un|bel|ievable`, `SENTENCEPIECE → SENT|ENCE|PI|ECE`.

## Byte fallback: the rule, measured

`byte_fallback = 1` and the byte pieces exist (ids 238..493, surface `<0xNN>`, `score = 0.0`,
id = 238 + byte value). The existing Kotlin check `pieceTypes[id] == TYPE_BYTE && raw.size == 1` can
never fire, because the stored surface is six characters (`<0xC2>`), not one raw byte — so the byte
table is empty and `bytePiece()` always returns `-1`.

The measured rule is **not** "always explode to bytes": it is *if the character has no piece at all,
emit one byte piece per UTF-8 byte*.

| input | oracle pieces | note |
|---|---|---|
| `\u00a0` (NBSP) | `<0xC2>`, `<0xA0>` | no piece → one byte piece per byte |
| `\u00a0a` | `<0xC2>`, `<0xA0>`, `a` | fallback per character, then normal pieces resume |
| `\u0000` | `<0x00>` (id 238) | |
| `\u2028` | `<0xE2>`, `<0x80>`, `<0xA8>` | three bytes |
| `\u0001` | `\x01` (id 249732) | a real piece exists → **no** fallback |
| `\U0001F600` | `😀` (id 242398) | a real piece exists → **no** fallback, despite being 4 bytes |

`EmbeddingTokenizerTest`'s existing expectation `bytePiece(0xC2) == 432`, `bytePiece(0xA0) == 398`,
`bytePiece(0x00) == 238`, `bytePiece(0xFF) == 493` is consistent with `id = 238 + byte`.

## Consequence

The fix is a rewrite of `SentencePieceUnigram.kt` into a BPE encoder (symbol list + merge agenda +
resegmentation), plus the byte table keyed by the `<0xNN>` surface. The class name is a misnomer. The
6 red tests in `EmbeddingTokenizerTest` keep their literal expectations — those are the publisher's own
output — but they now drive the BPE implementation.

## Artefacts (all under `.local/`, git-ignored)

- `t6b-bpe.py` — the reference BPE encoder, 68/69; **this is the executable specification**
- `t6b-trainerspec.py`, `t6b-ROOT-CAUSE-BPE.txt` — `model_type == 2` proof
- `t6b-bpe.txt` — per-string agreement table
- `t6b-byte-fallback.py`, `t6b-byte-fallback.txt` — the fallback rule measurements
- `t6b-verdict.py`, `t6b-report.py`, `t6b-audit4.py` — the direction measurements (5/68 and 19/68),
  kept so the superseded question is not re-opened

---

## 2026-10-14: the port was still wrong in **three** ways — found by an independent review, then measured

The BPE rewrite landed on 2026-10-13 with its own tests green, and this file's byte table was written from
measurement. It was still not the publisher's encoder. An independent review of the increment
(`6d5a0a6`,`3678e64`,`b812da3`) found two defects, and a corpus check found a third that the review had missed:

1. **The `USER_DEFINED` pre-match was missing (the review's I1).** `bpe_model.cc` has two stages; only the merge
   stage was ported. The pre-match takes the **longest** match against pieces of type 4 and freezes it. This model has
   **6410** of them (`<mask>`, `[multimodal]`, `[toxicity=0]`, `<ctrl1>`…`<ctrl3600>`, `<start_of_*>`/`<end_of_*>`, and
   the run-length symbols), and the merge agenda cannot assemble `<mask>` out of `<` + `mask` + `>`.
   The first port skipped it because it read "no `UNUSED` pieces" as "no pre-match": `UNUSED` is type **5** and really
   is absent; `USER_DEFINED` is type **4**. Measured (`sp.encode`, python-sentencepiece 0.2.2):
   `<mask>` → `[4]` (the old port gave `['<','mask','>']`), `<ctrl10>` → `[16]` (not `<ctrl1>` + `0`), and
   **`<pad>` still splits** into `['<','pad','>']` — CONTROL symbols are *not* pre-matched, which is why the fix is
   keyed on type 4 and not on "the low id band".
2. **The one-byte byte-fallback guard (the review's I2).** `if (bytes.size <= 1) symbol.add(c)` exempted every
   single-byte character, so a control character with no piece resolved through the vocabulary lookup to `<unk>`.
   The table above (row `\u0000` → `<0x00>`, id 238) states the publisher's behaviour; the shipped code did not
   reproduce it. Re-measured here, independently: `\u0000`→238, `\u0004`→242, `\u000b`→249, `\r`→251, while
   `\u0001`→249732 and `\t`→255968 (real pieces, never exploded).
3. **Non-BMP characters were split into surrogate halves (found by the corpus, not by the review).** The scan
   iterated `Char`, so `😀` became two lone surrogates, each of which has no UTF-8 encoding and turned into a `?`
   byte. Measured: `emoji 😀 here` → the publisher gives `[67906, 163543, 1590]`, the old port gave
   `[67906, 236743, 301, 301, 1590]`. The fix iterates **code points**. (`U+10000` legitimately falls back to
   `F0 90 80 80` → byte pieces `[478, 382, 366, 366]`.)

**The instrument that settled all three is a corpus, not an opinion.** `.local/t6b2-corpus.py` writes
`.local/t6b2-corpus.tsv`: **7305** cases, each `<utf8-hex>\t<ids>` as produced by the publisher's own
`SentencePieceProcessor` — the previous 69 strings, **every one of the 6410 `USER_DEFINED` surfaces**, every one-byte
code point, UTF-8 boundary code points including non-BMP, runs of the space marker (1..100) and newline (1..40), and
600 seeded random strings over eight scripts. `EmbeddingTokenizerTest.every case in the publisher corpus encodes
identically` asserts **0 disagreements** (it skips, loudly, when the gitignored corpus is absent), and three literal
tests pin the specific semantics so the corpus is not the only guard. Result: **7305/7305**, against the same encoder
that produced the table above.

**What this costs and what it does not prove.** The publisher's C++ source was *not* used for the fix — it is neither
vendored nor reachable from this host (no network at the time), so the semantics were pinned by the publisher's own
encoder over the corpus rather than by reading `bpe_model.cc`. A pre-match rule the corpus never exercises could still
differ; the corpus covers the whole type-4 table and the character classes, but it cannot cover an input nobody
generated. The earlier reference encoder `t6b-bpe.py` is kept for history and is **not** an adequate specification: it
skipped the pre-match and split non-BMP characters, which is why its 68/69 headline read as a success.
