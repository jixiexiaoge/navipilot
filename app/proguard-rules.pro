# ===========================================
# ProGuard / R8 混淆规则
# 策略：第三方SDK全部保留，只混淆自己的核心业务代码
# ===========================================

# ---- 基础配置 ----
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-dontusemixedcaseclassnames
-verbose

# 降低优化级别，避免过度优化导致反射失败和 Kotlin 编译器内部错误
# 注意：不能使用 -dontoptimize，否则 -assumenosideeffects（日志移除）不生效
-optimizationpasses 1
-allowaccessmodification

# 优化选项 - 禁用可能导致 Kotlin 编译错误的优化
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# 混淆字典（增加反编译难度）
-obfuscationdictionary dictionary.txt
-classobfuscationdictionary dictionary.txt
-packageobfuscationdictionary dictionary.txt

# 保留崩溃堆栈可读性
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-printmapping build/outputs/mapping/release/mapping.txt

# 保留反射/注解/泛型签名所需属性
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ===========================================
# Android 框架必须保留
# ===========================================
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# 显式保留 Application 类（防止 R8 移除或重命名）
-keep class com.example.navipilot.CarrotApplication { *; }
-keep class com.example.navipilot.CarrotApplication$* { *; }
-keepclassmembers class com.example.navipilot.CarrotApplication { *; }

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
# Jetpack Compose（必须保留，混淆会导致UI崩溃）
# ===========================================
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Compose Runtime（lambda和内联函数）
-keepclassmembers class androidx.compose.runtime.** { *; }
-keep class androidx.compose.runtime.internal.** { *; }

# Compose UI（AndroidView互操作）
-keep class androidx.compose.ui.platform.** { *; }
-keep class androidx.compose.ui.viewinterop.** { *; }

# ===========================================
# Kotlin / Coroutines（混淆会导致协程崩溃）
# ===========================================
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ===========================================
# Koin 依赖注入（大量使用反射，必须保留）
# ===========================================
-keep class org.koin.** { *; }
-dontwarn org.koin.**
-keep class io.insert-koin.** { *; }
-dontwarn io.insert-koin.**
# Koin 通过反射创建实例，保留所有被注入类的构造函数
-keepclassmembers class com.example.navipilot.** {
    public <init>(...);
}

# ===========================================
# Timber 日志库
# ===========================================
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# ===========================================
# DataStore Preferences
# ===========================================
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ===========================================
# OkHttp + Gson（网络通信）
# ===========================================
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class sun.misc.Unsafe { *; }

# ===========================================
# ExoPlayer / Media3（视频播放）
# ===========================================
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ===========================================
# ML Kit（车道车辆检测，JNI）- 已移除以减小APK体积
# ===========================================
# -keep class com.google.mlkit.** { *; }
# -dontwarn com.google.mlkit.**

# ===========================================
# MapLibre / OSM 地图 SDK
# ===========================================
-keep class org.maplibre.** { *; }
-keep class com.mapbox.** { *; }
-dontwarn org.maplibre.**
-dontwarn com.mapbox.**

# ===========================================
# 腾讯导航SDK + 地图SDK + NTRIP SDK（全部保留）
# ===========================================
-keep class com.tencent.** { *; }
-keep interface com.tencent.** { *; }
-keep enum com.tencent.** { *; }
-keepclassmembers class com.tencent.** { *; }
-keepclasseswithmembers class com.tencent.** { *; }
-dontwarn com.tencent.**
-dontnote com.tencent.**

-keep class com.qq.taf.jce.** { *; }
-dontwarn com.qq.taf.jce.**

# 🚨 关键修复：强制保留腾讯导航 SDK 的所有 R 类及资源 ID
# 解决 NoSuchFieldError: navix_info_view_normal_bg 崩溃问题
-keep class com.tencent.navix.publish.R { *; }
-keep class com.tencent.navix.publish.R$* { *; }
-keepclassmembers class com.tencent.navix.publish.R$* {
    public static <fields>;
}
-keep class com.tencent.tencentmap.mapsdk.maps.R { *; }
-keep class com.tencent.tencentmap.mapsdk.maps.R$* { *; }
-keepclassmembers class com.tencent.tencentmap.mapsdk.maps.R$* {
    public static <fields>;
}

