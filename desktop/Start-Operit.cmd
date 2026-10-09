@echo off
cd /d "%~dp0.."
runtime\node.exe desktop\src\launcher.cjs
pause
