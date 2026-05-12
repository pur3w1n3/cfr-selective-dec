@echo off
chcp 65001 >nul
setlocal

set "ROOT=%~dp0"

if exist "%ROOT%build" rmdir /s /q "%ROOT%build"
if exist "%ROOT%dist" rmdir /s /q "%ROOT%dist"

echo Cleaned build outputs.
endlocal
