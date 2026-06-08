@echo off
chcp 65001 >nul
echo ============================================
echo  异环手游自动钓鱼 - 编译并发布到项目主目录
echo ============================================
echo.

REM 检查 Java
where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 找不到 Java，请确保已安装 JDK 17+
    echo 安装地址: https://adoptium.net/temurin/releases/
    pause
    exit /b 1
)

echo [1/3] 编译 Release APK...
call gradlew assembleRelease
if %errorlevel% neq 0 (
    echo [错误] 编译失败，请检查 Android SDK 配置
    pause
    exit /b 1
)

echo [2/3] 复制 APK 到项目主目录...
copy /Y app\build\outputs\apk\release\app-release.apk app.apk
if %errorlevel% equ 0 (
    echo ✅ APK 已复制到项目根目录: %cd%\app.apk
) else (
    echo [警告] APK 文件未生成，请检查编译输出
)

echo [3/3] 更新 update.json...
if exist app.apk (
    for %%i in (app.apk) do set size=%%~zi
    REM 简单估算 size (字节转MB)
    set /a sizeMB=%size% / 1048576
    echo ✅ APK 大小: ~%sizeMB% MB
    echo    update.json 中的 targetSize 可手动调整
)

echo.
echo ============================================
echo  编译完成！
echo.
echo  📦 app.apk       - Release APK
echo  📄 update.json   - 更新配置文件
echo.
echo  如需发布到 GitHub Release:
echo   1. 提交代码: git add -A ^&^& git commit -m "release: v1.0.1"
echo   2. 推送代码: git push
echo   3. 创建 Release 并上传 app.apk ^& update.json
echo ============================================
pause
