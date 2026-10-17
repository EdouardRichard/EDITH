# Public Engine Candidate Attribution

Candidate runtime: sherpa-onnx1.13.8, SHA256633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96. Full source licenses and model declarations are retained in licenses/. No models or credentials are bundled in the App.

The four embedded ONNX Runtime binaries contain VERS_1.28.2; exact per-ABI hashes are in runtime-binary-evidence.json. Include the v1.28.2 MIT license and complete upstream ThirdPartyNotices. This binary inspection establishes version evidence, not a complete build SBOM.

The pinned sherpa source commit11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf references kaldi-native-fbank1.22.3. That tag resolves to b09e686fe2084732ddd30d1ef80acfc0f13eaf01 and its cmake/kissfft.cmake pins febd4caeed32e33ad8b2e0bb5ea77542c40f18ec. Retain full kaldi-native-fbank Apache2 license plus KissFFT COPYING/BSD3/Unlicense texts from those commits. These are source-derived dependency notices; exact inclusion/linkage in the prebuilt sherpa binary remains unverified.

ASR weights declare Apache2 in their pinned model card; Silero v5 declares MIT with its full source license; speaker weights declare Apache2 in the captured model card. Preserve cards, citations and retained exporter attribution. The speaker's mutable release is pinned by exact digest and asset identity; historical exporter/checkpoint linkage is unverified.

Distribution review remains Incomplete: the prebuilt AAR carries no own LICENSE/NOTICE entries. Source dependency records and full captured texts must be included in the candidate package, but additional statically linked native components still require audit. Do not infer production engine selection, actual model permissions, runtime compatibility or performance from this notice bundle.
