# Dependencies supply their own consumer rules. Do not keep entire app/library packages.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Tink references these static-analysis annotations, not runtime dependencies.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Shizuku creates this non-manifest entry point through reflection.
-keep class com.navipilot.amap2tesla.privileged.PrivilegedDisplayService {
    public <init>();
}

# The WebRTC AAR has no consumer rules; its native library registers these JNI classes by name.
-keep class org.webrtc.** {
    *;
}
-keep class org.jni_zero.** {
    *;
}
-dontwarn org.jni_zero.JniZeroJni

# Preserve generated methods of this unusually large data class to avoid ART VerifyError.
# Its class/member names may still be obfuscated; JSON contracts use explicit keys.
-keep,allowobfuscation class com.jixiexiaoge.drivingassist.CarrotManFields {
    *;
}

# liblanenet.so 通过 JNI 名字查找 com.jixiexiaoge.drivingassist.vision.LaneNative 的 native 方法，
# 类名/方法名一旦被混淆，Java_..._nativeInit 就对不上了。
-keep class com.jixiexiaoge.drivingassist.vision.LaneNative {
    native <methods>;
}
