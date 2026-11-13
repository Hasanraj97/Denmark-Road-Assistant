@echo off
title Denmark Road Assistant - review fixes and evidence
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0review.ps1"
echo.
pause
