@echo off
chcp 65001 >nul
setlocal

set "ROOT=%~dp0"
set "CFR_SRC=%ROOT%third_party\cfr\src"

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAR=%JAVA_HOME%\bin\jar.exe"
) else (
    set "JAVAC=javac"
    set "JAR=jar"
)

where "%JAVAC%" >nul 2>nul
if errorlevel 1 (
    if not exist "%JAVAC%" (
        echo javac not found. Set JAVA_HOME to a JDK 8+ installation, or add javac to PATH.
        exit /b 1
    )
)

where "%JAR%" >nul 2>nul
if errorlevel 1 (
    if not exist "%JAR%" (
        echo jar not found. Set JAVA_HOME to a JDK 8+ installation, or add jar to PATH.
        exit /b 1
    )
)

if not exist "%CFR_SRC%\org\benf\cfr\reader\Main.java" (
    echo CFR source not found: "%CFR_SRC%"
    echo Please make sure third_party\cfr\src is present.
    exit /b 1
)

if exist "%ROOT%build\classes" rmdir /s /q "%ROOT%build\classes"
if exist "%ROOT%build\generated" rmdir /s /q "%ROOT%build\generated"
if not exist "%ROOT%build" mkdir "%ROOT%build"
if not exist "%ROOT%dist" mkdir "%ROOT%dist"
mkdir "%ROOT%build\classes"
mkdir "%ROOT%build\generated\org\benf\cfr\reader\util"

(
echo package org.benf.cfr.reader.util;
echo public class CfrVersionInfo {
echo     private CfrVersionInfo^(^) {}
echo     public static final String VERSION = "0.153-local";
echo     public static final boolean SNAPSHOT = true;
echo     public static final String GIT_COMMIT_ABBREVIATED = "local";
echo     public static final boolean GIT_IS_DIRTY = false;
echo     public static final String VERSION_INFO = VERSION + " (" + GIT_COMMIT_ABBREVIATED + ")";
echo }
) > "%ROOT%build\generated\org\benf\cfr\reader\util\CfrVersionInfo.java"

dir /s /b "%CFR_SRC%\*.java" > "%ROOT%build\sources.txt"
dir /s /b "%ROOT%src\main\java\*.java" >> "%ROOT%build\sources.txt"
dir /s /b "%ROOT%build\generated\*.java" >> "%ROOT%build\sources.txt"

"%JAVAC%" --release 8 -version >nul 2>nul
if errorlevel 1 (
    set "JAVA8_ARGS=-source 1.8 -target 1.8"
) else (
    set "JAVA8_ARGS=--release 8"
)

"%JAVAC%" -encoding UTF-8 -Xlint:-options %JAVA8_ARGS% -d "%ROOT%build\classes" @"%ROOT%build\sources.txt"
if errorlevel 1 exit /b 1

"%JAR%" cfe "%ROOT%dist\cfr-selective-dec-standalone.jar" com.aq.cfrselect.Main -C "%ROOT%build\classes" .
if errorlevel 1 exit /b 1

copy /y "%ROOT%dist\cfr-selective-dec-standalone.jar" "%ROOT%dist\cfr-selective-dec.jar" >nul
echo Built: "%ROOT%dist\cfr-selective-dec-standalone.jar"
endlocal
