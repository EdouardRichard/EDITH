# Laya model declaration (Laya-Multilingual-LiteRT)

Status: declaration of the source and license evidence for the six Laya runtime files
bundled by the 0.4 build. This is **not** a legal conclusion and **not** a completed
distribution audit. The project's `distributionReview` stays **Incomplete** (see the
`runtime.distributionReview` field in `tools/scripts/p2/engine-manifest.json` and
`tools/scripts/p2/NOTICE.md`).

## Identity and source

- Artifact group: `laya` in `tools/scripts/p2/model-bundles.json` (6 runtime files;
  `tokenizer_config.json` is present in the staging directory but is not read by the
  runtime and is not packaged).
- Publisher: `litert-community/Laya-Multilingual-LiteRT`
  (<https://huggingface.co/litert-community/Laya-Multilingual-LiteRT>), a LiteRT
  conversion hosted on Hugging Face.
- Upstream base model: `convaiinnovations/laya`
  (<https://huggingface.co/convaiinnovations/laya>), Apache-2.0.
- Publisher model-card metadata observed on 2026-10-26: `license: apache-2.0`,
  `base_model: convaiinnovations/laya`, card created 2026-09-21, last modified
  2026-10-05.
- Checkpoint revision recorded by the publisher's retained NOTICE:
  `1c5edc17a7acd8701df6fc341c0d179f1c62c982` (the publisher's quality section also
  refers to it as `1c5edc17`). That revision is the public conversion's checkpoint
  reference. The delivered local set was produced by this project's fine-tune line
  from `E:\layaft\artifacts\laya-ml-embeds-bnd-seed20261014`; therefore the public
  revision is evidence about the upstream checkpoint lineage, **not** proof that the
  bytes below are identical to the public repository files. The bytes/SHA-256 below
  are the pinned identity of what is delivered.
- Retained NOTICE text: `phone-app/app/src/main/kotlin/com/laya/LICENSES/laya-sample-NOTICE.txt`.
  It is copied into the APK as `assets/models/licenses/laya-sample-NOTICE.txt` by the
  app's `BundleModelAssets` task. It records the Apache-2.0 sample derivation, the
  checkpoint revision above, the mmBERT-base (MIT) encoder attribution, and the
  ModernBERT/MosaicBERT notice.

## License

- The publisher declares **Apache-2.0** for the LiteRT conversion.
- The vendored host code under `phone-app/app/src/main/kotlin/com/laya/` is the
  publisher's Android sample, Apache-2.0; its NOTICE is retained as described above.
- The full Apache-2.0 text ships as `assets/models/licenses/Apache-2.0-standard.txt`.
- The multilingual encoder derives from `jhu-clsp/mmBERT-base` (MIT declared by its
  model card; attribution retained in the Laya NOTICE). This declaration does not
  re-audit that dependency chain, and the calibration datasets are documented
  upstream but are not bundled.

## Delivered runtime files (pinned by `model-bundles.json`)

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `laya_ml_act_head_fp32.tflite` | 795,816 | `30da532a8b752fdc82351740b0194d440a13d342ed77b9f8848978f1a96cf16d` |
| `laya_ml_calibration.json` | 1,108 | `2e7b10ce9a882b814d39827c40e3f1b3bfa6e7018c857e3b3f20e045ecfa09be` |
| `laya_ml_s256_embeds_wfp16.tflite` | 250,889,040 | `f19f70f6309264f93d337ca68d385a3b9673238e1e6e083e4d6c339dee8f1771` |
| `token_embeddings.json` | 844 | `afcda57527406bcab8cccab61ffd3530b88394d6485b625cfa870dc1c4f89e7b` |
| `token_embeddings_fp16.bin` | 393,216,000 | `339c9ebf138861bb367bd8783c4db9de44e6e18a602699e4069ba244ca59822b` |
| `tokenizer.json` | 34,363,188 | `609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f` |

Total: 679,265,996 bytes (the 7-file staging set also contains a 524-byte
`tokenizer_config.json` that is intentionally not part of the bundle).

The build fails if any of these files is missing or does not match the pinned
bytes/SHA-256. Inclusion of the files in this table is a declaration of provenance
evidence, not a claim that redistribution rights have been fully audited.
