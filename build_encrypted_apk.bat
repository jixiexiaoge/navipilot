@echo off
chcp 65001 >nul
title NaviPilot Release Build
echo ========================================
echo    NaviPilot Release APK 构建
echo    R8 全混淆 + 代码重打包 + 日志移除
echo ========================================
echo.

:: 检查 keystore，不存在则创建
if not exist "app\release.keystore" (
    echo 🔑 正在创建签名文件...
    call "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkey -v ^
        -keystore app\release.keystore ^
        -alias navipilot_key ^
        -keyalg RSA -keysize 2048 -validity 10000 ^
        -storepass navipilot123456 ^
        -keypass navipilot123456 ^
        -dname "CN=NaviPilot, OU=Development, O=NaviPilot, L=Beijing, S=Beijing, C=CN" >nul 2>&1
    if errorlevel 1 (
        echo ❌ 创建签名文件失败
        pause
        exit /b 1
    )
    echo ✅ 签名文件创建成功
) else (
    echo ✅ 签名文件已存在
)

:: 确保 local.properties 中有签名配置
findstr /B "RELEASE_STORE_PASSWORD" local.properties >nul 2>&1
if errorlevel 1 (
    echo   正在写入签名配置到 local.properties...
    >>local.properties echo.
    >>local.properties echo # Release signing (不要提交到版本控制)
    >>local.properties echo RELEASE_STORE_PASSWORD=navipilot123456
    >>local.properties echo RELEASE_KEY_ALIAS=navipilot_key
    >>local.properties echo RELEASE_KEY_PASSWORD=navipilot123456
    echo ✅ 签名配置已写入
)

:: 停止 Gradle 守护进程（避免文件锁定）
echo ⏳ 正在停止 Gradle 守护进程...
call .\gradlew --stop >nul 2>&1

:: 清理并构建 Release APK
echo.
echo ⚙️  正在构建 Release APK（R8 混淆已开启）...
call .\gradlew clean assembleRelease

if errorlevel 1 (
    echo ❌ 构建失败
    echo.
    echo 常见原因：
    echo   1. local.properties 签名密码与 keystore 不匹配
    echo   2. 依赖库下载失败（检查网络）
    echo   3. 高德/腾讯 SDK 许可过期
    pause
    exit /b 1
)

:: 定位生成的 APK
set "APK_SRC=app\build\outputs\apk\release\app-release.apk"
set "APK_DST=NaviPilot-Release.apk"

if not exist "%APK_SRC%" (
    echo ❌ 未找到 APK 文件: %APK_SRC%
    pause
    exit /b 1
)

copy "%APK_SRC%" "%APK_DST%" >nul 2>&1
if errorlevel 1 (
    echo ❌ 复制 APK 失败
    pause
    exit /b 1
)

:: 获取 APK 文件大小
for %%F in ("%APK_DST%") do set "APK_BYTES=%%~zF"
set /a APK_KB=%APK_BYTES% / 1024
set /a APK_MB=%APK_BYTES% / 1048576

:: 显示构建结果
echo.
echo ========================================
echo   ✅ Release APK 构建完成
echo ========================================
echo.
echo  📦 输出文件: %APK_DST%
echo  📊 文件大小: %APK_KB% KB (%APK_MB% MB)
echo.
echo  🔒 保护措施:
echo     • R8 全混淆（类名/方法名/字段名映射为 a/b/c）
echo     • 代码重打包至统一混淆包
echo     • Release 日志全部移除
echo     • 调试信息剥离
echo     • APK 数字签名
echo.
echo  ⚠️  注意：ProGuard 是混淆工具，不是加密！
echo     反编译仍然可能（只是输出难以阅读），
echo     真正的代码加密需要商业工具（如 DexGuard）。
echo.
echo 📱 安装方法:
echo     adb install %APK_DST%
echo     或 将 APK 传输到 Android 设备后点击安装
echo.
echo 按任意键退出...
pause >nul
