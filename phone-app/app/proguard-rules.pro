# Keep the shared contracts and constructor injection stable for separate instrumentation APKs.
-keep class !com.sheen.rokid.model.DevelopmentProfileLoader**,!com.sheen.rokid.model.DevelopmentConfiguration**,com.sheen.rokid.** { *; }
-checkdiscard class com.sheen.rokid.model.DevelopmentProfileLoader**
-checkdiscard class com.sheen.rokid.model.DevelopmentConfiguration**

# The CXR native bridges (libcxr-sock-proto-jni.so from cxr-service-bridge, which ships no proguard.txt)
# look their Java peers up by name through JNI. The vendor client-l proguard.txt keeps only a handful of
# members, so R8 stripped the rest and session creation died in JNI_OnLoad:
#   NoSuchMethodError: no non-static method "Lcom/rokid/cxr/Caps;.at(I)Lcom/rokid/cxr/Caps$Value;"
#   ClassNotFoundException: Didn't find class "com.rokid.cxr.CXRSocketProtocol"
# Both are then followed by a fatal JNI abort. Keep the reflective bridge surface in the minified build.
#
# Breadth measured at checkpoint34 (see docs/project/evidence/2026-10-07-p2-c34-review-fixes.md):
# com.rokid.* is 163 class files / ~372 KB uncompressed across both AARs. Commented-out only for the
# with/without measurement; restore before any device build.
-keep class com.rokid.cxr.** { *; }
-keep class com.rokid.sprite.aiapp.externalapp.** { *; }

# edith-0.2 Phase A: the LiteRT inference surface (Laya and EmbeddingGemma run in this process).
#
# The AAR ships consumer rules, but they keep only members annotated @UsedByReflection, and neither the
# `Interpreter` API nor the `CompiledModel` API is annotated that way. R8 therefore strips whatever the
# app's own code does not happen to call, which broke the on-device performance measurement twice:
#   NoSuchMethodError: no direct method <init>(Ljava/nio/ByteBuffer;Lorg/tensorflow/lite/Interpreter$Options;)V
#   ClassNotFoundException: Didn't find class "com.google.ai.edge.litert.Environment"
# and would equally break the product the first time the native side or a new call site needs a member R8
# had already removed. Measured cost (checkpoint edith-0.2-phase-a-exec-20261013-01): the Java surface is
# ~90 class files of a 151 KB classes.jar; the AAR's bulk (4.9 MB libLiteRt.so + 2.6 MB
# libLiteRtOpenClAccelerator.so per ABI) is native code R8 never touches.
# Same shape of problem as the com.rokid.cxr.** keep above, same remedy.
-keep class org.tensorflow.lite.** { *; }

# One wildcard, not `**`: the single `*` covers the package's own classes *and* their nested ones
# (`CompiledModel$GpuOptions` has no further dot, so `*` matches it), while stopping short of the
# `com.google.ai.edge.litert.deployment` subpackage. That subpackage is LiteRT's Play-Asset-Delivery model
# *download* path, which this app excludes and never calls: keeping it made R8 report five missing
# `com.google.android.play.core.assetpacks` classes - the very dependency deliberately excluded in
# build.gradle.kts because the models ship on the device. The inference classes R8 was wrongly stripping
# are all in the parent package, so nothing needed is lost.
-keep class com.google.ai.edge.litert.* { *; }
