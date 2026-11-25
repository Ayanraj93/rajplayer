# Keep RajPlayer classes & JNI entry points safe
-keep class com.raj.rajplayer.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep Media3 reflection-loaded renderers and streaming factories
-keep class androidx.media3.decoder.av1.** { *; }
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.exoplayer.dash.** { *; }
-keep class androidx.media3.exoplayer.rtsp.** { *; }

# Suppress compile-only annotation warnings during R8 shrinking
-dontwarn androidx.media3.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**
-dontwarn com.google.j2objc.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Keep NewPipeExtractor & OkHttp models
-keep class org.schabi.newpipe.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.schabi.newpipe.**

# Keep Rhino JS engine used by NewPipeExtractor for YouTube stream decryption
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.**
-dontwarn javax.script.**
-dontwarn javax.swing.**
-dontwarn java.awt.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
