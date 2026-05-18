# ===========================================
# ProGuard / R8 混淆规则
# 策略：第三方SDK全部保留，只混淆自己的核心业务代码
# ===========================================

# ---- 基础配置 ----
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-dontusemixedcaseclassnames
-verbose

# 降低优化级别，避免过度优化导致反射失败
# 注意：不能使用 -dontoptimize，否则 -assumenosideeffects（日志移除）不生效
-optimizationpasses 1
-allowaccessmodification

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
-keepclassmembers class com.example.carrotamap.** {
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
# ML Kit（车道车辆检测，JNI）
# ===========================================
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

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

# 腾讯SDK的内部类和枚举（反射调用需要）
-keepattributes InnerClasses,EnclosingMethod
-keep class com.tencent.navix.api.model.SimulatorConfig { *; }
-keep class com.tencent.navix.api.model.SimulatorConfig$* { *; }
-keep class com.tencent.navix.api.model.NavGpsLocation { *; }
-keep class com.tencent.navix.api.model.NavDayNightMode { *; }
-keep class com.tencent.navix.api.config.RouteElementConfig { *; }
-keep class com.tencent.navix.api.config.RouteElementConfig$* { *; }
-keep class com.tencent.navix.api.layer.MapGestureListener { *; }
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
-keep class com.example.carrotamap.CarrotApplication { *; }

# 保留 MainActivity（Activity 入口）
-keep class com.example.carrotamap.MainActivity { *; }

# MainActivity 拆分类（大量 Compose / 注册 ActivityResult / 匿名内部类）
# Release + R8 全优化 + 重打包时若不保留，曾出现 VerifyError（构造与 switchToTencentMode 等校验失败）
-keep class com.example.carrotamap.MainActivityCore { *; }
-keep class com.example.carrotamap.MainActivityCore$* { *; }
-keep class com.example.carrotamap.MainActivityLifecycle { *; }
-keep class com.example.carrotamap.MainActivityLifecycle$* { *; }
-keep class com.example.carrotamap.MainActivityUI { *; }
-keep class com.example.carrotamap.MainActivityUI$* { *; }
-keep class com.example.carrotamap.MainActivityUIComponents { *; }
-keep class com.example.carrotamap.MainActivityUIComponents$* { *; }

# 保留 Service
-keep class com.example.carrotamap.CarrotAmapForegroundService { *; }

# 保留 BroadcastReceiver
-keep class com.example.carrotamap.XiaogeDataReceiver { *; }

# 保留数据模型类（Gson 序列化/反序列化需要字段名）
-keep class com.example.carrotamap.CarrotManDataModels { *; }
-keepclassmembers class com.example.carrotamap.CarrotManDataModels$* { *; }
-keep class com.example.carrotamap.CarrotManFields { *; }
-keepclassmembers class com.example.carrotamap.CarrotManFields { *; }
-keep class com.example.carrotamap.CarrotManTencentSlice { *; }
-keepclassmembers class com.example.carrotamap.CarrotManTencentSlice { *; }

# 保留 DI 模块定义（Koin module 引用类名）
-keep class com.example.carrotamap.di.** { *; }

# 保留 data 层（DataStore 序列化）
-keep class com.example.carrotamap.data.** { *; }

# 保留 WebRTC 相关自定义类（JNI 回调）
-keep class com.example.carrotamap.webrtc.** { *; }

# 保留 TencentNavPage 相关（大量反射调用腾讯SDK）
-keep class com.example.carrotamap.ui.components.TencentNavPage** { *; }
-keepclassmembers class com.example.carrotamap.ui.components.TencentNavPage** { *; }
-keep class com.example.carrotamap.ui.components.TencentNavWidgets** { *; }
-keep class com.example.carrotamap.navigation.TencentNavDataBridge { *; }
-keepclassmembers class com.example.carrotamap.navigation.TencentNavDataBridge { *; }
-keep class com.example.carrotamap.navigation.TencentNtripProvider { *; }
-keep class com.example.carrotamap.navigation.TencentRouteProvider { *; }

# 保留 TencentNavPage 中的所有 Composable 函数和 lambda
-keepclassmembers class com.example.carrotamap.ui.components.TencentNavPageKt {
    *** TencentNavPage(...);
    *** TencentComma3StatusBadge(...);
}

# 保留 TencentNavPage 中使用的 remember/LaunchedEffect 等 Compose API
-keep class kotlin.jvm.internal.Lambda { *; }
-keepclassmembers class * extends kotlin.jvm.internal.Lambda {
    public synthetic <methods>;
}

# 保留 OsmMapView（MapLibre 回调）
-keep class com.example.carrotamap.ui.components.OsmMapView** { *; }

# 保留 WebRTCVideoView（WebRTC 回调）
-keep class com.example.carrotamap.ui.components.WebRTCVideoView** { *; }

# 保留 Constants（可能被反射引用）
-keep class com.example.carrotamap.Constants { *; }

# ============================================================
# 以下包/类会被正常混淆（核心业务逻辑）：
# - com.example.carrotamap.core.*
# - com.example.carrotamap.detection.*
# - com.example.carrotamap.utils.*
# - com.example.carrotamap.AutoOvertakeManager
# - com.example.carrotamap.BatchedPreferences
# - com.example.carrotamap.DataFieldManager
# - com.example.carrotamap.DeviceManager
# - com.example.carrotamap.LocationSensorManager
# - com.example.carrotamap.NetworkManager
# - com.example.carrotamap.CarrotManNetworkClient
# - com.example.carrotamap.PermissionManager
# - com.example.carrotamap.AmapBroadcastHandlers
# - com.example.carrotamap.AmapBroadcastManager
# - com.example.carrotamap.MainActivityCore
# - com.example.carrotamap.MainActivityLifecycle
# - com.example.carrotamap.MainActivityUI
# - com.example.carrotamap.MainActivityUIComponents
# - com.example.carrotamap.navigation.CoordinateConverter
# - com.example.carrotamap.navigation.DualFreqGnssEngine
# - com.example.carrotamap.navigation.GeoUtils
# - com.example.carrotamap.navigation.GnssEnhancedProvider
# - com.example.carrotamap.navigation.GpsKalmanFilter
# - com.example.carrotamap.navigation.HybridRouteProvider
# - com.example.carrotamap.navigation.LaneInfoCache
# - com.example.carrotamap.navigation.LaneLevelNavigator
# - com.example.carrotamap.navigation.NtripClient
# - com.example.carrotamap.navigation.OsmDataMapper
# - com.example.carrotamap.navigation.OsmNavigationManager
# - com.example.carrotamap.navigation.OsrmRouteProvider
# - com.example.carrotamap.navigation.OverpassClient
# - com.example.carrotamap.navigation.RouteEngine
# - com.example.carrotamap.navigation.RouteTracker
# - com.example.carrotamap.navigation.RtcmDecoder
# - com.example.carrotamap.ui.components.HelpPage
# - com.example.carrotamap.ui.components.LaneComponents
# - com.example.carrotamap.ui.components.LaneWarningOverlay
# - com.example.carrotamap.ui.components.LedMatrixManager
# - com.example.carrotamap.ui.components.MapSearchService
# - com.example.carrotamap.ui.components.NavigationIcons
# - com.example.carrotamap.ui.components.ProfilePage
# - com.example.carrotamap.ui.theme.*
# - com.example.carrotamap.ui.utils.*
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
