@echo off
title Denmark Road Assistant - undo the review update
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0undo-review.ps1"
echo.
pause
