@echo off
echo ========================================
echo Navipilot 发布部署脚本
echo ========================================

REM 设置变量
set PROJECT_DIR=C:\Users\zhudo\AndroidStudioProjects\Navipilot
set APK_NAME=Navipilot-Release-v1.0.0.apk
set KEYSTORE_PATH=%PROJECT_DIR%\release.keystore
set KEYSTORE_ALIAS=navipilot

echo.
echo 1. 清理项目...
cd /d "%PROJECT_DIR%"
call gradlew clean

echo.
echo 2. 运行测试...
call gradlew testDebugUnitTest
if %ERRORLEVEL% neq 0 (
    echo 测试失败，停止部署
    pause
    exit /b 1
)

echo.
echo 3. 编译Release版本...
call gradlew assembleRelease
if %ERRORLEVEL% neq 0 (
    echo 编译失败，停止部署
    pause
    exit /b 1
)

echo.
echo 4. 签名APK...
if exist "%KEYSTORE_PATH%" (
    jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 -keystore "%KEYSTORE_PATH%" app\build\outputs\apk\release\app-release-unsigned.apk %KEYSTORE_ALIAS%
    if %ERRORLEVEL% neq 0 (
        echo 签名失败，停止部署
        pause
        exit /b 1
    )
) else (
    echo 签名文件不存在，跳过签名步骤
)

echo.
echo 5. 优化APK...
if exist "app\build\outputs\apk\release\app-release-unsigned.apk" (
    zipalign -v 4 app\build\outputs\apk\release\app-release-unsigned.apk app\build\outputs\apk\release\%APK_NAME%
    if %ERRORLEVEL% neq 0 (
        echo APK优化失败
    )
)

echo.
echo 6. 生成发布包...
if exist "app\build\outputs\apk\release\%APK_NAME%" (
    echo 创建发布目录...
    if not exist "release" mkdir release
    
    echo 复制APK到发布目录...
    copy "app\build\outputs\apk\release\%APK_NAME%" "release\"
    
    echo 复制文档...
    if exist "USER_TESTING_GUIDE.md" copy "USER_TESTING_GUIDE.md" "release\"
    if exist "README.md" copy "README.md" "release\"
    
    echo 生成发布信息...
    echo Navipilot Release v1.0.0 > release\RELEASE_NOTES.txt
    echo 构建时间: %DATE% %TIME% >> release\RELEASE_NOTES.txt
    echo 构建环境: Windows 10 >> release\RELEASE_NOTES.txt
    echo. >> release\RELEASE_NOTES.txt
    echo 主要改进: >> release\RELEASE_NOTES.txt
    echo - 添加了完整的错误处理机制 >> release\RELEASE_NOTES.txt
    echo - 重构了MainActivity，分离职责 >> release\RELEASE_NOTES.txt
    echo - 添加了性能监控和内存优化 >> release\RELEASE_NOTES.txt
    echo - 完善了单元测试和集成测试 >> release\RELEASE_NOTES.txt
    
    echo.
    echo ========================================
    echo 部署完成！
    echo ========================================
    echo APK文件: release\%APK_NAME%
    echo 发布目录: %PROJECT_DIR%\release
    echo.
    echo 下一步：
    echo 1. 测试APK安装和运行
    echo 2. 按照USER_TESTING_GUIDE.md进行测试
    echo 3. 收集用户反馈
    echo 4. 发布到应用商店
    echo.
) else (
    echo APK文件不存在，部署失败
    pause
    exit /b 1
)

echo.
echo 按任意键退出...
pause > nul
