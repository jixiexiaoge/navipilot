import java.util.Properties
import java.io.FileOutputStream
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry
import org.objectweb.asm.*

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
    namespace = "com.jixiexiaoge.drivingassist"
    compileSdk = 35
    // AR 车道线推理薄壳（cpp/lanenet_jni.cpp）
    ndkVersion = "26.1.10909125"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val navAbiList: List<String> =
        (project.findProperty("navipilot.abis") as String?)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a")

    defaultConfig {
        applicationId = "com.jixiexiaoge.drivingassist"
        minSdk = 31
        targetSdk = 35
        versionCode = 26102602
        versionName = "8.1.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // GitHub OAuth Client ID（从 local.properties 读取，不硬编码）
        val props = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"${props.getProperty("GITHUB_CLIENT_ID", "Ov23ctaOHfiktpd9aTE6")}\"")

        ndk {
            abiFilters.clear()
            abiFilters.addAll(navAbiList)
        }

        // liblanenet.so：只转发 MNN 调用，运行时 dlopen jniLibs 里的 libVAE.so。
        // 用 c++_static 规避与其它 SDK 的 libc++_shared.so 冲突。
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-O3", "-ffast-math", "-fvisibility=hidden")
            }
        }
    }


    // 签名配置（从 local.properties 读取，不硬编码密码）
    signingConfigs {
        create("release") {
            val props = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
            
            storeFile = project.file(props.getProperty("RELEASE_STORE_FILE", "release.keystore"))
            storePassword = props.getProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = props.getProperty("RELEASE_KEY_ALIAS", "")
            keyPassword = props.getProperty("RELEASE_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "security-config.pro",
            )
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isCrunchPngs = true
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
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
        // 修复 Kotlin 编译器内部错误
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",  // 启用 JVM 默认方法
            "-Xno-call-assertions",  // 减少编译时断言检查
            "-Xno-param-assertions",
            "-Xno-receiver-assertions"
        )
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
        aidl = true
    }

    lint {
        disable += "DuplicateNamespace"
        disable += "PackagedPrivateKey"
    }

    // R8优化配置
    packaging {
        resources {
            merges += setOf("META-INF/LICENSE*.md", "META-INF/NOTICE*.md")
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
        // .mnn 必须不压缩：默认 5.8MB 每次启动都要解压一遍，且 assets.open 拿不到真实长度
        noCompress += setOf("tflite", "lite", "mnn")
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

    // Material View层组件 - 已移除（原为腾讯导航SDK提供主题属性）

    // AppCompat - 已移除（原为腾讯SDK drawable主题解析）

    // HTTP客户端 - 用于导航确认API请求和反馈提交
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    
    // ExoPlayer - 用于视频播放
    // 已移除（无代码使用）
    // implementation("androidx.media3:media3-exoplayer:1.2.1")
    // implementation("androidx.media3:media3-ui:1.2.1")
    // implementation("androidx.media3:media3-common:1.2.1")
    

    
    // Koin依赖注入 - 已移除（无使用）
    // implementation("io.insert-koin:koin-android:3.5.3")
    // implementation("io.insert-koin:koin-androidx-compose:3.5.3")
    // implementation("io.insert-koin:koin-androidx-navigation:3.5.3")
    
    // Timber日志库 - P2 代码质量优化
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // DataStore - P3 功能增强（示例）
    // 已移除（无代码使用）
    // implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // 🆕 安全存储（EncryptedSharedPreferences）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Amap Auto virtual-display projection for browser/Tesla viewing
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // ZeroMQ - 已移除（无使用）
    // implementation("org.zeromq:jeromq:0.6.0")

    // SSH - 用于远程连接 comma3 设备
    // 已移除（SshConnectionManager 已删除）
    // implementation("com.hierynomus:sshj:0.38.0")
    //
    // SLF4J - SSHJ依赖的日志框架
    // implementation("org.slf4j:slf4j-api:2.0.9")
    // implementation("com.github.tony19:logback-android:3.0.0")
    //
    // BouncyCastle - 用于解析 RSA 私钥（SSHJ需要）
    // implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    // BouncyCastle - 已移除（SSH 已删除）
    // implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // 测试框架 - P0 优先级优化
    testImplementation(libs.junit)
    testImplementation("com.google.truth:truth:1.1.5")  // Google Truth 断言库
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")  // 协程测试
    testImplementation("io.mockk:mockk:1.13.8")  // Kotlin Mock 框架
    testImplementation("org.json:json:20240303")
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation("io.mockk:mockk-android:1.13.8")  // Android Mock 支持
    
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
