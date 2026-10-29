# EDITH 0.2 Phase A — On-device inference performance (K40)

**Date**: 2026-10-13
**Device**: Redmi K40 (`2696813b`), Snapdragon 870, 12 GB RAM, MIUI / Android 13 (API 33)
**Build**: `phone-app` debug, `edith.phone` + `edith.phone.test`, both installed at the commit below
**Test**: `phone-app/src/androidTest/kotlin/edith/phone/litert/LiteRTPerformanceTest.kt`
**Command**: `adb -s 2696813b shell "am instrument -w -e class edith.phone.litert.LiteRTPerformanceTest edith.phone.test/edith.phone.PhoneTestRunner"`
**Result**: `OK (3 tests)`, 56–59 s wall

This is the **A5** acceptance item. Per `D59` these are K40 *development-device* reference values;
K40 is not the delivery target (that is the K70 Ultra / Dimensity 9300+), and a missing K70 Ultra does
not block Phase A.

## What was measured, and what was not

**Measured** — the real converted graphs, loaded through LiteRT 2.1.0's `Interpreter` on the phone:

- model load (mmap + graph init), one sample each;
- graph forward-pass latency, 3 warm-up passes then 20 measured passes, 4 CPU threads;
- combined resident set size with both models mapped *and* touched.

**Not measured** — and therefore not claimed:

- **Tokenizer and prompt-template cost.** The Kotlin port of `laya_host.py`'s prompt builder and the
  SentencePiece tokenizer are not implemented yet (see the TODOs in `LayaLiteRTEngine` /
  `EmbeddingGemmaLiteRTEngine`). The numbers below are *graph* cost, which dominates; the tokenizer adds
  single-digit milliseconds on top and does not change any conclusion here.
- **NPU execution.** `CompiledModel` exposes `QualcommOptions` for it; nothing here uses it.
- **Real task accuracy.** These runs use synthetic token ids, so they measure cost and cross-check the
  two APIs against each other — they say nothing about whether the models answer well.
- **Warm-up delta.** The first-pass cost was not isolated; load and latency are reported separately.

## Graph contracts, as read off the device

Both graphs were introspected on the phone rather than taken from the model card. They match the card.

| Graph | Inputs | Outputs |
|---|---|---|
| `laya_ml_s256_fp32.tflite` | `input_ids [1,256] INT32`, `attention_mask [1,256] FLOAT32`, `qtype_onehot [1,3] FLOAT32` | `token_logits [1,256] FLOAT32`, `pooled_cls [1,768] FLOAT32` |
| `embeddinggemma-300M_seq256_mixed-precision.tflite` | `[1,256] INT32` | `[1,768] FLOAT32` |

The Laya `pooled_cls` output is a real computation, not zeros or NaN —
first four values `[-14.697, 88.528, 148.205, -103.407]`. Both graphs are asserted non-zero and
NaN-free in the test, so a graph that "ran" but produced nothing cannot pass as a fast success.

## Results

Two LiteRT execution APIs, same graphs, same device. The `Interpreter` API is the legacy one; the
`CompiledModel` API is what the model card's own figures were taken with. Both are measured because the
choice between them turns out to matter more than the accelerator does.

| Metric | `Interpreter` | `CompiledModel` (CPU, 4 threads) | `CompiledModel` (GPU, OpenCL FP32) | Gate |
|---|---:|---:|---:|---|
| Laya load | 272 ms | — | — | — |
| Laya forward pass | median **1815 ms** (p95 2018) | median **569 ms** (σ<0.2 %) | ~226 ms — **output unverified, not reportable** | — |
| EmbeddingGemma load | 270 ms | — | — | — |
| EmbeddingGemma forward pass | median **584 ms** (p95 590) | median **191 ms** (samples 189–197) | median **242 ms** (output **verified**) | p95 < 150 ms |
| Both models resident (RSS delta) | **1132 MB** (120 → 1252) | — | — | < 1500 MB — **Met** |
| vector search p95 @1000×256 | not measured on device | — | — | p95 < 50 ms — **NotMeasured** |

**The API choice is the biggest single lever found.** `CompiledModel` on the CPU is **3.2× faster than
`Interpreter`** for Laya (569 vs 1815 ms) and **3.1×** for EmbeddingGemma (191 vs 584 ms). Phase B should
use `CompiledModel`; the `Interpreter` numbers are the reason the first embedding reading looked
hopeless against a 150 ms gate.

> **Superseded — see "Correction" below.** The wall-clock difference is real but its cause is that
> `Interpreter` never used the four threads it was given, not that `CompiledModel`'s arithmetic is
> faster: the two paths produce **bit-identical** output, and `CompiledModel` at one thread equals
> `Interpreter` at four. The recommendation to use `CompiledModel` stands; the "3× on any device" reading
> of it does not.

With `CompiledModel` the embedding graph lands at **191 ms**, i.e. **1.3× the gate**, not 3.9×. The
K40 is the 2021 development device, so this is within reach of the delivery target.

Propagation error is negligible on the CPU paths: the per-sample spread is under 2 % for both graphs and
both APIs, so these are stable numbers rather than thermal noise.

The `laya_ml_s256_fp32` graph is ModernBERT-large (421M) plus the decision head; the multilingual
checkpoint's card lists ~0.68 GB resident and 51 ms/question on a Galaxy S26 GPU, 222 ms on its CPU. The
K40 `CompiledModel` CPU reading of 569 ms is 2.6× that CPU reference — expected for a four-year-older
part.

### The GPU path: reachable, and not a win *on an idle device* (see the correction below)

No separate GPU artifact is needed — `litert:2.1.0` ships `com.google.ai.edge.litert.CompiledModel` and
`libLiteRtOpenClAccelerator.so`. Both graphs compiled and ran on the K40's Adreno 650 with explicit
`Precision.FP32`:

