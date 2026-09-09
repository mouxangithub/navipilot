# ===========================================
# ProGuard / R8 混淆规则（精简版）
# 策略：保留 Android 框架、Jetpack Compose、Kotlin 协程、第三方 SDK；
#       自身业务代码允许混淆，但入口类、数据模型、反射使用处显式保留。
# ===========================================

# ---- 基础配置 ----
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-dontusemixedcaseclassnames
-verbose

-optimizationpasses 1
-allowaccessmodification
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# 保留崩溃堆栈可读性
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-printmapping build/outputs/mapping/release/mapping.txt
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ===========================================
# Android 框架必须保留
# ===========================================
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ===========================================
# 应用入口与核心类（实际包名）
# ===========================================
-keep class com.mouxan.drivingassist.CarrotApplication { *; }
-keep class com.mouxan.drivingassist.MainActivity { *; }
-keep class com.mouxan.drivingassist.SplashActivity { *; }
-keep class com.mouxan.drivingassist.ScreenMirrorActivity { *; }
-keep class com.mouxan.drivingassist.CarrotAmapForegroundService { *; }
-keep class com.mouxan.drivingassist.AmapAutoStaticReceiver { *; }
-keep class com.mouxan.drivingassist.AppUpdater { *; }

# Compose 导航/路由如果用到反射或序列化，保留相关类名
-keepnames class com.mouxan.drivingassist.** { *; }

# ===========================================
# Jetpack Compose
# ===========================================
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ===========================================
# Kotlin / Coroutines
# ===========================================
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ===========================================
# Timber
# ===========================================
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# ===========================================
# OkHttp + Gson
# ===========================================
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class sun.misc.Unsafe { *; }

# ===========================================
# libVLC
# ===========================================
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# ===========================================
# Google Material / Places
# ===========================================
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
-keep class com.google.android.libraries.places.** { *; }
-dontwarn com.google.android.libraries.places.**

# ===========================================
# OAID 相关（抑制警告）
# ===========================================
-dontwarn com.heytap.openid.**
-dontwarn com.asus.msa.**
-dontwarn com.huawei.hms.ads.identifier.**
-dontwarn com.samsung.android.deviceidservice.**
-dontwarn com.bun.miitmdid.**
-dontwarn dalvik.system.**
-dontwarn sun.misc.**
-dontwarn java.lang.invoke.StringConcatFactory

# ===========================================
# 移除日志与无用方法（release 有效）
# ===========================================
-assumenosideeffects class java.io.PrintStream {
    public void println(%);
    public void println(**);
    public void print(%);
    public void print(**);
}

-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

-adaptclassstrings
-adaptresourcefilenames
-adaptresourcefilecontents

# 抑制第三方 SDK 缺失引用警告
-dontwarn **
-ignorewarnings
-dontnote
