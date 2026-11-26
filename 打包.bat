@echo off
setlocal EnableExtensions
chcp 936 >nul
title jvm-heap-analyzer - 一键打包

rem =====================================================================
rem 双击运行即可打包；也可以带参数运行 打包.bat -SkipTests / -Clean
rem 实际工作由同目录 package.ps1 完成：
rem   定位 JDK 17 -> 运行测试 -> shadowJar -> 生成 dist 目录与 zip
rem 说明：本文件保存为 GBK/ANSI 编码，避免 cmd 中文乱码。
rem =====================================================================

cd /d "%~dp0"

echo ============================================================
echo              jvm-heap-analyzer - 一键打包
echo ============================================================
echo.

if not exist "%~dp0package.ps1" (
  echo [ERROR] 未找到 package.ps1，请把本脚本放在 package.ps1 同目录。
  if not defined NO_PAUSE pause
  exit /b 2
)

set "PS=powershell"
where pwsh >nul 2>&1
if not errorlevel 1 set "PS=pwsh"

echo 步骤：定位 JDK 17 - 运行测试 - 打 fat-jar - 生成 dist 压缩包
echo 首次打包可能需要 1-2 分钟，请勿关闭窗口。
echo.

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0package.ps1" %*
set "RC=%ERRORLEVEL%"

echo.
if not "%RC%"=="0" (
  echo [FAILED] 打包失败，退出码 %RC%
  echo   1. 确认已安装 JDK 17
  echo   2. 可跳过测试重试：打包.bat -SkipTests
  echo   3. 查看上方日志中的第一个报错
  echo.
  if not defined NO_PAUSE pause
  exit /b %RC%
)

echo [OK] 打包完成，产物在 dist 目录：
if exist "%~dp0dist" (
  dir /b "%~dp0dist"
  if not defined NO_OPEN (
    echo.
    echo 正在打开 dist 目录...
    start "" "%~dp0dist"
  )
)
echo.
if not defined NO_PAUSE pause
exit /b 0
