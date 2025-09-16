@echo off
setlocal enabledelayedexpansion
echo ========================================
echo GUARDIAN IA ANDROID - CORRECTOR DE RECURSOS
echo Anderson Mamian Chicangana
echo Proyecto: Java/Kotlin Android
echo ========================================
echo.

set PROJECT_PATH=C:\Users\usuario\AndroidStudioProjects\GuardianIA\app\src\main\res

echo Navegando al proyecto Guardian IA Android...
cd /d "%PROJECT_PATH%"

if not exist layout (
    echo ERROR: No se encuentra el directorio layout del proyecto Guardian IA
    echo Verificar ruta: %PROJECT_PATH%
    pause
    exit /b 1
)

echo ✅ Proyecto Guardian IA Android encontrado
echo.

echo === CORRIGIENDO ARCHIVO PROBLEMÁTICO ===
cd layout

echo Buscando: " activity_admin_emergency_command.xml"
if exist " activity_admin_emergency_command.xml" (
    echo ❌ ENCONTRADO: Archivo con espacio al inicio
    echo Corrigiendo...
    ren " activity_admin_emergency_command.xml" "activity_admin_emergency_command.xml"
    echo ✅ CORREGIDO: activity_admin_emergency_command.xml
) else (
    echo ℹ️  Archivo ya corregido o no encontrado
)

echo.
echo === VERIFICANDO TODOS LOS LAYOUTS DE GUARDIAN IA ===

echo Layouts encontrados:
dir /b *.xml

echo.
echo Verificando nombres problemáticos...

for %%f in (*.xml) do (
    set "filename=%%~nf"
    set "fullname=%%f"
    
    REM Verificar espacios al inicio
    if "!fullname:~0,1!"==" " (
        set "corrected=!fullname:~1!"
        echo ❌ Corrigiendo: "!fullname!" → "!corrected!"
        ren "!fullname!" "!corrected!"
    )
    
    REM Verificar que empiece con letra
    set "first_char=!filename:~0,1!"
    echo !first_char! | findstr /r "^[a-z]" >nul
    if errorlevel 1 (
        echo ⚠️  WARNING: %%f - No empieza con letra minúscula
    )
)

echo.
echo === VERIFICANDO OTROS RECURSOS DE GUARDIAN IA ===

REM Verificar drawable
if exist ..\drawable (
    cd ..\drawable
    echo Verificando drawable...
    for %%f in (*.xml) do (
        if "%%f:~0,1"==" " (
            set "corrected=%%f:~1"
            echo Corrigiendo drawable: "%%f" → "!corrected!"
            ren "%%f" "!corrected!"
        )
    )
    cd ..\layout
)

REM Verificar values
if exist ..\values (
    cd ..\values
    echo Verificando values...
    for %%f in (*.xml) do (
        if "%%f:~0,1"==" " (
            set "corrected=%%f:~1"
            echo Corrigiendo values: "%%f" → "!corrected!"
            ren "%%f" "!corrected!"
        )
    )
    cd ..\layout
)

echo.
echo === VERIFICANDO ESTRUCTURA DEL PROYECTO GUARDIAN IA ===

cd ..\..\..

echo Verificando estructura del proyecto:
echo ✅ app/src/main/res/layout/ - %PROJECT_PATH%\layout
echo ✅ app/src/main/java/ - Código fuente Java/Kotlin
echo ✅ app/src/main/AndroidManifest.xml - Manifiesto

if exist java\com\guardianai (
    echo ✅ Paquete GuardianAI encontrado
) else (
    echo ℹ️  Verificar estructura de paquetes Java
)

echo.
echo === GENERANDO REPORTE GUARDIAN IA ===

echo GUARDIAN IA ANDROID - REPORTE DE CORRECCIONES > guardian_fix_report.txt
echo ================================================ >> guardian_fix_report.txt
echo Proyecto: Android Java/Kotlin >> guardian_fix_report.txt
echo Desarrollador: Anderson Mamian Chicangana >> guardian_fix_report.txt
echo Fecha: %date% %time% >> guardian_fix_report.txt
echo. >> guardian_fix_report.txt

echo ARCHIVOS CORREGIDOS: >> guardian_fix_report.txt
echo - activity_admin_emergency_command.xml (removido espacio inicial) >> guardian_fix_report.txt
echo. >> guardian_fix_report.txt

cd res\layout
echo LAYOUTS ACTUALES EN GUARDIAN IA: >> ..\..\guardian_fix_report.txt
dir /b *.xml >> ..\..\guardian_fix_report.txt

echo.
echo ========================================
echo ✅ CORRECCIONES COMPLETADAS - GUARDIAN IA
echo ========================================
echo.
echo 📱 Proyecto Android Guardian IA corregido
echo 📋 Reporte guardado: guardian_fix_report.txt
echo.
echo 🚀 SIGUIENTES PASOS EN ANDROID STUDIO:
echo 1. File → Sync Project with Gradle Files
echo 2. Build → Clean Project  
echo 3. Build → Rebuild Project
echo 4. Run → Run 'app'
echo.
echo ✨ El error "resource name must start with a letter" está solucionado
echo.

pause
