@echo off
rem =====================================================================
rem analyze.bat - jvm-heap-analyzer launcher (Windows cmd)
rem
rem 1) Auto heap: parse memory scales with OBJECT COUNT; rule of thumb
rem    Xmx = dumpGB * 2.5 + 1, floor 4g, cap 60% of RAM.
rem    Override with XMX_OVERRIDE=16g if needed.
rem 2) -b  background mode: detached run, output to analyze.log
rem 3) dumps >= 4GB get friendlier defaults (--top 10, --min-retained-mb 20)
rem    unless the user passed them explicitly.
rem
rem Usage:
rem   analyze.bat -i dump.hprof -o report\
rem   analyze.bat -i dump.hprof -o report\ --app-prefix com.mycorp
rem   analyze.bat -b -i dump.hprof -o report\
rem   analyze.bat -i dump.hprof -o report\ --top 5 --min-retained-mb 50
rem
rem Env vars: JAR / JAVA / XMX_OVERRIDE / JAVA_OPTS / LOG
rem All-ASCII on purpose: non-ASCII bytes break cmd parsing under GBK.
rem =====================================================================
setlocal EnableDelayedExpansion

rem ---------- locate jar: build\libs first, then side-by-side ----------
if defined JAR goto jar_ok
set "JAR="
for %%f in ("%~dp0build\libs\jvm-heap-analyzer-*.jar") do set "JAR=%%~ff"
if not defined JAR for %%f in ("%~dp0jvm-heap-analyzer-*.jar") do set "JAR=%%~ff"
:jar_ok
if not defined JAR (
  echo [analyze] fat-jar not found. Build first: gradlew.bat build, or set JAR env var. 1>&2
  exit /b 2
)
if not defined JAVA set "JAVA=java"

rem ---------- scan args: strip -b, find -i target ----------
set "BG=0"
set "DUMP="
set "PREV="
set "KEEP="
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="-b"     (set "BG=1" & shift & goto parse)
if /i "%~1"=="--bg"   (set "BG=1" & shift & goto parse)
if "%PREV%"=="-i"      set "DUMP=%~1"
if "%PREV%"=="--input" set "DUMP=%~1"
set "PREV=%~1"
set "KEEP=!KEEP! %~1"
shift
goto parse
:parsed

rem ---------- zero-arg defaults: pick newest *.hprof next to script; -o . ----------
set "HAS_I=0"
set "HAS_O=0"
echo !KEEP! | findstr /c:" -i " 1>nul && set "HAS_I=1"
echo !KEEP! | findstr /c:" --input " 1>nul && set "HAS_I=1"
echo !KEEP! | findstr /c:" -o " 1>nul && set "HAS_O=1"
echo !KEEP! | findstr /c:" --output " 1>nul && set "HAS_O=1"
if "%HAS_I%"=="0" (
  set "FOUND="
  for /f "delims=" %%f in ('dir /b /a-d /o-d "%~dp0*.hprof" "%~dp0*.hprof.gz" 2^>nul') do if not defined FOUND set "FOUND=%~dp0%%f"
  if not defined FOUND (
    echo [analyze] no -i given and no *.hprof found next to the script 1>&2
    exit /b 2
  )
  echo [analyze] -i not given, auto-picked newest hprof: !FOUND!
  set "KEEP=!KEEP! -i !FOUND!"
  set "DUMP=!FOUND!"
)
if "%HAS_O%"=="0" (
  echo [analyze] -o not given, report goes to current directory
  set "KEEP=!KEEP! -o ."
)

rem ---------- heap sizing ----------
if defined XMX_OVERRIDE (
  set "XMX_ARGS=-Xmx%XMX_OVERRIDE% -Xms1g"
  goto defaults
)
set "XMX_ARGS=-Xmx4g -Xms1g"
set "SIZE_GB=0"
if not defined DUMP goto defaults
if not exist "%DUMP%" goto defaults

rem one PowerShell round-trip, single line "<dumpGB>,<ramGB>"
set "SIZES="
for /f "usebackq delims=" %%v in (`powershell -nologo -command "$s=[int]((Get-Item -LiteralPath '%DUMP%').Length/1GB); if((Get-Item -LiteralPath '%DUMP%').Length %% 1GB -gt 0){$s++}; $r=[int]((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB); Write-Output ($s.ToString()+','+$r.ToString())" 2^>nul`) do set "SIZES=%%v"
if not defined SIZES goto defaults
for /f "tokens=1,2 delims=," %%a in ("%SIZES%") do (
  set "SIZE_GB=%%a"
  set "RAM_GB=%%b"
)
if not defined RAM_GB set "RAM_GB=16"

set /a "WANT=SIZE_GB*5/2+1" 2>nul
set /a "CAP=RAM_GB*60/100" 2>nul
if %CAP% LSS 4 set "CAP=4"
set "XMX_GB=%WANT%"
if %WANT% GTR %CAP% set "XMX_GB=%CAP%"
if %XMX_GB% LSS 4 set "XMX_GB=4"
set /a "XMS_GB=XMX_GB/4"
if %XMS_GB% LSS 1 set "XMS_GB=1"
set "XMX_ARGS=-Xmx!XMX_GB!g -Xms!XMS_GB!g"
echo [analyze] dump~!SIZE_GB!G ram~!RAM_GB!G -^> auto heap: -Xmx!XMX_GB!g (formula: dumpGB*2.5+1, cap=RAM*60%%) 1>&2

:defaults
rem ---------- friendly defaults for big dumps (>=4GB) only ----------
set "EXTRA="
if %SIZE_GB% GEQ 4 (
  echo !KEEP! | findstr /c:"--top" >nul || set "EXTRA=--top 10"
  echo !KEEP! | findstr /c:"--min-retained-mb" >nul || set "EXTRA=!EXTRA! --min-retained-mb 20"
)

rem ---------- run (foreground or detached) ----------
if not defined LOG set "LOG=analyze.log"
if "%BG%"=="1" (
  echo [analyze] background mode, log: !LOG!
  start "jvm-heap-analyzer" /min cmd /c ""%JAVA%" %XMX_ARGS% -XX:+UseG1GC %JAVA_OPTS% -jar "%JAR%" !KEEP! %EXTRA% > "!LOG!" 2>&1"
  echo [analyze] progress: type !LOG!  -- heartbeat every 60s; growing sizes mean progress
  goto end
)
"%JAVA%" %XMX_ARGS% -XX:+UseG1GC %JAVA_OPTS% -jar "%JAR%" !KEEP! %EXTRA%
:end
endlocal
