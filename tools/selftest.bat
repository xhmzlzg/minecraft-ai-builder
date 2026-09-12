@echo off
rem 离线自检：不启动游戏，直接验证"规格 → 方块方案 → 体检"这条链路
rem 用法：项目根目录下执行  tools\selftest.bat
setlocal

set SRC=%~dp0..
set GSON=%USERPROFILE%\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson

rem 找 gson jar（gradle 缓存里的任意版本）
for /f "delims=" %%i in ('dir /b /s "%GSON%\*.jar" 2^>nul') do set GSONJAR=%%i
if "%GSONJAR%"=="" (
  echo [selftest] 未找到 gson jar，请先运行一次 gradlew build
  exit /b 1
)

if not exist "%SRC%\build\selftest" mkdir "%SRC%\build\selftest"

javac -encoding UTF-8 -cp "%GSONJAR%" -d "%SRC%\build\selftest" ^
  "%SRC%\src\main\java\com\mcai\common\BuildingPlan.java" ^
  "%SRC%\src\main\java\com\mcai\common\PlanValidator.java" ^
  "%SRC%\src\main\java\com\mcai\common\spec\*.java" ^
  "%SRC%\selftest\SpecSelfTest.java"
if errorlevel 1 exit /b 1

java -Dstdout.encoding=UTF-8 -cp "%SRC%\build\selftest;%GSONJAR%" SpecSelfTest
endlocal
