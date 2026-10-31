@echo off
setlocal
cd /d "%~dp0"
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
