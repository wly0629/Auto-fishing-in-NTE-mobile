@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

set BUILD_TYPE=%1
if "%BUILD_TYPE%"=="" set BUILD_TYPE=release

echo ========================================
echo  AutoFish Build Script
echo  Build Type: %BUILD_TYPE%
echo ========================================
echo.

rem 先清理以防止残留
echo [1/3] 清理上次构建...
call gradlew clean --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 清理失败，错误码: %ERRORLEVEL%
    pause
    exit /b %ERRORLEVEL%
)
echo [完成] 清理成功
echo.

echo [2/3] 构建 APK ^(%BUILD_TYPE%^)...
if /i "%BUILD_TYPE%"=="debug" (
    call gradlew assembleDebug --no-daemon
) else (
    call gradlew assembleRelease --no-daemon
)
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 构建失败，错误码: %ERRORLEVEL%
    pause
    exit /b %ERRORLEVEL%
)
echo [完成] 构建成功
echo.

echo [3/3] 输出 APK 文件:
echo ----------------------------------------
if /i "%BUILD_TYPE%"=="debug" (
    for %%f in (app\build\outputs\apk\debug\*.apk) do (
        echo  %%f
    )
) else (
    for %%f in (app\build\outputs\apk\release\*.apk) do (
        echo  %%f
    )
)
echo ----------------------------------------
echo.

echo ^> 构建完成!
echo.
pause
