import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt-config.yml"))
}

android {
    namespace = "com.example.navipilot"
    compileSdk = 35

    val navAbiList: List<String> =
        (project.findProperty("navipilot.abis") as String?)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a")

    defaultConfig {
        applicationId = "com.example.navipilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 260516
        versionName = "v260516"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // GitHub OAuth Client ID（从 local.properties 读取，不硬编码）
        val props = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"${props.getProperty("GITHUB_CLIENT_ID", "Ov23ctaOHfiktpd9aTE6")}\"")
        // 高德 Web 服务（输入提示 restapi），在 local.properties 配置 AMAP_WEB_KEY / AMAP_WEB_SECRET（安全密钥，用于数字签名）
        val amapWebKey = props.getProperty("AMAP_WEB_KEY", "").replace("\\", "\\\\").replace("\"", "\\\"")
        val amapWebSecret = props.getProperty("AMAP_WEB_SECRET", "").replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "AMAP_WEB_KEY", "\"$amapWebKey\"")
        buildConfigField("String", "AMAP_WEB_SECRET", "\"$amapWebSecret\"")
        // Google Maps API Key（注入 AndroidManifest）
        manifestPlaceholders["MAPS_API_KEY"] = props.getProperty("MAPS_API_KEY", "")

        ndk {
            abiFilters.clear()
            abiFilters.addAll(navAbiList)
        }
    }

    bundle {
        abi {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        language {
            enableSplit = false
        }
    }

    // 签名配置（从 local.properties 读取，不硬编码密码）
    signingConfigs {
        create("release") {
            val props = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
            
            storeFile = file("release.keystore")
            storePassword = props.getProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = props.getProperty("RELEASE_KEY_ALIAS", "")
            keyPassword = props.getProperty("RELEASE_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false  // 高德JAR的final R class ids与optimized shrinking不兼容
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isCrunchPngs = true
            // 🔧 Gradle 9.x要求：使用proguard-android-optimize.txt
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "security-config.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isCrunchPngs = false
            packaging {
                jniLibs {
                    pickFirsts += listOf("**/libc++_shared.so")
                    keepDebugSymbols += setOf(
                        "*/libc++_shared.so",
                        "*/libnavicore.so",
                        "*/libsynthesizer.so",
                        "*/libtxmapvis.so"
                    )
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    
    // Kotlin JVM 目标版本（必须与 Java compileOptions 一致）
    kotlinOptions {
        jvmTarget = "11"
    }
    
    // Kotlin Compose Compiler配置（Gradle 9.x + Kotlin 2.1）
    composeCompiler {
        // 启用强跳过模式以提升性能
        // enableStrongSkippingMode = true  // 已废弃，使用featureFlags
    }

    // Google Navigation SDK 需要较大的 heap（Gradle 9.x 已移除 dexOptions javaMaxHeapSize）

    buildFeatures {
        compose = true
        buildConfig = true  // 启用BuildConfig生成
    }

    // 高德 JNI：app/libs/arm64-v8a 等（与合并 JAR 配套）
    sourceSets.named("main") {
        jniLibs.srcDirs("libs")
    }

    // 抑制腾讯SDK namespace重复警告（SDK供应商问题，无法修复）
    lint {
        disable += "DuplicateNamespace"
        disable += "PackagedPrivateKey"
    }

    // 排除 Google Play Services Maps 传递依赖冲突（Navigation SDK 已包含）
    configurations.all {
        exclude(group = "com.google.android.gms", module = "play-services-maps")
        exclude(group = "com.google.android.gms", module = "play-services-location")
    }
    
    // R8优化配置
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/proguard/*",
                "META-INF/com.android.tools/*",
                "META-INF/gradle-plugins/*",
                "META-INF/versions/*",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/spring.schemas",
                "META-INF/spring.tooling",
                "META-INF/spring.handlers",
                "META-INF/spring.factories",
                "META-INF/spring-autoconfigure-metadata.properties",
                "META-INF/spring-boot-autoconfigure-processor.properties",
                "META-INF/spring-configuration-metadata.json",
                "META-INF/spring-configuration-metadata.properties",
                "META-INF/spring.factories",
                "META-INF/spring.schemas",
                "META-INF/spring.tooling",
                "META-INF/spring.handlers",
                "META-INF/spring-autoconfigure-metadata.properties",
                "META-INF/spring-boot-autoconfigure-processor.properties",
                "META-INF/spring-configuration-metadata.json",
                "META-INF/spring-configuration-metadata.properties"
            )
        }
        jniLibs {
            pickFirsts += listOf(
                "**/libc++_shared.so",
            )
        }
    }
    
    // 启用资源混淆（使用新的 androidResources API）
    androidResources {
        noCompress += setOf("tflite", "lite")
        ignoreAssetsPattern += setOf("!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~")
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Material View层组件（腾讯导航SDK的NavigatorLayerViewDrive需要Material主题属性）
    implementation("com.google.android.material:material:1.11.0")

    // AppCompat - 提供官方 Theme.AppCompat.DayNight 主题，解决腾讯SDK drawable主题解析
    implementation("androidx.appcompat:appcompat:1.6.1")

    // HTTP客户端 - 用于导航确认API请求和反馈提交
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // ExoPlayer - 用于视频播放
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    

    
    // Koin依赖注入 - P1 架构优化
    implementation("io.insert-koin:koin-android:3.5.3")  // Koin核心
    implementation("io.insert-koin:koin-androidx-compose:3.5.3")  // Compose集成
    implementation("io.insert-koin:koin-androidx-navigation:3.5.3")  // Navigation集成
    
    // Timber日志库 - P2 代码质量优化
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // DataStore - P3 功能增强（示例）
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // 🆕 安全存储（EncryptedSharedPreferences）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // MapLibre - OSM 地图显示
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    // 国内发行：腾讯导航 + 高德合并包（无 Google Navigation SDK）
    implementation("com.tencent.map:tencent-map-nav-sdk-core:7.5.0")
    implementation("com.tencent.openmap:foundation:0.8.0.07a0862-lite")
    implementation("com.tencent.map:tencent-map-nav-sdk-tts:7.5.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation(files("libs/TencentNtripSDK-2b52548-release.aar"))
    implementation(files("libs/AMap3DMap_11.1.200_AMapNavi_11.1.200_AMapSearch_9.7.4_AMapLocation_11.1.200_20260421.jar"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Google Navigation SDK 🆕
    api("com.google.android.libraries.navigation:navigation:7.0.0")
    api("org.chromium.net:cronet-fallback:119.6045.31")
    api("com.google.android.libraries.places:places:4.0.0")
    
    // ZeroMQ - 用于与comma3设备通信
    implementation("org.zeromq:jeromq:0.6.0")

    // SSH - 用于远程连接 comma3 设备
    // 使用 SSHJ 替代 JSch（更好的现代SSH支持，兼容Android）
    implementation("com.hierynomus:sshj:0.38.0")
    
    // SLF4J - SSHJ依赖的日志框架
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("com.github.tony19:logback-android:3.0.0")

    // BouncyCastle - 用于解析 RSA 私钥（SSHJ需要）
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // 测试框架 - P0 优先级优化
    testImplementation(libs.junit)
    testImplementation("com.google.truth:truth:1.1.5")  // Google Truth 断言库
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")  // 协程测试
    testImplementation("io.mockk:mockk:1.13.8")  // Kotlin Mock 框架
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation("io.mockk:mockk-android:1.13.8")  // Android Mock 支持
    
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
