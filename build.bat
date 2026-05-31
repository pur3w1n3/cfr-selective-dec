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
        echo Maven not found. Install Maven and add mvn to PATH, set MAVEN_HOME, or provide mvnw.cmd.
        exit /b 1
    )
)

if not exist "%ROOT%pom.xml" (
    echo pom.xml not found: "%ROOT%pom.xml"
    exit /b 1
)

"%MVN%" -f "%ROOT%pom.xml" clean package
if errorlevel 1 exit /b 1

echo Built: "%ROOT%target\cfr-selective-dec-standalone.jar"
endlocal
