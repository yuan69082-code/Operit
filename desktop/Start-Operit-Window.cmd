@echo off
cd /d "%~dp0"
"..\electron\electron.exe" "%~dp0."
if errorlevel 1 pause