# 保留腾讯导航SDK自定义View的所有构造函数（防止XML布局inflate失败）
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep public class * extends android.view.ViewGroup {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 腾讯SDK的内部类和枚举（反射调用需要）
-keepattributes InnerClasses,EnclosingMethod
-keep class com.tencent.navix.api.model.SimulatorConfig { *; }
-keep class com.tencent.navix.api.model.SimulatorConfig$* { *; }
-keep class com.tencent.navix.api.model.NavGpsLocation { *; }
-keep class com.tencent.navix.api.model.NavDayNightMode { *; }
-keep class com.tencent.navix.api.config.RouteElementConfig { *; }
-keep class com.tencent.navix.api.config.RouteElementConfig$* { *; }
# 🔧 修复：移除 MapGestureListener keep 规则（已从代码中删除，不再使用）
# -keep class com.tencent.navix.api.layer.MapGestureListener { *; }
-keep class com.tencent.tencentmap.mapsdk.maps.model.LatLng { *; }
-keep class com.tencent.tencentmap.mapsdk.maps.CameraUpdate { *; }
-keep class com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory { *; }

# ===========================================
# Google Material（腾讯导航SDK布局依赖）
# ===========================================
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ===========================================
# OAID 相关（非OPPO设备抑制警告）
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
# 自己的代码：只保留必须保留的，其余全部混淆
# ===========================================

# 保留 Application 入口（已被上面 extends Application 覆盖，这里显式声明）
-keep class com.example.navipilot.CarrotApplication { *; }

# 保留 MainActivity（Activity 入口）
-keep class com.example.navipilot.MainActivity { *; }

# MainActivity 拆分类（大量 Compose / 注册 ActivityResult / 匿名内部类）
# Release + R8 全优化 + 重打包时若不保留，曾出现 VerifyError（构造与 switchToTencentMode 等校验失败）
-keep class com.example.navipilot.MainActivityCore { *; }
-keep class com.example.navipilot.MainActivityCore$* { *; }
-keep class com.example.navipilot.MainActivityLifecycle { *; }
-keep class com.example.navipilot.MainActivityLifecycle$* { *; }
-keep class com.example.navipilot.MainActivityUI { *; }
-keep class com.example.navipilot.MainActivityUI$* { *; }
-keep class com.example.navipilot.MainActivityUIComponents { *; }
-keep class com.example.navipilot.MainActivityUIComponents$* { *; }

# 保留 Service
-keep class com.example.navipilot.CarrotAmapForegroundService { *; }

# 保留 BroadcastReceiver
-keep class com.example.navipilot.XiaogeDataReceiver { *; }
-keep class com.example.navipilot.amapAutoStaticReceiver { *; }

# 保留数据模型类（Gson 序列化/反序列化需要字段名）
-keep class com.example.navipilot.CarrotManDataModels { *; }
-keepclassmembers class com.example.navipilot.CarrotManDataModels$* { *; }
-keep class com.example.navipilot.CarrotManFields { *; }
-keepclassmembers class com.example.navipilot.CarrotManFields { *; }
-keep class com.example.navipilot.CarrotManTencentSlice { *; }
-keepclassmembers class com.example.navipilot.CarrotManTencentSlice { *; }

# 保留 DI 模块定义（Koin module 引用类名）
-keep class com.example.navipilot.di.** { *; }

# 保留 data 层（DataStore 序列化）
-keep class com.example.navipilot.data.** { *; }

# 保留 WebRTC 相关自定义类（JNI 回调）
-keep class com.example.navipilot.webrtc.** { *; }

# 保留 TencentNavPage 相关（大量反射调用腾讯SDK）
-keep class com.example.navipilot.ui.components.TencentNavPage** { *; }
-keepclassmembers class com.example.navipilot.ui.components.TencentNavPage** { *; }
-keep class com.example.navipilot.ui.components.TencentNavWidgets** { *; }
-keep class com.example.navipilot.navigation.TencentNavDataBridge { *; }
-keepclassmembers class com.example.navipilot.navigation.TencentNavDataBridge { *; }
-keep class com.example.navipilot.navigation.TencentNtripProvider { *; }
-keep class com.example.navipilot.navigation.TencentRouteProvider { *; }

# 保留 TencentNavPage 中的所有 Composable 函数和 lambda
-keepclassmembers class com.example.navipilot.ui.components.TencentNavPageKt {
    *** TencentNavPage(...);
}

# 保留 TencentNavPage 中使用的 remember/LaunchedEffect 等 Compose API
-keep class kotlin.jvm.internal.Lambda { *; }
-keepclassmembers class * extends kotlin.jvm.internal.Lambda {
    public synthetic <methods>;
}

# 保留 OsmMapView（MapLibre 回调）
-keep class com.example.navipilot.ui.components.OsmMapView** { *; }

# 保留 WebRTCVideoView（WebRTC 回调）
-keep class com.example.navipilot.ui.components.WebRTCVideoView** { *; }

# 保留 Constants（可能被反射引用）
-keep class com.example.navipilot.Constants { *; }

# ============================================================
# 以下包/类会被正常混淆（核心业务逻辑）：
# - com.example.navipilot.core.*
# - com.example.navipilot.detection.*
# - com.example.navipilot.utils.*
# - com.example.navipilot.AutoOvertakeManager
# - com.example.navipilot.BatchedPreferences
# - com.example.navipilot.DataFieldManager
# - com.example.navipilot.DeviceManager
# - com.example.navipilot.LocationSensorManager
# - com.example.navipilot.NetworkManager
# - com.example.navipilot.CarrotManNetworkClient
# - com.example.navipilot.PermissionManager
# - com.example.navipilot.AmapBroadcastHandlers
# - com.example.navipilot.AmapBroadcastManager
# - com.example.navipilot.MainActivityCore
# - com.example.navipilot.MainActivityLifecycle
# - com.example.navipilot.MainActivityUI
# - com.example.navipilot.MainActivityUIComponents
# - com.example.navipilot.navigation.CoordinateConverter
# - com.example.navipilot.navigation.DualFreqGnssEngine
# - com.example.navipilot.navigation.GeoUtils
# - com.example.navipilot.navigation.GnssEnhancedProvider
# - com.example.navipilot.navigation.GpsKalmanFilter
# - com.example.navipilot.navigation.HybridRouteProvider
# - com.example.navipilot.navigation.LaneInfoCache
# - com.example.navipilot.navigation.LaneLevelNavigator
# - com.example.navipilot.navigation.NtripClient
# - com.example.navipilot.navigation.OsmDataMapper
# - com.example.navipilot.navigation.OsmNavigationManager
# - com.example.navipilot.navigation.OsrmRouteProvider
# - com.example.navipilot.navigation.OverpassClient
# - com.example.navipilot.navigation.RouteEngine
# - com.example.navipilot.navigation.RouteTracker
# - com.example.navipilot.navigation.RtcmDecoder
# - com.example.navipilot.ui.components.HelpPage
# - com.example.navipilot.ui.components.LaneComponents
# - com.example.navipilot.ui.components.LaneWarningOverlay
# - com.example.navipilot.ui.components.MapSearchService
# - com.example.navipilot.ui.components.NavigationIcons
# - com.example.navipilot.ui.components.ProfilePage
# - com.example.navipilot.ui.theme.*
# - com.example.navipilot.ui.utils.*
# ============================================================

# 移除 System.out 输出
-assumenosideeffects class java.io.PrintStream {
    public void println(%);
    public void println(**);
    public void print(%);
    public void print(**);
}

# 🆕 Release 构建移除 Debug/Verbose 级别日志（优化 #5：日志级别控制）
# 保留 Log.w / Log.e（警告和错误），移除 Log.d / Log.v / Log.i
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# 移除 printStackTrace
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# 字符串/资源适配
-adaptclassstrings
-adaptresourcefilenames
-adaptresourcefilecontents

# 抑制所有警告（第三方SDK可能有缺失引用）
-dontwarn **
-ignorewarnings
-dontnote

# ============================================================
# 高德地图 SDK（AMap3DMap_11.1.200_AMapNavi_11.1.200_AMapSearch_9.7.4_AMapLocation_11.1.200_20260421.jar）
# 警告：R8 优化资源压缩时遇到 final R class ids 会导致反射失效和资源丢失
# 解决：禁用该 JAR 的 optimized resource shrinking，保留所有字段和方法
# ============================================================
-dontwarn com.amap.api.navi.R$**
-dontwarn com.amap.api.map3d.R$**
-dontwarn com.amap.api.location.R$**
-dontwarn com.amap.api.search.R$**
-keep class com.amap.api.navi.R$** { *; }
-keep class com.amap.api.map3d.R$** { *; }
-keep class com.amap.api.location.R$** { *; }
-keep class com.amap.api.search.R$** { *; }
-keep class com.amap.api.navi.R$string { *; }
-keep class com.amap.api.navi.R$anim { *; }
-keep class com.amap.api.navi.R$attr { *; }
-keep class com.amap.api.map3d.R$attr { *; }
-keep class com.amap.api.location.R$string { *; }

# ============================================================
# Google Places API（防止 R8 StackOverflowError）
# ============================================================
-keep class com.google.android.libraries.places.** { *; }
-dontwarn com.google.android.libraries.places.**

# ============================================================
# 反射保护（防止反射调用失败）
# ===========================================================
# 保留所有通过 Class.forName 加载的类
-keepnames class * {
    *;
}

# 保留所有通过反射调用的方法
-keepclassmembers class * {
    public <methods>;
    protected <methods>;
}

# 保留所有枚举类型（反射经常用到）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
    public *;
}

# 保留所有 Builder 模式类（腾讯SDK大量使用）
-keep class **$Builder {
    public <methods>;
    public <fields>;
}

# 保留所有内部类和嵌套类
-keepattributes InnerClasses,EnclosingMethod
-keep class **$* { *; }
