@echo off
title Denmark Road Assistant - thesis evidence
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1"
echo.
pause
