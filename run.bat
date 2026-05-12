@echo off
chcp 65001 >nul
setlocal

set "ROOT=%~dp0"

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=java"
)

if not exist "%ROOT%dist\cfr-selective-dec-standalone.jar" (
    call "%ROOT%build.bat"
    if errorlevel 1 exit /b 1
)

"%JAVA%" -jar "%ROOT%dist\cfr-selective-dec-standalone.jar" %*
endlocal