- **EmbeddingGemma on GPU is *slower* than on CPU**: 242 ms vs 191 ms (speedup 0.79). Its output was
  cross-checked against the CPU's and **agrees** (max abs difference 0.015 on a scale of 0.18, identical
  argmax), so this is a trustworthy negative result — the GPU is not the lever for this graph.
- **Laya on GPU is unverified and must not be reported.** It ran at ~226 ms, but its `token_logits`
  differ from the CPU's by up to **3.31 on a scale of 4.57** with a different argmax. That is far larger
  than FP32 rounding, so the GPU is either computing in a lower effective precision somewhere or the
  `CompiledModel` input mapping differs from the `Interpreter`'s. Until one of those is settled, ~226 ms
  is not a measurement of inference. The test logs `COMPILED_LAYA_GPU_UNVERIFIED` and fails rather than
  let this be quoted as a 2.5× win.

**GPU submissions are asynchronous and the first samples lie.** The five timed runs of one graph read
`[0.41, 0.37, 228.30, 225.58, 225.52]` ms (Laya) and `[1.39, 0.80, 244.05, 241.85, 242.90]` ms
(embedding): the first two submissions returned in about a millisecond, before the work was done, and
only once the command queue saturated did `run()` block. The first version of this test measured exactly
one such early sample and reported a 295× speedup; the value cross-check is what caught it. Any future
GPU latency figure must discard the early samples and must be accompanied by an output comparison.

## Two findings this measurement produced

### 1. Matryoshka truncation must be re-normalized — fixed

The EmbeddingGemma graph L2-normalizes its 768-dim output (measured norm `1.0000`), but a *prefix* of a
unit vector is not unit-length: the device measured the 256-prefix at **norm 0.643**. Cosine similarity
against the truncated vector was therefore wrong by that factor.

Truncation and re-normalization are now one operation in `EmbeddingServiceImpl.toOutputDimensions`, with
`EmbeddingDimensionPolicyTest` pinning it (4 tests, RED before the fix). This is the failure mode the
spec's Review Focus called out; it is no longer theoretical.

### 2. The Java-heap memory probe does not measure these models — method replaced

The first version of the memory test read `Runtime.totalMemory() - freeMemory()` and reported
`DELTA_MB=0`, which would have "passed" the 1.5 GB gate while proving nothing: TFLite maps the model
files, so a 1.2 GB graph costs a few MB of Java heap. The probe was replaced with `VmRSS` from
`/proc/self/status`, and both graphs are now run once so their weights are faulted in rather than merely
mapped. The first reading was discarded, not reported.

## Correction (2026-10-13, later the same session): the "3.2x API win" was a thread count, not an API

The first version of this document reported `CompiledModel` as 3.2x faster than `Interpreter` on the CPU
and called the API choice "the biggest single lever found". Two follow-up measurements show that framing
was wrong and the correction matters, because a thread-count artefact does not transfer across devices
the way a genuinely better kernel does.

**1. Both CPU APIs produce bit-identical output.** Run on the same inputs, every output tensor of both
graphs matched exactly — `maxAbsDiff = 0.0` on all three (embedding `[1,768]`, Laya `token_logits`
`[1,256]` and `pooled_cls` `[1,768]`), with identical argmaxes. So there is no precision trade and the
two paths run the same kernels; nothing about `CompiledModel`'s arithmetic is faster.

**2. `Interpreter`'s `setNumThreads(4)` does not take effect on these graphs.** Thread scaling, median of
5 runs each:

| threads | `CompiledModel` Laya | `CompiledModel` embed | `Interpreter` Laya |
|---:|---:|---:|---:|
| 1 | 1809 ms | 584 ms | 1808 ms |
| 2 | 1015 ms | 331 ms | 2018 ms |
| 4 | **579 ms** | **196 ms** | 2009 ms |

`CompiledModel` scales almost linearly with threads (1→2: 1.78x, 2→4: 1.75x). `Interpreter` does not move
at all — its 1-thread and 4-thread readings are the same number within noise. **`CompiledModel` at one
thread (1809 ms) equals `Interpreter` at four (1808 ms).** The entire 3.2x is the difference between a
path that used the four threads it was asked for and one that did not.

