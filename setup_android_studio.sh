#!/bin/bash

# Script de configuración para Guardian IA - Android Studio
# Versión 3.1.0

echo "🛡️ Guardian IA - Configuración de Android Studio"
echo "=================================================="

# Colores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Función para imprimir mensajes con colores
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

# Verificar si estamos en el directorio correcto
if [ ! -f "app/build.gradle" ]; then
    print_error "Este script debe ejecutarse desde el directorio raíz del proyecto Guardian IA"
    exit 1
fi

print_status "Iniciando configuración de Guardian IA..."

# 1. Verificar versión de Java
print_status "Verificando versión de Java..."
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -n1 | cut -d'"' -f2)
    print_success "Java encontrado: $JAVA_VERSION"
else
    print_error "Java no encontrado. Por favor instala Java 11 o superior."
    exit 1
fi

# 2. Verificar Android SDK
print_status "Verificando Android SDK..."
if [ -n "$ANDROID_HOME" ]; then
    print_success "ANDROID_HOME configurado: $ANDROID_HOME"
else
    print_warning "ANDROID_HOME no está configurado. Configúralo en Android Studio."
fi

# 3. Limpiar proyecto
print_status "Limpiando proyecto..."
if command -v ./gradlew &> /dev/null; then
    ./gradlew clean
    print_success "Proyecto limpiado"
else
    print_warning "Gradle wrapper no encontrado. Usando gradle del sistema..."
    if command -v gradle &> /dev/null; then
        gradle clean
        print_success "Proyecto limpiado con gradle del sistema"
    else
        print_error "Gradle no encontrado"
    fi
fi

# 4. Verificar dependencias
print_status "Verificando dependencias del proyecto..."

# Verificar si las dependencias críticas están en build.gradle
CRITICAL_DEPS=(
    "androidx.core:core-ktx"
    "com.google.dagger:hilt-android"
    "com.android.billingclient:billing"
    "org.tensorflow:tensorflow-lite"
    "androidx.room:room-runtime"
)

for dep in "${CRITICAL_DEPS[@]}"; do
    if grep -q "$dep" app/build.gradle; then
        print_success "Dependencia encontrada: $dep"
    else
        print_warning "Dependencia no encontrada: $dep"
    fi
done

# 5. Configurar archivos de configuración
print_status "Configurando archivos de proyecto..."

# Crear local.properties si no existe
if [ ! -f "local.properties" ]; then
    print_status "Creando local.properties..."
    if [ -n "$ANDROID_HOME" ]; then
        echo "sdk.dir=$ANDROID_HOME" > local.properties
        print_success "local.properties creado"
    else
        print_warning "No se pudo crear local.properties - ANDROID_HOME no configurado"
    fi
fi

# 6. Configurar permisos de Gradle
print_status "Configurando permisos de Gradle..."
if [ -f "gradlew" ]; then
    chmod +x gradlew
    print_success "Permisos de gradlew configurados"
fi

# 7. Verificar estructura de directorios
print_status "Verificando estructura de directorios..."

REQUIRED_DIRS=(
    "app/src/main/java/com/guardianai"
    "app/src/main/res/layout"
    "app/src/main/res/values"
    "app/src/main/res/drawable"
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

# 8. Verificar archivos críticos
print_status "Verificando archivos críticos..."

CRITICAL_FILES=(
    "app/src/main/AndroidManifest.xml"
    "app/build.gradle"
    "build.gradle"
    "settings.gradle"
    "gradle.properties"
)

for file in "${CRITICAL_FILES[@]}"; do
    if [ -f "$file" ]; then
        print_success "Archivo encontrado: $file"
    else
        print_error "Archivo crítico no encontrado: $file"
    fi
done

# 9. Configurar IDE settings (si Android Studio está instalado)
print_status "Configurando settings de IDE..."

# Crear directorio .idea si no existe
if [ ! -d ".idea" ]; then
    mkdir -p .idea
fi

# Configurar compiler.xml para optimizar compilación
cat > .idea/compiler.xml << EOF
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="CompilerConfiguration">
    <bytecodeTargetLevel target="11" />
  </component>
</project>
EOF

print_success "Configuración de compilador actualizada"

# 10. Crear script de build optimizado
print_status "Creando script de build optimizado..."

cat > build_guardian_ia.sh << 'EOF'
#!/bin/bash
echo "🛡️ Compilando Guardian IA..."

# Configurar variables de entorno para optimización
export GRADLE_OPTS="-Xmx4g -XX:MaxPermSize=512m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"

# Limpiar y compilar
./gradlew clean assembleDebug --parallel --daemon --configure-on-demand

echo "✅ Compilación completada"
EOF

chmod +x build_guardian_ia.sh
print_success "Script de build creado: build_guardian_ia.sh"

# 11. Crear script de testing
print_status "Creando script de testing..."

cat > test_guardian_ia.sh << 'EOF'
#!/bin/bash
echo "🧪 Ejecutando tests de Guardian IA..."

# Ejecutar tests unitarios
./gradlew testDebugUnitTest

# Ejecutar tests de instrumentación (si hay dispositivo conectado)
if adb devices | grep -q "device$"; then
    echo "📱 Dispositivo detectado, ejecutando tests de instrumentación..."
    ./gradlew connectedDebugAndroidTest
else
    echo "⚠️ No hay dispositivos conectados para tests de instrumentación"
fi

echo "✅ Tests completados"
EOF

chmod +x test_guardian_ia.sh
print_success "Script de testing creado: test_guardian_ia.sh"

# 12. Verificar configuración final
print_status "Verificando configuración final..."

# Intentar sincronizar proyecto
print_status "Sincronizando proyecto con Gradle..."
if ./gradlew tasks > /dev/null 2>&1; then
    print_success "Sincronización exitosa"
else
    print_warning "Problemas en la sincronización. Revisa los logs de Gradle."
fi

# 13. Mostrar resumen
echo ""
echo "📋 RESUMEN DE CONFIGURACIÓN"
echo "=========================="
print_success "✅ Proyecto Guardian IA configurado correctamente"
print_status "📁 Directorio del proyecto: $(pwd)"
print_status "🔧 Versión del proyecto: 3.1.0"
print_status "📱 Target SDK: 34"
print_status "🎯 Min SDK: 26"

echo ""
echo "🚀 PRÓXIMOS PASOS:"
echo "1. Abre Android Studio"
echo "2. Selecciona 'Open an existing project'"
echo "3. Navega a: $(pwd)"
echo "4. Espera a que se complete la sincronización"
echo "5. Ejecuta el proyecto con Ctrl+R (o Cmd+R en Mac)"

echo ""
echo "📚 SCRIPTS DISPONIBLES:"
echo "• ./build_guardian_ia.sh - Compilar proyecto optimizado"
echo "• ./test_guardian_ia.sh - Ejecutar tests"
echo "• ./gradlew assembleDebug - Compilar versión debug"
echo "• ./gradlew assembleRelease - Compilar versión release"

echo ""
print_success "🎉 ¡Configuración completada! Guardian IA está listo para desarrollo."

# 14. Verificar si hay actualizaciones disponibles
print_status "Verificando actualizaciones de dependencias..."
if ./gradlew dependencyUpdates > /dev/null 2>&1; then
    print_status "Ejecuta './gradlew dependencyUpdates' para ver actualizaciones disponibles"
fi

echo ""
echo "🛡️ Guardian IA - Protegiendo el futuro digital con IA"
echo "=================================================="

