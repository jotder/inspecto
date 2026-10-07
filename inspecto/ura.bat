@echo off
rem URA File Management Suite ? development CLI runner
rem
rem Usage (from the inspecto\ directory after 'mvn clean package'):
rem   ura.bat [--dry-run] <command> <pipeline.toon> [args...]
rem
rem Examples:
rem   ura.bat help
rem   ura.bat search           config\adjustment\adjustment_pipeline.toon
rem   ura.bat copy             config\voucher\voucher_unknown_pipeline.toon
rem   ura.bat --dry-run backup config\adjustment\adjustment_pipeline.toon
rem   ura.bat prepare-inbox    config\adjustment\adjustment_pipeline.toon
rem   ura.bat create-schema    adjustment  samples\adj_sample.csv  config\adjustment\adj_gen.toon
rem
rem This script targets the fat JAR in target\ -- build once with 'mvn clean package'.
rem For deployed servers use ura.bat bundled alongside inspecto.jar.
setlocal
cd /d "%~dp0"

set "JAR="
for %%F in (target\inspecto-processor-*.jar) do set "JAR=%%F"
if not defined JAR (
    echo ERROR: no JAR found matching target\inspecto-processor-*.jar
    echo        Run 'mvn clean package' first.
    exit /b 1
)

rem MODULE-REORG-P3d stage 2: the core libraries are thin jars in their own modules' target\ dirs, not inside the product jar.
set "CP=%JAR%"
for /d %%D in (..\platform\* ..\spi\*) do for %%F in ("%%D\target\inspecto-*.jar") do echo %%~nF | findstr /i /c:"-tests" /c:"-sources" /c:"-javadoc" /c:"original" >nul || call set "CP=%%CP%%;%%F"

java --enable-native-access=ALL-UNNAMED ^
     -cp "%CP%" ^
     com.gamma.inspector.MainApp %*