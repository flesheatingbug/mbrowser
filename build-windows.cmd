@echo off
rem Builds the mbrowser installer on Windows. Needs Node.js 20+ (https://nodejs.org).
rem Works from the folder this file lives in, however it was started.
cd /d "%~dp0"
set CSC_IDENTITY_AUTO_DISCOVERY=false
if not exist package.json goto nopkg
where node >nul 2>nul
if errorlevel 1 goto nonode
echo Building in %CD%
call npm install
if %ERRORLEVEL% NEQ 0 goto fail
call npm run dist:win
if %ERRORLEVEL% EQU 0 goto built
echo.
echo The normal build failed. This is usually because Windows Developer Mode is off.
echo Retrying with a simpler build that leaves the program icon unchanged...
call npx electron-builder --win --publish never -c.win.signAndEditExecutable=false
if %ERRORLEVEL% NEQ 0 goto fail
:built
if not exist "dist\*.exe" goto fail
echo.
echo Done. The installer and a portable zip are in: %CD%\dist
start "" "%CD%\dist"
pause
exit /b 0
:nopkg
echo package.json was not found in %CD%
echo Extract the whole zip first, then run this file from inside the extracted folder.
pause
exit /b 1
:nonode
echo Node.js was not found. Install it from https://nodejs.org and run this again.
pause
exit /b 1
:fail
echo.
echo The build failed. Scroll up for the error message.
pause
exit /b 1
