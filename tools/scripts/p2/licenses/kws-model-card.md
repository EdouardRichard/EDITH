# sherpa-onnx KWS Zipformer zh-en 3M model declaration

Status: declaration of the source and license evidence for the wake-word (KWS) files
bundled by the 0.4 build. This is **not** a legal conclusion and **not** a completed
distribution audit. The project's `distributionReview` stays **Incomplete**.

## Identity and source

- Artifact group: `kws` in `tools/scripts/p2/model-bundles.json` — five runtime files
  from the upstream model archive plus the project-authored `keywords.txt`.
- Upstream release: `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` (Chinese &
  English), from the k2-fsa sherpa-onnx project:
  <https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2>
- Release evidence checked on 2026-10-26: the download URL answered HTTP 200 with
  `Content-Length: 32885699`, `Last-Modified: Mon, 22 Dec 2025 11:09:52 GMT`, and the
  file name from the archive disposition. The upstream
  <https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html> page lists
  the same archive and the same file set.
- `keywords.txt` is not part of the upstream archive. It is project-authored and
  tracked at `phone-app/perception/audio-android/kws/keywords.txt`; its exact bytes
  are pinned in `model-bundles.json`.
- Local source directory: `.local/p3-kws-model/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/`
  (repo-outside build input, like the other model groups).
- APK layout is unchanged from earlier releases: the five runtime files and
  `keywords.txt` are packaged as `assets/kws/` by
  `:phone-app:perception:audio-android:generateKwsModelAssets`, which keeps the
  hard-coded SHA checks and LF check for `keywords.txt`. Complete builds now fail
  when the KWS set is missing or incomplete.

## License

- The upstream sherpa-onnx project is **Apache-2.0**, and the KWS model set is
  declared Apache-2.0 for this bundle. No separate `LICENSE` file was present in the
  downloaded model directory, so this declaration relies on the upstream project's
  license and the project record (spec §6.5); it does not assert a completed
  redistribution audit.
- The full Apache-2.0 text ships as `assets/models/licenses/Apache-2.0-standard.txt`.
- The runtime (sherpa-onnx 1.13.8) attribution and its own license text remain in
  `tools/scripts/p2/licenses/` and are packaged by the existing trusted-manifest
  assets; this card does not change that path.

## Delivered runtime files (pinned by `model-bundles.json`)

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `decoder-epoch-13-avg-2-chunk-8-left-64.onnx` | 759,829 | `63a22dd60f40fff082ac3e09afa507f6787da36df76ded2fbe145fa233e22c21` |
| `en.phone` | 3,330,061 | `f7000ec3a90544c0c7c16090d8951779c2b322e14dad5006290f498567d439ea` |
| `encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx` | 4,600,657 | `2ca84d6bfe73e1ea3c9c49f600f7cad1c9ddd423c53c906b8bfe802444dd78d5` |
| `joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx` | 86,629 | `190d4067b4cc20b72a42a1916e69d92052000fb7051a427ebb1bc72a69207dc1` |
| `tokens.txt` | 1,928 | `2d3f32311f9b692b964da3c90e830258d3e78e013cb0c992dbfb15cd5a1a71b0` |
| `keywords.txt` (project-authored) | 46 | `4f74ca4656092de75ee4e7fb1f43ccd570ef7f4eb90e7272e040f2b3adec1fbb` |

Only the chunk-8/left-64 files above are packaged from the archive; the other
chunk-16 and non-int8 variants in the download are not part of the bundle.
