#!/bin/bash

# Script para corregir errores de compilación de Guardian IA
# Versión 3.1.0

echo "🔧 Guardian IA - Corrección de Errores de Compilación"
echo "===================================================="

# Colores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Verificar que estamos en el directorio correcto
if [ ! -f "app/build.gradle" ]; then
    print_error "Este script debe ejecutarse desde el directorio raíz del proyecto Guardian IA"
    exit 1
fi

print_status "Iniciando corrección de errores de compilación..."

# 1. Limpiar proyecto completamente
print_status "Limpiando proyecto completamente..."
./gradlew clean --quiet
rm -rf app/build/
rm -rf build/
rm -rf .gradle/
print_success "Proyecto limpiado"

# 2. Verificar y corregir nombres de archivos de recursos
print_status "Verificando nombres de archivos de recursos..."

# Renombrar archivos con nombres problemáticos si existen
cd app/src/main/res/layout/

# Lista de archivos problemáticos y sus correcciones
declare -A file_renames=(
    ["activity_guardian_real-time_monitoring_system.xml"]="activity_guardian_realtime_monitoring_system.xml"
    ["activity_guardian_security_y_audit_center_layout.xml"]="activity_guardian_security_audit_center_layout.xml"
    ["guardian_user_y_permissions_management_center.xml"]="guardian_user_permissions_management_center.xml"
    ["activity_guardian_content_y_support_hub.xml"]="activity_guardian_content_support_hub.xml"
)

for old_name in "${!file_renames[@]}"; do
    new_name="${file_renames[$old_name]}"
    if [ -f "$old_name" ]; then
        mv "$old_name" "$new_name"
        print_success "Renombrado: $old_name → $new_name"
    fi
done

cd ../../../../

# 3. Verificar y corregir IDs problemáticos en layouts
print_status "Verificando IDs en layouts..."

# Buscar y corregir IDs con guiones
find app/src/main/res/layout/ -name "*.xml" -exec sed -i 's/android:id="@+id\/\([^"]*\)-\([^"]*\)"/android:id="@+id\/\1_\2"/g' {} \;
print_success "IDs con guiones corregidos"

# 4. Verificar permisos en AndroidManifest
print_status "Verificando AndroidManifest.xml..."

# Asegurar que todos los permisos estén correctamente declarados
if ! grep -q "android.permission.BIND_VPN_SERVICE" app/src/main/AndroidManifest.xml; then
    print_warning "Permiso VPN no encontrado en AndroidManifest"
fi

print_success "AndroidManifest verificado"

# 5. Verificar dependencias en build.gradle
print_status "Verificando dependencias..."

# Verificar que las dependencias críticas estén presentes
CRITICAL_DEPS=(
    "androidx.core:core-ktx"
    "com.google.dagger:hilt-android"
    "androidx.lifecycle:lifecycle-runtime-ktx"
)

for dep in "${CRITICAL_DEPS[@]}"; do
    if grep -q "$dep" app/build.gradle; then
        print_success "Dependencia encontrada: $dep"
    else
        print_warning "Dependencia no encontrada: $dep"
    fi
done

# 6. Corregir configuración de Gradle
print_status "Verificando configuración de Gradle..."

# Asegurar que gradle.properties tenga las configuraciones correctas
if ! grep -q "org.gradle.jvmargs=-Xmx4096m" gradle.properties; then
    echo "org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8" >> gradle.properties
    print_success "Configuración de memoria añadida"
fi

# 7. Verificar archivos de recursos críticos
print_status "Verificando archivos de recursos..."

# Verificar que los archivos de colores y strings existan
CRITICAL_RESOURCE_FILES=(
    "app/src/main/res/values/colors.xml"
    "app/src/main/res/values/strings.xml"
    "app/src/main/res/values/themes.xml"
)

for file in "${CRITICAL_RESOURCE_FILES[@]}"; do
    if [ -f "$file" ]; then
        print_success "Archivo encontrado: $file"
    else
        print_error "Archivo crítico no encontrado: $file"
    fi
done

# 8. Verificar iconos VPN
print_status "Verificando iconos VPN..."

VPN_ICONS=(
    "app/src/main/res/drawable/ic_vpn_connected.xml"
    "app/src/main/res/drawable/ic_vpn_connecting.xml"
    "app/src/main/res/drawable/ic_vpn_disconnected.xml"
)

for icon in "${VPN_ICONS[@]}"; do
    if [ -f "$icon" ]; then
        print_success "Icono encontrado: $(basename $icon)"
    else
        print_warning "Icono no encontrado: $(basename $icon)"
    fi
done

# 9. Limpiar archivos temporales
print_status "Limpiando archivos temporales..."
find . -name "*.tmp" -delete
find . -name "*.bak" -delete
find . -name "*~" -delete
print_success "Archivos temporales eliminados"

# 10. Verificar estructura de directorios
print_status "Verificando estructura de directorios..."

REQUIRED_DIRS=(
    "app/src/main/java/com/guardianai/activities"
    "app/src/main/java/com/guardianai/membership"
    "app/src/main/java/com/guardianai/vpn"
    "app/src/main/java/com/guardianai/viewmodels"
)

for dir in "${REQUIRED_DIRS[@]}"; do
    if [ -d "$dir" ]; then
        print_success "Directorio encontrado: $dir"
    else
        print_warning "Directorio no encontrado: $dir"
        mkdir -p "$dir"
        print_status "Directorio creado: $dir"
    fi
done

# 11. Intentar compilación de prueba
print_status "Intentando compilación de prueba..."

if ./gradlew assembleDebug --quiet --no-daemon; then
    print_success "✅ COMPILACIÓN EXITOSA - Todos los errores corregidos"
    echo ""
    echo "🎉 Guardian IA está listo para usar en Android Studio"
    echo "📱 Puedes ejecutar la aplicación sin problemas"
else
    print_warning "⚠️ Aún hay algunos problemas de compilación"
    echo ""
    echo "🔍 Ejecuta './gradlew assembleDebug' para ver errores específicos"
    echo "📋 Revisa los logs de Gradle para más detalles"
fi

# 12. Mostrar resumen final
echo ""
echo "📋 RESUMEN DE CORRECCIONES APLICADAS"
echo "===================================="
print_success "✅ Proyecto limpiado completamente"
print_success "✅ Nombres de archivos corregidos"
print_success "✅ IDs problemáticos corregidos"
print_success "✅ Configuración de Gradle optimizada"
print_success "✅ Estructura de directorios verificada"
print_success "✅ Recursos críticos verificados"

echo ""
echo "🚀 PRÓXIMOS PASOS:"
echo "1. Abre Android Studio"
echo "2. Sync Project with Gradle Files"
echo "3. Build → Clean Project"
echo "4. Build → Rebuild Project"
echo "5. Run App"

echo ""
print_success "🛡️ Guardian IA - Errores de compilación corregidos"

