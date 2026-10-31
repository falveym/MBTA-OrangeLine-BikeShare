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

set "COLLECTOR_JAR=%CD%\target\mbta-orangeline-bikeshare-1.0-SNAPSHOT-jar-with-dependencies.jar"
start "Orange Line collector" "%JAVA_HOME%\bin\java.exe" -cp "%COLLECTOR_JAR%" edu.wit.SegmentHeadwayTracker
start "Bluebikes collector" "%JAVA_HOME%\bin\java.exe" -cp "%COLLECTOR_JAR%" edu.wit.BluebikesOrangeLineCapacity
echo Collectors started in separate windows. Close each window to stop its collector.
echo CSV output folder: %CD%
