# Instrumentation shares these app dependencies but is shrunk separately.
# Keep facade owners and members used only by AndroidX Test or test-support.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
# Instrumentation invokes the Compose compiler facade from the minified app ABI.
-keep class androidx.compose.runtime.SnapshotStateKt { *; }

# edith-0.2: the publisher's Laya host (vendored under com.laya) and our wrapper over it are exercised by
# instrumentation and, so far, by nothing in production — the proactive gate that calls them lands in
# Phase B. Without these rules R8 strips both and the smoke test fails with
# `ClassNotFoundException: edith.phone.laya.LayaOfficialHost`, which reads like a missing dependency but is
# just dead-code elimination being right about the current call graph.
#
# These live in the debug rules rather than proguard-rules.pro on purpose: they should disappear when
# Phase B gives the host a production caller, and keeping them debug-only makes that removal automatic
# rather than something to remember.
-keep class com.sheen.rokid.phone.laya.** { *; }
-keep class com.laya.** { *; }
