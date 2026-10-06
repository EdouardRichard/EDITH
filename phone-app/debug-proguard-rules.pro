# Instrumentation shares these app dependencies but is shrunk separately.
# Keep facade owners and members used only by AndroidX Test or test-support.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
