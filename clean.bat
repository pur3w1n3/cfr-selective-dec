@echo off
chcp 65001 >nul
setlocal

set "ROOT=%~dp0"
set "MVN=mvn"

if exist "%ROOT%mvnw.cmd" (
    set "MVN=%ROOT%mvnw.cmd"
)
if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" (
    set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
)

where "%MVN%" >nul 2>nul
if errorlevel 1 (
    if not exist "%MVN%" (
        if exist "%ROOT%target" rmdir /s /q "%ROOT%target"
        echo Cleaned target.
        endlocal
        exit /b 0
    )
)

"%MVN%" -f "%ROOT%pom.xml" clean
if errorlevel 1 exit /b 1

echo Cleaned target.
endlocal
