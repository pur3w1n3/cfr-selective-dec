@echo off
chcp 65001 >nul
setlocal

set "ROOT=%~dp0"
set "JAVA=java"

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
)

if not exist "%ROOT%target\cfr-selective-dec-standalone.jar" (
    call "%ROOT%build.bat"
    if errorlevel 1 exit /b 1
)

"%JAVA%" -jar "%ROOT%target\cfr-selective-dec-standalone.jar" %*
endlocal
