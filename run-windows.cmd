@echo off
rem Starts mbrowser from this folder without installing it. Needs Node.js 20+ (https://nodejs.org).
cd /d "%~dp0"
if not exist package.json goto nopkg
where node >nul 2>nul
if errorlevel 1 goto nonode
if not exist node_modules call npm install
call npx electron . %*
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
