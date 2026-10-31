@echo off
setlocal
cd /d "%~dp0"

rem Prefer configured Java; fall back to IntelliJ-managed JDKs.
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" goto java_ready
for /d %%J in ("%USERPROFILE%\.jdks\*") do if exist "%%~J\bin\javac.exe" set "JAVA_HOME=%%~J"
:java_ready
if not defined JAVA_HOME (
    echo Java JDK 17 or newer is required. Set JAVA_HOME to its installation folder.
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
if /i "%~1"=="arrivals" goto run_collectors
if /i "%~1"=="bikes" goto run_collectors
set "MAVEN_CMD="
for /f "delims=" %%M in ('where mvn.cmd 2^>nul') do set "MAVEN_CMD=%%M"
if defined MAVEN_CMD goto maven_ready
for /d %%I in ("%LOCALAPPDATA%\Programs\IntelliJ IDEA*") do if exist "%%~I\plugins\maven\lib\maven3\bin\mvn.cmd" set "MAVEN_CMD=%%~I\plugins\maven\lib\maven3\bin\mvn.cmd"
if defined MAVEN_CMD goto maven_ready
for /d %%I in ("%USERPROFILE%\AppData\Local\Programs\IntelliJ IDEA*") do if exist "%%~I\plugins\maven\lib\maven3\bin\mvn.cmd" set "MAVEN_CMD=%%~I\plugins\maven\lib\maven3\bin\mvn.cmd"
:maven_ready
if not defined MAVEN_CMD (
    echo Maven was not found. Install Maven and add its bin folder to PATH.
    exit /b 1
)
call "%MAVEN_CMD%" package
if errorlevel 1 exit /b 1
if /i "%~1"=="--build-only" exit /b 0

:run_collectors
if not exist logs mkdir logs
set "JAR=target\mbta-orangeline-bikeshare-1.0-SNAPSHOT-jar-with-dependencies.jar"
if not exist "%JAR%" (
  echo Build first with mvn package.
  exit /b 1
)
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
  echo Java 17+ is required. Set JAVA_HOME or add java to PATH.
  exit /b 1
)
if /i "%~1"=="arrivals" (
  "%JAVA%" -cp "%JAR%" edu.wit.SegmentHeadwayTracker >>logs\arrivals.log 2>&1
  exit /b
)
if /i "%~1"=="bikes" (
  "%JAVA%" -cp "%JAR%" edu.wit.BluebikesOrangeLineCapacity >>logs\bikes.log 2>&1
  exit /b
)
echo Run with arrivals or bikes for a supervised collector.
echo Starting both in separate windows; close each window to stop its collector.
start "Orange Line arrivals" cmd /c call "%~f0" arrivals
start "Bluebikes" cmd /c call "%~f0" bikes
