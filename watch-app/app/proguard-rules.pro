# Add project-specific ProGuard rules here.
# Keep OkHttp WebSocket classes
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okio.**
