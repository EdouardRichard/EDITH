# Instrumentation shares these app dependencies but is shrunk separately.
# Keep facade owners and members used only by AndroidX Test or test-support.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
# Instrumentation invokes the Compose compiler facade from the minified app ABI.
-keep class androidx.compose.runtime.SnapshotStateKt { *; }

# Instrumentation-only Compose ABI. `AndroidComposeUiTestEnvironment` installs an
# `InfiniteAnimationPolicy` implementation in the test coroutine context, and the Kotlin compiler
# routes the interface's default method through `InfiniteAnimationPolicy$DefaultImpls`. Production
# never calls that bridge, so R8 strips it from the debug app dex; every Compose test then dies
# before its body with
# `NoClassDefFoundError: Failed resolution of: Landroidx/compose/ui/platform/InfiniteAnimationPolicy$DefaultImpls;`.
# Keep the policy interface, its default bridge and the frame helper (all present in ui-android
# 1.7.3) so the instrumented APK resolves them from the minified app ABI.
-keep class androidx.compose.ui.platform.InfiniteAnimationPolicy { *; }
-keep class androidx.compose.ui.platform.InfiniteAnimationPolicy$DefaultImpls { *; }
-keep class androidx.compose.ui.platform.InfiniteAnimationPolicyKt { *; }

# Compose test runtime ABI (round 2). The instrumentation `ComposeRootRegistry` calls
# `ViewRootForTest.Companion.setOnViewCreatedCallback`, a member production never touches, so R8
# kept the class and the getter but stripped the setter. Every Compose test then died before its
# body with
# `NoSuchMethodError: No virtual method setOnViewCreatedCallback(Lkotlin/jvm/functions/Function1;)V
# in class Landroidx/compose/ui/platform/ViewRootForTest$Companion;`.
# Keep the whole `androidx.compose.ui.platform` package and its members in debug instead of naming
# one member at a time: compose-ui-test reaches directly into this package's internal ABI, so the
# next stripped member would fail the same way. Debug-only, like the rules above.
-keep class androidx.compose.ui.platform.** { *; }

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