This also makes the earlier 1815 ms read as a *single-core* number, which is what makes the arithmetic
close: the graph's encoder is on the order of 64 GFLOP per forward pass (roughly 125M encoder parameters
— the 1.29 GB file is dominated by a large token-embedding table, which costs lookup bandwidth, not
FLOPs — at a 256-token window). That is ~35 GFLOP/s on one core (68 % of an A77 prime core's ~51
GFLOP/s fp32 NEON peak) and ~110 GFLOP/s across four (66 % of the four big cores' ~167 GFLOP/s). Both
land where a real kernel should, so the numbers are now internally consistent instead of implying 290
GFLOP/s from a chip that peaks at 167.

**What survives from the original finding**: `CompiledModel` is still the API to use — for the
preallocate-then-run structure and because it honours the thread setting. What does not survive is
"switching APIs buys 3x on any device": on a device where `Interpreter` *does* thread correctly, the two
would be equal.

## Cross-device estimate: Dimensity 9300+ (K70 Ultra) — superseded by the measurement at the end of this file

Per `D59` the delivery target is the Redmi K70 Ultra. Nothing below is a measurement; it is an
architecture-level estimate, and it is written out with its inputs so it can be checked or replaced by
the instrumented test, which now runs on any attached device.

**Model.** The workload is compute-bound (the near-linear thread scaling above is the evidence), and the
kernels are generic NEON shipped inside LiteRT, so the first-order predictor is fp32 SIMD throughput,
which for these cores is 2 x 128-bit FMA = 16 FLOP/cycle each:

| | Cores actually available to LiteRT | Peak fp32 |
|---|---|---:|
| Snapdragon 870 (K40, measured) | 1x A77 @3.2 + 3x A77 @2.42 (the 4x A55 @1.8 are too weak to matter) | **167 GFLOP/s** |
| Dimensity 9300+ (K70 Ultra) | 1x X4 @3.40 + 3x X4 @2.85 + 4x A720 @2.00 (no little cores) | **319 GFLOP/s** |

Ratio **1.91x**. Applied to the K40 readings:

| | K40 (measured) | K70 Ultra (estimated) | gate |
|---|---:|---:|---|
| Laya forward pass | 579 ms | **~305 ms** | — |
| EmbeddingGemma forward pass | 196 ms | **~103 ms** | 150 ms — would **meet** it |

**The dominant assumption is thread count, not clock speed.** Four of the Dimensity's eight cores are
A720s at 2.0 GHz contributing 40 % of its peak. If LiteRT's CPU kernels effectively use only the four X4
cores, the ratio collapses to 191/167 = **1.14x** and the estimate becomes ~510 ms and ~172 ms — i.e. the
embedding gate would still be missed. The 8-thread branch gives ~305/~103; the 4-thread branch gives
~510/~172. **Treat ~300 ms and ~100 ms as the optimistic end of the range and ~510 ms / ~170 ms as the
pessimistic end**, and measure rather than trust.

Two further effects, in opposite directions and neither quantified here:

- **Thermals favour the K70.** The K40 figures come from a 134-second run of back-to-back multi-thread
  work on a 7 nm part; the Dimensity is TSMC N4P and should hold its clocks better, so the real ratio may
  exceed 1.91x.
- **The GPU and NPU are not in this model at all.** The Adreno 650 was not a win on the K40 (verified
  slower than CPU for the embedding graph), but the Immortalis-G720 MC12 is a far larger step up than the
  CPU is, and the model card's own figure for this multilingual graph via the Hexagon-class NPU path is
  37 ms. Neither has been verified on our side, so neither is claimed.

**The correction to the earlier "3.2x" also matters here**: that number was a K40-specific threading
artefact. Anyone extrapolating it to the K70 would be extrapolating the wrong thing. The table above
extrapolates the single-thread and 4-thread readings separately, which is the form that transfers.

## GPU on the K40: tested, and the answer is a trustworthy "no" — plus a package we picked wrong

The GPU was not left unmeasured for lack of trying. Follow-up experiments on the Adreno 650 settled what
the mismatch is and is not:

- **Not a precision setting being ignored.** Forcing `Precision.FP16` on the fp32-weight graph produces
  **NaN** — exactly the fp16 overflow the card documents for this graph — and differs from the FP32
  output. So `Precision.FP32` is honoured and precision is not the cause.
- **Not the input order.** A write-width probe located `qtype_onehot` at buffer [2] by its 12-byte
  footprint rejecting 256-element writes, matching the assumed order; the Interpreter's tensor names are
  `serving_default_input_ids` (INT32), `serving_default_attention_mask` (FLOAT32),
  `serving_default_qtype_onehot` (FLOAT32).
- **It is the GPU's arithmetic.** Both graphs compile and run on the Adreno 650, but the GPU's Laya output
  disagrees with the CPU's at the semantic level — `token_logits` by 3.31 on a scale of 4.57, `pooled_cls`
  by 98.8 on a scale of 289.6 — while the two CPU APIs agree **bit for bit**. The CPU is self-consistent
  and the GPU is the outlier. This reproduces across runs.

The model card explains the boundary exactly: its GPU figures were validated on **one** device, "one
Snapdragon 8 Elite Gen 5 phone" (an Adreno 8xx), where the same file reads a maximum probability
difference of **0.0001** against the reference. Its own words: *"Other Android GPUs and NPUs have not been
validated here."* The Adreno 650 is outside that set, and this measurement shows why the caveat matters
rather than being boilerplate.

**A package-selection error on our side, found while reading that card.** We took
`litert-community/laya-LiteRT` — the *token-id* form, with the 256k x 768 embedding table **inside** the
graph. The card's recommendation for phones is a different package,
[`Laya-Multilingual-LiteRT`](https://huggingface.co/litert-community/Laya-Multilingual-LiteRT), which
takes embedding rows instead and keeps the table on the host:

| | our package (token ids, table in graph) | recommended package (embeds, table on host) |
|---|---:|---:|
| Phone file set | 1.29 GB | **0.68 GB** (graph 239 MB + table 375 MB) |
| S26 CPU median | 222 ms | **163 ms** |
| S26 GPU FP32 | 50.8 ms (previous revision) | 51.9 ms |
| GPU compile | ~7 s | **1.3 s**, 1809/1809 one partition |
| Validation errors | 81/81 argmax, max dp 0.0001 | 81/81, max dp 0.0014 |
| Host lookup cost | 0 | +14.6 ms median |

So the recommended form is **half the storage and ~1.4x faster on CPU** for the same answers, at the cost
of a host-side embedding lookup. The token-id form's only advantage is skipping that lookup. This is a
package swap, not a re-conversion.

**And it ships the Kotlin host we were about to write.** `android/app/src/main/java/com/laya/` contains
`LayaTokenizer.kt`, `LayaPromptBuilder.kt`, `LayaEmbeddings.kt`, `LayaDecoder.kt`, `LayaCalibration.kt`
and `LayaEngine.kt` (~44 KB of host code, Apache-2.0), plus `fixtures/gate_rows_s256.json` — the 201
validation rows with reference answers, i.e. a ready-made parity gate. The tokenizer port, the prompt
builder, the marker-gathering decoder and the calibration loader are all already written and validated
by the publisher. Porting `laya_host.py` by hand is unnecessary work.

### FP16: two different things, and only one of them should change

Confusing these would be easy, so both are stated explicitly:

| | what it changes | verdict |
|---|---|---|
| **Compute precision** — `GpuOptions.Precision.FP16` | the arithmetic the delegate performs | **No.** Measured on the K40: forcing it on the fp32-weight graph makes both Laya outputs **NaN** — precisely the overflow the card documents (masks at -1e4, LayerNorm inputs whose squares reach 1e8). The card agrees: *"On the GPU, use explicit FP32 computation... plain FP16 changed one of 81 answers."* |
| **Weight storage** — the `wfp16` model files | 99 FULLY_CONNECTED weight tensors stored as float16 with a DEQUANTIZE to float32; **every activation stays float32** | **Yes — this is the card's recommended phone form.** 81/81 argmax, max dp 0.0014, and faster even on CPU (S26: 163 ms vs 222 ms for the fp32 form) because half the weight bytes are streamed. GPU compile drops from ~7 s to 1.3 s. |

So "use fp16" is right about the file and wrong about the precision setting. Switching to
`Laya-Multilingual-LiteRT` gets the weight-storage benefit while keeping compute in fp32, and it is also
the form the card validated on the GPU as a single partition (1809/1809 operators) — worth testing on the
Adreno 650, where our current 1.29 GB token-id fp32 form miscomputes.

### A defect in this investigation, recorded because it produced a wrong reading

One revision of the GPU comparison hoisted the input write out of its quiescence loop, so the settle
probe and the reported outputs ran on freshly allocated **zero** buffers. That produced two false
readings — "the GPU's output is all zeros" and "the CPU never settles" — which briefly looked like
delegate unreliability. It was the test, not the device. Inputs are now written inside the run itself,
which is also what the production path does. The first attempt at the same loop demanded bit-identical
reads and never settled even on the CPU, because XNNPACK's multi-threaded reductions combine partial
sums in a work-stealing order that varies run to run; the criterion is a tolerance now (1e-4), which the
CPU meets in 2 rounds and which an unwritten buffer still fails.

### The CPU/GPU verdict depends on what the device was doing first — and the GPU is the steadier one

The embedding graph was measured twice with the same code, in the same thermal bucket, and the CPU side
differed by **4.2x**:

| condition | embedding CPU | embedding GPU | winner |
|---|---:|---:|---|
| fresh process, this test alone | **191 ms** | 243 ms | CPU (0.79x) |
| after this class's other benchmarks | **799 ms** | 250 ms | **GPU (3.19x)** |

The second row is a valid same-state comparison - both sides were measured back to back and the test now
logs and requires that (`sameThermalState=true`). What differs between the rows is not the accelerators
but **what the CPU had been left in**: on this run the platform reported `thermalBefore=NONE
thermalAfter=NONE` at a 37 C battery, so this is **not** OS thermal throttling. It is state left by the
preceding tests in the same process, which is why the test now logs the platform's thermal bucket - to
rule that explanation in or out rather than assume it.

Two consequences, both of which contradict what this file said earlier:

1. **"The GPU is not the lever" only holds on an idle device.** When the CPU is busy - which is the normal
   condition for a phone running continuous proactive gating, local ASR and everything else - the CPU path
   degrades by 4x and the GPU path does not. For the product, that argues *for* the GPU on the embedding
   graph, not against it.
2. **The `Interpreter`-based CPU numbers (Laya 1815 ms, embedding 584 ms) were taken on a fresh process and
   are therefore cool-device figures**, as are the `CompiledModel` numbers in the Results table. Any
   figure from this file that is quoted for planning is a best case; the busy-device case is roughly 4x
   worse on the CPU and unmeasured on the GPU except for the one row above.

### A test-methodology constraint worth carrying forward

`LiteRT CompiledModel.create` on the Adreno 650 fails outright after roughly three GPU instantiations in
one process (`LiteRtException ... litert_compiled_model.cc:139`), reproducibly when this class runs end to
end, while identical code succeeds in a fresh process. The failure is at *create*, not *run*, so the driver
is not returning some resource. The class therefore creates exactly one GPU model per graph and nothing
else touches the GPU accelerator; the two GPU experiments that used to exist here were removed for that
reason and their conclusions are recorded in the test file as comments.

This is a constraint the product already satisfies by construction: spec §2.5 requires the model to be
loaded once and stay resident, with no pause/resume. It is only test code that was creating and destroying
models per measurement.

---

### What this means for the K70 Ultra

The card's GPU validation covers Adreno 8xx and its NPU validation covers Hexagon v81 through LiteRT's
Qualcomm plugin. **The K70 Ultra's Immortalis-G720 is neither**, and its Dimensity 9300+ NPU is a MediaTek
APU, which that plugin does not drive. So on the delivery target **only the CPU path is hardware-portable**
— which is exactly the path the estimate above extrapolates. GPU and NPU acceleration must be treated as
unvalidated on the K70 until measured there, and no published number transfers to it.

The immediate next step is therefore not to fight the Adreno 650: it is to switch to the recommended
package, take the publisher's Kotlin host, and run its 201-row gate on the K40. That converts A5 from
"graph cost" into real per-decision cost **with a correctness gate attached**, which is what Phase B
actually needs.

## Package switch to the publisher's recommended phone set (2026-10-13, later the same session)

Acting on the package-selection finding above, the project moved from `laya-LiteRT` (token-ids, table in
graph, 1.29 GB) to `Laya-Multilingual-LiteRT` (embeds, table on host, 679,274,525 bytes) and adopted the
publisher's Kotlin host instead of writing one.

**What was taken, verbatim, into `phone-app/src/main/kotlin/com/laya/`** (Apache-2.0, SPDX headers and the
publisher's LICENSE/NOTICE/README kept alongside under `com/laya/LICENSES/`): `LayaEngine.kt`,
`LayaTokenizer.kt`, `LayaPromptBuilder.kt`, `LayaEmbeddings.kt`, `LayaDecoder.kt`, `LayaCalibration.kt`,
`LayaJson.kt`. Their `LayaPromptBuilder` and `LayaDecoder` reproduce upstream's prompt template, marker
positions, option cap, right-truncation and a softmax that mirrors NumPy's float32 pairwise reduction
including its 8-accumulator order; the publisher validated that port on 402 rows, 300 tokenizer stress
strings and 118,554,624 embedding values at maximum absolute error 0. Hand-transcribing it would have
traded a verified implementation for an unverified one - and the tokenizer port this session had budgeted
"a session's worth of work" for turned out to be already written and verified.

**LiteRT 2.1.0 to 2.2.0**, which the vendored host requires: it calls
`Environment.create(Context, Map<Option, String>)`, an overload 2.1.0 does not have (verified by `javap`
against both AARs). 2.3.0 remains unusable because its `litert-api` carries Kotlin 2.4.0 metadata that this
project's Kotlin 2.2.20 cannot read.

**Build-side: `android.uniquePackageNames=false`.** On AGP 9.x, `litert` 2.2.0 and its dependency
`litert-api` 2.2.0 both declare the namespace `com.google.ai.edge.litert`, and the build stops at
`processDebugMainManifest`. The publisher's own LiteRT skill documents this exact case and names
`uniquePackageNames=false` as the fix for 2.2.0/2.1.6, warning that it should be removed on 2.3.0 because
it also silences the clash between any other pair of libraries. Recorded in `gradle.properties` with that
expiry condition. (The publisher's sample is on AGP 8.9.1, where the clash is only a warning, which is why
they never hit it.)

**Provisioning.** `LayaEngine` resolves its assets from the app's **internal** `filesDir`, not the external
one, and checks for them at construction. The publisher's installer stages into `/data/local/tmp` and then
`run-as <pkg> cp`s each file - necessary because `run-as`'s shell cannot read `/sdcard` (the FUSE layer
validates the calling package). Replicated with explicit per-file `cp` calls rather than a shell loop,
which silently dropped the loop variable. `filesDir` then held exactly the publisher's 679,274,525 bytes
across the seven files.

### Smoke test on the K40, CPU backend, Chinese input

`LayaHostSmokeTest` (instrumentation) constructs the host, compiles on CPU and asks one `noul` question
and one `choice` question - the shapes D60 gives Laya:

```
LayaSmoke: LOAD tokenizerMs=2972.919322 embeddingsMs=0.870417
LayaSmoke: COMPILE_CPU_MS=508.511458
LayaSmoke: NOUL_PROBABILITY=0.8708
LayaSmoke: CHOICE_ANSWER={type=choice, choice=intervene,
             probabilities={intervene=0.5608, silence=0.4392}, confidence=0.0107,
             action={act_probability=1.0}}
LayaSmoke: CHOICE_PROBABILITY_SUM=1.0
```

State used: `{"foreground":"com.tencent.mm","notification_category":"msg",
"recent_transcript":"这个优惠券怎么用啊","dwell_seconds":47}` - Chinese, because that is the deployment
language and the multilingual checkpoint is the reason this package was chosen.

Three things this establishes, and one it does not:

- **The pipeline works end to end on this device with Chinese input.** Probabilities sum to 1.0, the
  choice answer is one of the supplied criteria, and the numbers are plausible for the state (87 % likely
  the user needs help).
- **`act_probability` is exactly 1.0**, independently reproducing the publisher's warning that the act
  head "saturated at 1.0 ... and is not a usable escalation signal". Nothing may be built on it.
- **`confidence=0.0107`** on the choice question - the model reports near-total uncertainty between
  intervening and staying silent. Worth remembering before any threshold is chosen: on this state the gate
  is a coin flip, not a decision.
- **Not established:** parity. This is a smoke test, not the publisher's 201-row gate, which exists
  (`fixtures/gate_rows_s256.json`, reachable through the sample's `--ez gate true` entry point) and is the
  thing to run if this path is ever suspected. The publisher documents 81/81 argmax and max dp 0.0014 for
  this file set on a validated device; re-validating their conversion was not the deliverable.

**Costs to plan around, measured here:** the tokenizer takes **3.0 s** to parse at construction (the
publisher reports ~1.5 s on their device), the CPU graph compiles in **0.5 s**, and each question takes
about **0.6 s** including host preparation, the embedding lookup and decoding. Construction and
`initialize` must both stay off the UI thread.

**Also confirmed by the switch:** LiteRT 2.2.0 ships the GPU accelerator inside the `litert` artifact and
registers it on the K40 - `libLiteRtClGlAccelerator.so` loads and registers as "LiteRT GPU" - so no second
artifact is needed (matching the publisher's note). The NPU fails to register exactly as expected when its
Qualcomm libraries are absent. Given a working GPU accelerator on 2.2.0, **the Laya GPU miscompute
recorded earlier in this file was measured on 2.1.0 and should be re-tested on 2.2.0 before it is treated
as a property of the Adreno 650.**

## Retraction: the Laya GPU miscompute was a LiteRT 2.1.0 bug, not the Adreno 650

The note at the end of the package-switch section said the GPU miscompute "was measured on 2.1.0 and should
be re-tested on 2.2.0 before it is treated as a property of the Adreno 650". That re-test was run, and it
**overturns the finding**:

| LiteRT | Laya `token_logits`, GPU vs CPU | argmax | verdict |
|---|---:|---|---|
| 2.1.0 | max abs difference **3.31** on a scale of 4.57 | 243 vs 157 | GPU wrong |
| **2.2.0** | max abs difference **7.6293945e-6** | 243 vs 243 | **GPU correct** |

The same instrumentation, the same file, the same synthetic input, the same phone. On 2.2.0 the GPU's Laya
output agrees with the CPU's to the last few bits, and the same run measures the GPU at **230 ms against
the CPU's 2261 ms - 9.83x faster** (`sameThermalState=true`, both sides measured back to back in one
process).

So everything this file said about "the GPU is not the lever" for Laya, and the `COMPILED_LAYA_GPU_UNVERIFIED`
gate that refused to report its latency, should be read as **statements about LiteRT 2.1.0**. On 2.2.0 the
GPU is both correct and roughly ten times faster than the CPU for this graph in these conditions, which
makes it the backend to use for the proactive gate - and removes the reason the earlier sections treated
Laya's latency as a problem to design around.

The `COMPILED_LAYA_GPU_UNVERIFIED` path stays in the test: it is what caught this, and it is the reason the
wrong reading never became a quoted performance number. It simply no longer fires.

### The CPU side of this device drifts by about 4x between runs, and the cause is still unknown

This run also re-measured every CPU path, and all of them were roughly four times slower than the earlier
runs, while the GPU was unchanged:

| measurement | earlier run | this run | ratio |
|---|---:|---:|---:|
| `Interpreter` Laya | 1815 ms | **8707 ms** | 4.8x |
| `Interpreter` EmbeddingGemma | 584 ms | **2733 ms** | 4.7x |
| `CompiledModel` CPU Laya | 579 ms | **2261 ms** | 3.9x |
| `CompiledModel` CPU embedding | 191 ms | **736 ms** | 3.9x |
| `CompiledModel` GPU embedding | 243 ms | 250 ms | 1.03x |
| `CompiledModel` GPU Laya | 225 ms | 230 ms | 1.02x |

The platform reported `thermalBefore=NONE / thermalAfter=NONE` throughout, so this is not OS thermal
throttling, and the interpreter path slowing down as much as the `CompiledModel` path rules out anything
specific to how this test drives the API. What is left is CPU frequency/affinity policy on this MIUI build,
possibly interacting with charging state or with the 680 MB of new files this session pushed into internal
storage - none of which has been tested.

Two consequences, and they are the reason this is recorded rather than worked around:

1. **Absolute CPU numbers from this device are not reliable to better than ~4x.** The cool-device figures in
   the Results table remain the best available, but any figure quoted for planning should be treated as a
   best case, and the K70 estimate inherits that uncertainty.
2. **The CPU/GPU ratio measured inside a single run is trustworthy; a ratio across runs is not.** This is
   why the test logs each side's thermal bucket and reports `sameThermalState`.

## Measured on the delivery target: Redmi K70 Ultra (Dimensity 9300+, Android 16)

The K70 Ultra was attached at the end of this session, so the cross-device estimate above can be replaced
with a measurement. Same file set (`Laya-Multilingual-LiteRT`, s256 embeds wfp16), same publisher host,
same one-question `noul` call, timed with the host's own phase timers so the graph figure is directly
comparable with the publisher's published numbers.

**Graph-only, median of 5 after a warm-up, one device, CPU is 4 threads (the host's setting):**

| phase | CPU | GPU (OpenCL, explicit FP32) |
|---|---:|---:|
| prepare (tokenize + build prompt) | ~5 ms | ~8 ms |
| embedding lookup (256 rows from the 375 MiB table) | ~13 ms | ~30 ms |
| **main graph** | **~742 ms** | **~231 ms** |
| act graph | ~0.2 ms | ~4 ms |
| decode | ~0.2 ms | ~0.3 ms |
| **total per question** | **~760 ms** | **~270 ms** |

Three consecutive runs, to rule out the K40's drift: CPU graph 748.4 / 742.3 / 737.5 ms, GPU graph 221.5 /
231.4 / 247.5 ms. Stable to about 1 % on the CPU side and drifting upward slightly on the GPU side. (The
first, separate run of the same test read CPU 934.3 / GPU 213.1, so the CPU figure varies by roughly 26 %
between runs - real variance, but an order of magnitude less than the K40's 4x swing.)

**CPU and GPU agree exactly.** The `noul` probability was 0.8708 on both backends from the same state,
absolute difference 0.0 - not within a tolerance, identically. This is the same cross-check that caught
LiteRT 2.1.0's GPU miscompute, and on 2.2.0 across two different SoCs it passes exactly.

### Against the publisher's S26 figures

| | K70 Ultra (measured) | Galaxy S26 (published) | ratio |
|---|---:|---:|---:|
| CPU graph | 742 ms | 163 ms | **4.55x** |
| GPU graph | 231 ms | 51.9 ms | **4.45x** |
| GPU / CPU | **0.31** | **0.32** | - |

Both backends are about 4.5x slower than the S26, and the GPU/CPU ratio is essentially the same on both
devices. That makes the ratio look like a property of the model and the host rather than of the SoC: this
graph is worth roughly 3.2x on the CPU side of any device that runs it, and the absolute scale is what
varies.

### The estimate this replaces was wrong, and why

The cross-device section above predicted **~305 ms optimistic / ~510 ms pessimistic** for Laya on the
K70's CPU. Measured: **742 ms**, i.e. 1.5x worse than the pessimistic end.

The extrapolation had no valid basis, for two reasons worth recording so the method is not reused:

1. **It scaled from a different graph form.** The K40's 579 ms was `CompiledModel` running the *token-ids*
   fp32 graph (1.29 GB, embedding table inside the graph, positionally created buffers). The K70's 742 ms
   is the *embeds* wfp16 graph (251 MB + a host-side 375 MiB table, named signature buffers) driven by the
   publisher's host. Different weights precision, different graph, different buffer plumbing - there was
   never a like-for-like pair to scale.
2. **It assumed a clean fp32-SIMD scaling law** from published core counts and clocks. That model also
   failed to predict the K40 itself: it implied ~290 GFLOP/s from a chip peaking at 167, which is what
   exposed the thread-count explanation. It should not have been trusted for a second device.

What would have worked is the comparison used here: hold the model and host fixed and compare against a
published number for the *same* artifact on a known device. That is available (the publisher's S26 table)
and it is the anchor a future estimate should start from.

### What this means for the plan

- **Use the GPU backend for the proactive gate on the delivery target.** At ~231 ms of graph per question
  it fits the event-driven cadence D60 specifies with room to spare; the CPU's ~742 ms does not fit
  anything but background work.
- **Budget the cold start.** On the K70 the tokenizer parses in **3.3 s** and the GPU graph compiles in
  **3.4 s**; the publisher measures 1.3 s for that compile on an S26. The gate cannot be live until both
  are done, so ~7 s of one-time work has to be scheduled at startup rather than discovered on the first
  event.
- **The same numbers on the K40** (timer breakdown not captured there; graph-only `CompiledModel` readings
  were 579 ms CPU / 230 ms GPU on the older token-ids form): the K40 is the development device, not a
  target, and its CPU drifts by ~4x, so its absolute figures should not be used for planning.

### Is the 4.5x gap a real SoC difference, or a degradation on our side?

Worth asking before accepting a number that large, because a uniform ratio across two independent
execution units is what a *common* problem would look like. The K70's delegation was therefore checked
against what the publisher reported for the S26:

| | K70 Ultra (measured) | Galaxy S26 (published) |
|---|---|---|
| CPU delegation | `Replacing 1809 out of 1809 node(s) with delegate (TfLiteXNNPackDelegate) node, yielding 1 partitions` | not reported |
| GPU main | `Replacing 1809 out of 1809 node(s) with delegate (LITERT_CL) node, yielding 1 partitions` | "main 1809/1809 ops ... in one partition" |
| GPU act | `Replacing 4 out of 4 node(s) with delegate (LITERT_CL) node, yielding 1 partitions` | "act 4/4 ops in one partition" |

**Both K70 paths are fully delegated, in one partition each, on the same backends the publisher used.** So
the 4.5x is genuine throughput, not operators falling back to the CPU on the target device. The
alternative explanation is ruled out rather than assumed away.

What is *not* established is a mechanism. A 4.5x gap between a late-2023 flagship (Dimensity 9300+, Cortex
X4 / A720) and a 2026 one (Snapdragon 8 Elite Gen 5, Oryon) is high for two generations, and this file has
already produced one confident wrong prediction by extrapolating from a paper specification model - the
fp32-SIMD estimate that missed the K40 itself. No specification-level explanation is offered here for that
reason. What is measured is the ratio on this workload, which stresses fp32 vector throughput and memory
bandwidth; other workloads would show a different one.

**This no longer matters for planning.** The S26 figures were only ever an anchor for estimating the K70
before the K70 could be measured. It has now been measured, so the anchor is retired.

### One mechanism confirmed rather than inferred: the GPU runs asynchronously

The K40 investigation concluded from sample timings that the OpenCL delegate's `run()` blocks on
submission back-pressure rather than on completion, and that reading outputs right after it returns is
therefore unreliable. The K70's log names the setting:

```
delegate_opencl.cc:603] options->hint_waiting_for_completion: false
delegate_opencl.cc:605] options->kernel_batch_size: -1
```

`hint_waiting_for_completion: false` is the delegate's default and it applies to the publisher's S26 runs
too, so their 200-row medians are steady-state figures in the same way ours are - the value is that the
inference is now backed by a named option rather than by timing evidence alone.

## Where the remaining gap is on the K40

The 150 ms embedding gate is **not met on K40 even with `CompiledModel`** (196 ms at 4 threads). Levers, in
the order worth trying:

1. **NPU.** The card reports 37 ms for this file via the Hexagon NPU, and `CompiledModel` exposes
   `QualcommOptions` for it. This is the largest remaining lever and the only one with a published
   number near the gate. It needs the Qualcomm runtime libraries in the APK.
2. **A shorter window.** `seq256` is already the shortest export published; shorter would need
   re-conversion.
3. **The delivery target.** See the Dimensity 9300+ estimate above: the K70 Ultra is expected to clear
   the gate on the CPU alone (~103 ms), subject to the thread-count assumption, and per `D59` the gate is
   not a Phase A blocker either way.

The Laya reading also needs a product decision independent of speed: **569 ms per decision (CPU,
`CompiledModel`) is not usable between a user finishing a sentence and the assistant answering.** Where
it is tolerable — pre-computing a SKILL shortlist while the user is still speaking, or scoring memory
candidates in the background — it is a background cost. Phase B must not put a `choice()` call in the
user's critical path.

## Reproduction

```
adb -s 2696813b shell "mkdir -p /sdcard/Android/data/edith.phone/files/models"
adb -s 2696813b push .local/models/laya-multilingual /sdcard/Android/data/edith.phone/files/models/
adb -s 2696813b push .local/models/embeddinggemma  /sdcard/Android/data/edith.phone/files/models/
./gradlew :phone-app:assembleDebug :phone-app:assembleDebugAndroidTest
adb -s 2696813b install -r phone-app/build/outputs/apk/debug/phone-app-debug.apk
adb -s 2696813b install -r -t phone-app/build/outputs/apk/androidTest/debug/phone-app-debug-androidTest.apk
adb -s 2696813b logcat -c
adb -s 2696813b shell "am instrument -w -e class edith.phone.litert.LiteRTPerformanceTest edith.phone.test/edith.phone.PhoneTestRunner"
adb -s 2696813b logcat -d -s LiteRTPerf:I
```

The `edith.phone.test` install needs MIUI's USB-install confirmation; the project's established
on-device pattern (automated tap on `继续安装`) was used, which the owner authorized for install
confirmations.

## Build-side findings needed to get here

- **LiteRT drags in `androidx.work`, which crashes this app's minified debug build.**
  `com.google.ai.edge.litert:litert` → `com.google.android.play:asset-delivery` →
  `androidx.work:work-runtime:2.9.1`, whose `androidx.startup` initializer died with
  `Failed to create an instance of class androidx.work.impl.WorkDatabase` because R8 strips Room's
  generated impl. Both are excluded in `phone-app/build.gradle.kts`; the models ship on the device, not
  through Play Asset Delivery, so nothing of value is lost.
- **LiteRT's consumer ProGuard rules are incomplete.** The AAR's `proguard.txt` keeps only
  `@UsedByReflection` members, and neither `Interpreter` nor `CompiledModel` carries that annotation, so
  R8 removed what the measurement needed — twice, in two different shapes:
  `NoSuchMethodError: <init>(ByteBuffer, Interpreter$Options)` and
  `ClassNotFoundException: com.google.ai.edge.litert.Environment`. Both packages are now kept in
  `phone-app/proguard-rules.pro`.
- **The keep has to be `com.google.ai.edge.litert.*`, not `.**`.** The single `*` covers the package's
  own classes and their nested ones while stopping short of `…litert.deployment`, which is LiteRT's
  Play-Asset-Delivery *download* path. Keeping it made R8 report five missing
  `com.google.android.play.core.assetpacks` classes — the very dependency excluded above. Verified in the
  built APK's dex: `com/google/ai/edge/litert/Environment` and `.../CompiledModel` present,
  `.../litert/deployment` absent.
- **LiteRT 2.3.0 is not usable by this project.** It splits its API into `litert-api`, compiled with
  Kotlin 2.4.0 metadata, which this project's Kotlin 2.2.20 cannot read
  (`Module was compiled with an incompatible version of Kotlin`). 2.3.0 is also the only version that
  publishes `litert-gpu` — and it is not needed, because `litert:2.1.0` already ships `CompiledModel`
  and `libLiteRtOpenClAccelerator.so`. Pinned to 2.1.0.
- **`Select-Object -First N` on a Gradle build silently kills the build.** PowerShell stops reading the
  pipeline once it has N objects, which terminated Gradle mid-run; the APK kept its old timestamp and a
  stale proguard rule appeared not to apply. Use `-Last`, or redirect to a file.

## Evidence provenance

Raw logcat lines from the recorded runs (`LiteRTPerf` tag).

Run 1 — `Interpreter` API, CPU (the numbers in the `Interpreter` column):

```
MEMORY_RSS_BEFORE_MB=120 MEMORY_RSS_AFTER_MB=1252 RSS_DELTA_MB=1132
MEMORY_RSS_LIMIT_MB=1500
EMBED_LOAD_MS=270.126615
EMBED input[0] shape=[1, 256] dtype=INT32
EMBED output[0] shape=[1, 768] dtype=FLOAT32
EMBED_LATENCY_MS median=583.986354 p95=589.893906 min=582.783489 max=589.893906 outDim=768
EMBED_TRUNC256_NORM=0.64299405 (full-dim norm=0.9999998)
LAYA_LOAD_MS=271.7775
LAYA input[0] shape=[1, 256] dtype=INT32
LAYA input[1] shape=[1, 256] dtype=FLOAT32
LAYA input[2] shape=[1, 3] dtype=FLOAT32
LAYA output[0] shape=[1, 256] dtype=FLOAT32
LAYA output[1] shape=[1, 768] dtype=FLOAT32
LAYA_LATENCY_MS median=1814.425989 p95=2018.45302 min=1812.473437 max=2018.45302 window=256 pooled=768
LAYA_POOLED_SAMPLE=[-14.697373, 88.5275, 148.20538, -103.407005]
```

Run 2 — `CompiledModel` API, CPU vs GPU, 1 warm-up then 5 timed runs each:

```
COMPILED_CPU_EMBED_MS median=190.91099 samples=[190.91099, 196.574531, 192.596719, 189.172604, 189.574218]
COMPILED_CPU_LAYA_MS median=569.390468 samples=[569.735833, 569.390468, 569.012135, 568.837656, 570.172396]
COMPILED_GPU_EMBED_MS median=241.84724 samples=[1.386719, 0.800156, 244.049531, 241.84724, 242.901094]
COMPILED_GPU_LAYA_MS median=225.521146 samples=[0.406198, 0.367552, 228.301771, 225.581875, 225.521146]
CPU_PATH_INTERPRETER_LAYA_MS=1815 CPU_PATH_COMPILED_LAYA_MS=569.390468
COMPILED_EMBED_CPU_VS_GPU maxAbsDiff=0.01504005 cpuScale=0.18380152 agrees=true cpuArgmax=74 gpuArgmax=74
COMPILED_EMBED_GPU_VERIFIED cpuMs=190.91099 gpuMs=241.84724 speedup=0.79
COMPILED_LAYA_CPU_VS_GPU maxAbsDiff=3.313819 cpuScale=4.569049 agrees=false cpuArgmax=243 gpuArgmax=157
COMPILED_LAYA_GPU_UNVERIFIED: the GPU's output does not match the CPU's (maxAbsDiff=3.313819).
```

The two ~1 ms entries at the head of each GPU sample list are the asynchronous submissions described
above; they are kept in the log deliberately, as the evidence for why early GPU samples are discarded.

Run 3 — do the two CPU APIs compute the same thing? (the answer is yes, bit for bit):

```
API_AGREEMENT_EMBED_out0 maxAbsDiff=0.0 valueScale=0.18380152 relative=0.0 argmaxInterpreter=74 argmaxCompiled=74
API_AGREEMENT_LAYA_out0 maxAbsDiff=0.0 valueScale=4.569049 relative=0.0 argmaxInterpreter=243 argmaxCompiled=243
API_AGREEMENT_LAYA_out1 maxAbsDiff=0.0 valueScale=289.64212 relative=0.0 argmaxInterpreter=340 argmaxCompiled=340
```

Run 4 — thread scaling (median of 5 per cell), the measurement behind the Correction:

```
THREAD_SCALING threads=1 COMPILED_EMBED_MS=583.881563 COMPILED_LAYA_MS=1809.577291 INTERPRETER_LAYA_MS=1807.991145
THREAD_SCALING threads=2 COMPILED_EMBED_MS=331.481146 COMPILED_LAYA_MS=1015.196041 INTERPRETER_LAYA_MS=2018.261509
THREAD_SCALING threads=4 COMPILED_EMBED_MS=195.823698 COMPILED_LAYA_MS=578.625364  INTERPRETER_LAYA_MS=2009.391458
```

Model files (HuggingFace, pinned revisions):
[laya-LiteRT](https://huggingface.co/litert-community/laya-LiteRT) (`laya_ml_s256_fp32.tflite`,
`laya_ml_act_head_fp32.tflite`, `multilingual/`), Apache-2.0 / MIT per the card;
[embeddinggemma-300m](https://huggingface.co/litert-community/embeddinggemma-300m)
(`embeddinggemma-300M_seq256_mixed-precision.tflite`, `sentencepiece.model`), Gemma license —
downloaded with the owner's HuggingFace token after the owner accepted the license, and **not**
committed to the repository.
