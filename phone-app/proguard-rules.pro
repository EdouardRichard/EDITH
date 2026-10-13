# Keep the shared contracts and constructor injection stable for separate instrumentation APKs.
-keep class !edith.model.DevelopmentProfileLoader**,!edith.model.DevelopmentConfiguration**,edith.** { *; }
-checkdiscard class edith.model.DevelopmentProfileLoader**
-checkdiscard class edith.model.DevelopmentConfiguration**

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
# The AAR ships consumer rules, but they keep only members annotated @UsedByReflection, and the
# `Interpreter` API is not annotated. R8 therefore strips every Interpreter member the app's own code
# does not happen to call - which broke the on-device performance measurement with
#   NoSuchMethodError: no direct method <init>(Ljava/nio/ByteBuffer;Lorg/tensorflow/lite/Interpreter$Options;)V
# and would equally break the product the first time the native side or a new call site needs a member
# R8 had already removed. Measured cost of the whole keep (checkpoint edith-0.2-phase-a-exec-20261013-01):
# org/tensorflow/lite is 29 class files / 151 KB of classes.jar; the AAR's bulk is the jni/ native libraries,
# which R8 never touches. Same shape of problem as the com.rokid.cxr.** keep above, same remedy.
-keep class org.tensorflow.lite.** { *; }
