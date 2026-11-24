@echo off
setlocal
cd /d "%~dp0"
if not exist "target\scientific-lens-harness-0.1.0-all.jar" (
    call mvnw.cmd -B package
    if errorlevel 1 (
        pause
        exit /b 1
    )
)
java --enable-native-access=ALL-UNNAMED -jar "target\scientific-lens-harness-0.1.0-all.jar"
if errorlevel 1 pause