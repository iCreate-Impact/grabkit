# Chaquopy: Python calls back into these Java classes by reflection.
-keep class com.chaquo.python.** { *; }
-keep interface com.grabkit.extractor.YtProgress { *; }
-keep class com.grabkit.extractor.YtProgress { *; }

# ffmpeg-kit JNI (the community fork keeps the com.arthenica.ffmpegkit package).
-keep class com.arthenica.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

-dontwarn org.brotli.dec.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
