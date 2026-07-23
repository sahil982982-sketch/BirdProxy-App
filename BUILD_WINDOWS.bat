@echo off
setlocal
cd /d "%~dp0"
call FETCH_NATIVE_CORE.bat || exit /b 1
call gradlew.bat clean assembleDebug || exit /b 1
echo.
echo APK: app\build\outputs\apk\debug\app-debug.apk
