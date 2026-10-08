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
