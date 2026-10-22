-keep class com.sheen.rokid.phone.** { *; }
# AndroidX test references these compile-time annotations; neither supplies runtime behavior.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
