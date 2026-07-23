@echo off
setlocal
set "ROOT=%~dp0"
set "DEST=%ROOT%app\src\main\jni\hev"
if exist "%DEST%\Android.mk" (
  echo Hev native core already present: %DEST%
  exit /b 0
)
where git >nul 2>nul || (
  echo Git is required. Install Git, then run this file again.
  exit /b 1
)
if exist "%DEST%" rmdir /s /q "%DEST%"
git clone --depth 1 --branch 2.16.0 --recursive --shallow-submodules https://github.com/heiher/hev-socks5-tunnel.git "%DEST%"
if errorlevel 1 exit /b 1
if not exist "%DEST%\Android.mk" (
  echo Hev source download is incomplete.
  exit /b 1
)
echo Fetched official HevSocks5Tunnel 2.16.0
