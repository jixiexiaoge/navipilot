@echo off
chcp 65001 >nul
echo ========================================
echo    Navipilot Release 构建脚本
echo ========================================
echo.

set PROJECT_DIR=%~dp0
set APK_DIR=%PROJECT_DIR%app\build\outputs\apk\release
set VERSION=v260530

echo [1/4] 清理项目...
call gradlew clean

echo.
echo [2/4] 运行单元测试...
call gradlew testDebugUnitTest
if %ERRORLEVEL% neq 0 (
    echo 单元测试失败
    pause
    exit /b 1
)

echo.
echo [3/4] 构建 Release 版本...
call gradlew assembleRelease
if %ERRORLEVEL% neq 0 (
    echo 构建失败
    pause
    exit /b 1
)

echo.
echo [4/4] 整理输出文件...
if not exist "%PROJECT_DIR%release" mkdir "%PROJECT_DIR%release"

:: 查找最新 APK 文件
for /f "delims=" %%F in ('dir /b /o-d "%APK_DIR%\*.apk" 2^>nul') do (
    set "LATEST_APK=%%F"
    goto :found
)
:found
if defined LATEST_APK (
    copy "%APK_DIR%\%LATEST_APK%" "%PROJECT_DIR%release\Navipilot-%VERSION%.apk" >nul
    for %%F in ("%PROJECT_DIR%release\Navipilot-%VERSION%.apk") do set "apk_size=%%~zF"
    set /a apk_size_mb=%apk_size% / 1048576
    echo.
    echo ========================================
    echo    构建完成
    echo ========================================
    echo.
    echo APK: release\Navipilot-%VERSION%.apk
    echo 大小: %apk_size_mb% MB
    echo.
) else (
    echo 未找到 APK 文件
    pause
    exit /b 1
)

echo 按任意键退出...
pause >nul
