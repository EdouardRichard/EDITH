# Keep the shared contracts and constructor injection stable for separate instrumentation APKs.
-keep class !edith.model.DevelopmentProfileLoader**,!edith.model.DevelopmentConfiguration**,edith.** { *; }
-checkdiscard class edith.model.DevelopmentProfileLoader**
-checkdiscard class edith.model.DevelopmentConfiguration**
