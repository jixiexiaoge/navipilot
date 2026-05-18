@echo off
echo 生成APK签名密钥...

set /p APP_NAME="请输入应用名称 (默认: CarrotAmap): "
if "%APP_NAME%"=="" set APP_NAME=CarrotAmap

set /p COMPANY_NAME="请输入公司/开发者名称 (默认: YourCompany): "
if "%COMPANY_NAME%"=="" set COMPANY_NAME=YourCompany

set /p PASSWORD="请输入密钥密码 (至少6位): "
if "%PASSWORD%"=="" (
    echo 密码不能为空！
    pause
    exit /b 1
)

echo 正在生成密钥文件...
keytool -genkey -v -keystore app\release.keystore -alias %APP_NAME% -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=%COMPANY_NAME%, OU=Development, O=%COMPANY_NAME%, L=Beijing, S=Beijing, C=CN" -storepass %PASSWORD% -keypass %PASSWORD%

if exist "app\release.keystore" (
    echo ✓ 密钥文件生成成功: app\release.keystore
    echo 请妥善保管此文件和密码！
    
    echo.
    echo 现在需要更新 app\build.gradle.kts 中的签名配置:
    echo signingConfigs {
    echo     create("release") {
    echo         storeFile = file("release.keystore")
    echo         storePassword = "%PASSWORD%"
    echo         keyAlias = "%APP_NAME%"
    echo         keyPassword = "%PASSWORD%"
    echo     }
    echo }
) else (
    echo ✗ 密钥文件生成失败！
)

pause
