@echo off
setlocal
cd /d "%~dp0"

if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_HOME if defined LOCALAPPDATA set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

if not exist "packager\gui.py" (
    echo [ERROR] packager\gui.py was not found. Keep this file in the project root.
    pause
    exit /b 1
)

where py >nul 2>nul
if not errorlevel 1 goto launch_with_py
where python >nul 2>nul
if not errorlevel 1 goto launch_with_python

echo [ERROR] Python 3.8 or later was not found in PATH.
pause
exit /b 1

:launch_with_py
py -3 -m packager.gui
goto check_result

:launch_with_python
python -m packager.gui

:check_result
if not errorlevel 1 goto done
echo [ERROR] Packager GUI failed to start. Check Python, JDK 17, and Android SDK.
pause

:done
endlocal
