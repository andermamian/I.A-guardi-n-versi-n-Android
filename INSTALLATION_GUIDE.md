# 🚀 Guía Completa de Instalación - Guardian IA

## 📋 Tabla de Contenidos

1. [Requisitos Previos](#requisitos-previos)
2. [Preparación del Entorno](#preparación-del-entorno)
3. [Instalación Paso a Paso](#instalación-paso-a-paso)
4. [Configuración Inicial](#configuración-inicial)
5. [Verificación de la Instalación](#verificación-de-la-instalación)
6. [Solución de Problemas](#solución-de-problemas)
7. [Configuración Avanzada](#configuración-avanzada)

## 🔧 Requisitos Previos

### Hardware Mínimo
- **Dispositivo Android** con API 26+ (Android 8.0)
- **RAM**: 4 GB mínimo (8 GB recomendado)
- **Almacenamiento**: 64 GB mínimo (128 GB recomendado)
- **Procesador**: Snapdragon 660 o equivalente
- **Sensores**: Acelerómetro, Giroscopio, Magnetómetro
- **Conectividad**: WiFi, Bluetooth, GPS

### Hardware Recomendado
- **Dispositivo Android** con API 31+ (Android 12.0)
- **RAM**: 8 GB o superior
- **Almacenamiento**: 256 GB o superior
- **Procesador**: Snapdragon 888 o superior
- **Sensores**: Biométricos (huella, facial)
- **Conectividad**: 5G, WiFi 6, Bluetooth 5.0

### Software de Desarrollo
- **Android Studio**: Arctic Fox (2020.3.1) o superior
- **JDK**: OpenJDK 11 o superior
- **Gradle**: 7.0 o superior
- **Kotlin**: 1.7.0 o superior
- **Git**: Para control de versiones

## 🛠️ Preparación del Entorno

### 1. Instalación de Android Studio

#### Windows
```bash
# Descargar desde https://developer.android.com/studio
# Ejecutar el instalador
# Seguir el asistente de configuración
```

#### macOS
```bash
# Descargar desde https://developer.android.com/studio
# Arrastrar a la carpeta Applications
# Ejecutar y seguir configuración
```

#### Linux (Ubuntu/Debian)
```bash
# Instalar dependencias
sudo apt update
sudo apt install openjdk-11-jdk

# Descargar Android Studio
wget https://redirector.gvt1.com/edgedl/android/studio/ide-zips/2022.1.1.21/android-studio-2022.1.1.21-linux.tar.gz

# Extraer
tar -xzf android-studio-*-linux.tar.gz

# Ejecutar
cd android-studio/bin
./studio.sh
```

### 2. Configuración del SDK de Android

```bash
# En Android Studio, ir a:
# File → Settings → Appearance & Behavior → System Settings → Android SDK

# Instalar las siguientes versiones:
# - Android 13 (API 33) - Recomendado
# - Android 12 (API 31) - Recomendado  
# - Android 8.0 (API 26) - Mínimo

# Instalar herramientas:
# - Android SDK Build-Tools 33.0.0
# - Android Emulator
# - Android SDK Platform-Tools
# - Intel x86 Emulator Accelerator (HAXM)
```

### 3. Configuración de Variables de Entorno

#### Windows
```cmd
# Agregar al PATH:
set ANDROID_HOME=C:\Users\%USERNAME%\AppData\Local\Android\Sdk
set PATH=%PATH%;%ANDROID_HOME%\tools;%ANDROID_HOME%\platform-tools
```

#### macOS/Linux
```bash
# Agregar al ~/.bashrc o ~/.zshrc:
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/tools/bin
export PATH=$PATH:$ANDROID_HOME/emulator

# Recargar configuración
source ~/.bashrc  # o ~/.zshrc
```

## 📥 Instalación Paso a Paso

### 1. Obtener el Código Fuente

#### Opción A: Clonar desde Git
```bash
# Clonar el repositorio
git clone https://github.com/usuario/guardian-ia.git
cd guardian-ia

# Verificar la rama
git branch -a
git checkout main
```

#### Opción B: Descargar ZIP
```bash
# Descargar desde GitHub
# Extraer el archivo ZIP
# Navegar al directorio extraído
```

### 2. Configuración del Proyecto

#### Verificar Estructura del Proyecto
```
guardian-ia/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/guardianai/
│   │   │   ├── res/
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

#### Configurar gradle.properties
```properties
# Configuración de Gradle
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true

# Configuración de Android
android.useAndroidX=true
android.enableJetifier=true

# Configuración de Kotlin
kotlin.code.style=official

# Configuración de Guardian IA
guardian.debug.enabled=true
guardian.ai.model.path=/assets/models/
guardian.security.level=HIGH
```

### 3. Configuración de Dependencias

#### build.gradle.kts (Project)
```kotlin
buildscript {
    ext.kotlin_version = "1.7.20"
    ext.compose_version = "1.3.0"
    
    dependencies {
        classpath "com.android.tools.build:gradle:7.3.1"
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
        classpath "dagger.hilt.android:hilt-android-gradle-plugin:2.44"
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

#### build.gradle.kts (Module: app)
```kotlin
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'kotlin-kapt'
    id 'dagger.hilt.android.plugin'
    id 'kotlin-parcelize'
}

android {
    namespace 'com.guardianai'
    compileSdk 33

    defaultConfig {
        applicationId "com.guardianai"
        minSdk 26
        targetSdk 33
        versionCode 1
        versionName "3.0.0"

        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
        
        vectorDrawables {
            useSupportLibrary true
        }
    }

    buildTypes {
        release {
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            
            buildConfigField "String", "AI_MODEL_VERSION", '"v3.0"'
            buildConfigField "boolean", "DEBUG_MODE", "false"
        }
        debug {
            applicationIdSuffix ".debug"
            debuggable true
            
            buildConfigField "String", "AI_MODEL_VERSION", '"v3.0-debug"'
            buildConfigField "boolean", "DEBUG_MODE", "true"
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = '11'
    }
    
    buildFeatures {
        compose true
        viewBinding true
        dataBinding true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion compose_version
    }
    
    packagingOptions {
        resources {
            excludes += '/META-INF/{AL2.0,LGPL2.1}'
        }
    }
}

dependencies {
    // Core Android
    implementation 'androidx.core:core-ktx:1.9.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.6.2'
    implementation 'androidx.activity:activity-compose:1.8.0'
    implementation 'androidx.fragment:fragment-ktx:1.5.4'
    
    // UI Components
    implementation "androidx.compose.ui:ui:$compose_version"
    implementation "androidx.compose.ui:ui-tooling-preview:$compose_version"
    implementation 'androidx.compose.material3:material3:1.0.1'
    implementation 'androidx.recyclerview:recyclerview:1.2.1'
    implementation 'com.google.android.material:material:1.7.0'
    
    // Navigation
    implementation 'androidx.navigation:navigation-fragment-ktx:2.5.3'
    implementation 'androidx.navigation:navigation-ui-ktx:2.5.3'
    implementation 'androidx.navigation:navigation-compose:2.5.3'
    
    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4'
    
    // Dependency Injection
    implementation "com.google.dagger:hilt-android:2.44"
    kapt "com.google.dagger:hilt-compiler:2.44"
    
    // Networking
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
    implementation 'com.squareup.okhttp3:logging-interceptor:4.10.0'
    
    // Database
    implementation 'androidx.room:room-runtime:2.4.3'
    implementation 'androidx.room:room-ktx:2.4.3'
    kapt 'androidx.room:room-compiler:2.4.3'
    
    // Security
    implementation 'androidx.biometric:biometric:1.1.0'
    implementation 'androidx.security:security-crypto:1.1.0-alpha04'
    
    // Media & Audio
    implementation 'androidx.media3:media3-exoplayer:1.0.0'
    implementation 'androidx.media3:media3-ui:1.0.0'
    implementation 'com.github.adrielcafe:AndroidAudioConverter:0.0.8'
    
    // Machine Learning
    implementation 'org.tensorflow:tensorflow-lite:2.10.0'
    implementation 'org.tensorflow:tensorflow-lite-gpu:2.10.0'
    implementation 'org.tensorflow:tensorflow-lite-support:0.4.2'
    
    // Image Processing
    implementation 'com.github.bumptech.glide:glide:4.14.2'
    implementation 'androidx.camera:camera-camera2:1.2.0'
    implementation 'androidx.camera:camera-lifecycle:1.2.0'
    implementation 'androidx.camera:camera-view:1.2.0'
    
    // Charts & Visualization
    implementation 'com.github.PhilJay:MPAndroidChart:v3.1.0'
    implementation 'com.github.AnyChart:AnyChart-Android:1.1.2'
    
    // Permissions
    implementation 'com.karumi:dexter:6.2.3'
    
    // Testing
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.mockito:mockito-core:4.8.0'
    testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4'
    
    androidTestImplementation 'androidx.test.ext:junit:1.1.4'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.0'
    androidTestImplementation "androidx.compose.ui:ui-test-junit4:$compose_version"
    
    debugImplementation "androidx.compose.ui:ui-tooling:$compose_version"
    debugImplementation "androidx.compose.ui:ui-test-manifest:$compose_version"
}
```

### 4. Configuración de Permisos

#### AndroidManifest.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Permisos de Internet y Red -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />

    <!-- Permisos de Ubicación -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

    <!-- Permisos de Cámara y Micrófono -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <!-- Permisos de Almacenamiento -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
        tools:ignore="ScopedStorage" />

    <!-- Permisos de Biometría -->
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />
    <uses-permission android:name="android.permission.USE_FINGERPRINT" />

    <!-- Permisos de Sistema -->
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

    <!-- Permisos de Contactos y Teléfono -->
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <uses-permission android:name="android.permission.CALL_PHONE" />
    <uses-permission android:name="android.permission.SEND_SMS" />

    <!-- Características requeridas -->
    <uses-feature
        android:name="android.hardware.camera"
        android:required="true" />
    <uses-feature
        android:name="android.hardware.camera.autofocus"
        android:required="false" />
    <uses-feature
        android:name="android.hardware.microphone"
        android:required="true" />
    <uses-feature
        android:name="android.hardware.location.gps"
        android:required="false" />

    <application
        android:name=".GuardianApplication"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.GuardianIA"
        android:hardwareAccelerated="true"
        android:largeHeap="true"
        tools:targetApi="31">

        <!-- Actividad Principal -->
        <activity
            android:name=".activities.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.GuardianIA.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Todas las demás actividades -->
        <activity android:name=".activities.GuardianMainInterfaceActivity" />
        <activity android:name=".activities.GuardianProtectionCenterActivity" />
        <activity android:name=".activities.GuardianCommunicationHubActivity" />
        <activity android:name=".activities.GuardianEmotionalBondingActivity" />
        <activity android:name=".activities.GuardianPersonalityDesignerActivity" />
        <activity android:name=".activities.GuardianMusicCreatorActivity" />
        <activity android:name=".activities.AICommunicationActivity" />
        <activity android:name=".activities.GuardianAdminDashboardActivity" />
        <activity android:name=".activities.GuardianAntiTheftActivity" />
        <activity android:name=".activities.GuardianConfigurationCenterActivity" />
        <activity android:name=".activities.GuardianRealTimeMonitoringActivity" />

        <!-- Servicios -->
        <service
            android:name=".services.GuardianBackgroundService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="location|camera|microphone" />

        <service
            android:name=".services.GuardianAIService"
            android:enabled="true"
            android:exported="false" />

        <!-- Receivers -->
        <receiver
            android:name=".receivers.BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter android:priority="1000">
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.PACKAGE_REPLACED" />
                <data android:scheme="package" />
            </intent-filter>
        </receiver>

        <!-- Providers -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

    </application>

</manifest>
```

## 🔧 Configuración Inicial

### 1. Compilación del Proyecto

```bash
# Navegar al directorio del proyecto
cd guardian-ia

# Limpiar proyecto
./gradlew clean

# Compilar en modo debug
./gradlew assembleDebug

# Compilar en modo release
./gradlew assembleRelease
```

### 2. Configuración de Emulador

#### Crear AVD (Android Virtual Device)
```bash
# Listar AVDs disponibles
avdmanager list avd

# Crear nuevo AVD
avdmanager create avd \
    -n "Guardian_IA_Test" \
    -k "system-images;android-33;google_apis;x86_64" \
    -d "pixel_4"

# Iniciar emulador
emulator -avd Guardian_IA_Test
```

#### Configuración Recomendada del Emulador
- **API Level**: 33 (Android 13)
- **RAM**: 4 GB mínimo
- **Almacenamiento**: 8 GB
- **GPU**: Hardware acelerado
- **Cámara**: Webcam (para testing)

### 3. Instalación en Dispositivo

```bash
# Verificar dispositivos conectados
adb devices

# Instalar APK debug
adb install app/build/outputs/apk/debug/app-debug.apk

# Instalar APK release
adb install app/build/outputs/apk/release/app-release.apk

# Desinstalar si es necesario
adb uninstall com.guardianai
```

## ✅ Verificación de la Instalación

### 1. Verificación Básica

#### Checklist de Instalación
- [ ] Aplicación se instala sin errores
- [ ] Aplicación se inicia correctamente
- [ ] Pantalla de bienvenida aparece
- [ ] Permisos se solicitan apropiadamente
- [ ] Configuración inicial se completa

#### Comandos de Verificación
```bash
# Verificar instalación
adb shell pm list packages | grep guardianai

# Verificar permisos
adb shell dumpsys package com.guardianai | grep permission

# Verificar logs
adb logcat | grep GuardianIA
```

### 2. Verificación de Funcionalidades

#### Test de Módulos Principales
```bash
# Test de comunicación IA
adb shell am start -n com.guardianai/.activities.AICommunicationActivity

# Test de protección
adb shell am start -n com.guardianai/.activities.GuardianProtectionCenterActivity

# Test de monitoreo
adb shell am start -n com.guardianai/.activities.GuardianRealTimeMonitoringActivity
```

#### Test de Permisos
```bash
# Test de cámara
adb shell am start -a android.media.action.IMAGE_CAPTURE

# Test de micrófono
adb shell am start -a android.provider.MediaStore.RECORD_SOUND

# Test de ubicación
adb shell am start -a android.intent.action.VIEW -d "geo:0,0?q=test"
```

### 3. Verificación de Rendimiento

#### Métricas de Rendimiento
```bash
# Uso de memoria
adb shell dumpsys meminfo com.guardianai

# Uso de CPU
adb shell top | grep guardianai

# Uso de batería
adb shell dumpsys batterystats | grep guardianai
```

## 🔧 Solución de Problemas

### Problemas Comunes

#### 1. Error de Compilación
```bash
# Problema: Dependencias no resueltas
# Solución:
./gradlew clean
./gradlew build --refresh-dependencies

# Problema: Versión de Gradle incompatible
# Solución: Actualizar gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-7.5-bin.zip
```

#### 2. Error de Permisos
```bash
# Problema: Permisos denegados
# Solución: Verificar en AndroidManifest.xml y solicitar en runtime

# Problema: Permiso de almacenamiento
# Solución: Agregar en AndroidManifest.xml
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

#### 3. Error de Memoria
```bash
# Problema: OutOfMemoryError
# Solución: Aumentar heap en gradle.properties
org.gradle.jvmargs=-Xmx8192m -Dfile.encoding=UTF-8

# En AndroidManifest.xml
android:largeHeap="true"
```

#### 4. Error de Red
```bash
# Problema: Network Security Config
# Solución: Crear network_security_config.xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

### Logs de Depuración

#### Habilitar Logs Detallados
```kotlin
// En Application class
if (BuildConfig.DEBUG) {
    Timber.plant(Timber.DebugTree())
}

// En código
Timber.d("Guardian IA: Iniciando módulo %s", moduleName)
Timber.e(exception, "Error en Guardian IA")
```

#### Filtrar Logs
```bash
# Logs de Guardian IA solamente
adb logcat | grep "GuardianIA"

# Logs de errores
adb logcat *:E | grep "GuardianIA"

# Logs en tiempo real
adb logcat -v time | grep "GuardianIA"
```

## ⚙️ Configuración Avanzada

### 1. Configuración de Modelos de IA

#### Ubicación de Modelos
```
app/src/main/assets/models/
├── personality_model.tflite
├── emotion_detection.tflite
├── speech_recognition.tflite
├── music_generation.tflite
└── security_analysis.tflite
```

#### Configuración de TensorFlow Lite
```kotlin
// En GuardianAIManager
private fun initializeModels() {
    val options = Interpreter.Options().apply {
        setNumThreads(4)
        setUseGPU(true)
    }
    
    personalityModel = Interpreter(loadModelFile("personality_model.tflite"), options)
    emotionModel = Interpreter(loadModelFile("emotion_detection.tflite"), options)
}
```

### 2. Configuración de Base de Datos

#### Schema de Room
```kotlin
@Database(
    entities = [
        UserProfile::class,
        ConversationHistory::class,
        SecurityEvent::class,
        SystemMetrics::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class GuardianDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun securityDao(): SecurityDao
    abstract fun metricsDao(): MetricsDao
}
```

### 3. Configuración de Seguridad

#### Configuración de Encriptación
```kotlin
// En SecurityManager
private fun initializeEncryption() {
    val keyGenParameterSpec = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .setUserAuthenticationRequired(true)
    .setUserAuthenticationValidityDurationSeconds(300)
    .build()
}
```

### 4. Configuración de Servicios en Background

#### Configuración de WorkManager
```kotlin
// En GuardianApplication
private fun setupBackgroundWork() {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    val workRequest = PeriodicWorkRequestBuilder<GuardianBackgroundWorker>(
        15, TimeUnit.MINUTES
    )
    .setConstraints(constraints)
    .build()

    WorkManager.getInstance(this).enqueueUniquePeriodicWork(
        "guardian_background_work",
        ExistingPeriodicWorkPolicy.KEEP,
        workRequest
    )
}
```

## 📊 Monitoreo Post-Instalación

### Métricas de Salud del Sistema
- **Tiempo de inicio**: < 3 segundos
- **Uso de memoria**: < 500 MB en idle
- **Uso de CPU**: < 10% en idle
- **Uso de batería**: < 5% por hora
- **Tiempo de respuesta IA**: < 2 segundos

### Alertas Automáticas
- **Crash de aplicación**
- **Uso excesivo de memoria**
- **Fallos de red**
- **Errores de permisos**
- **Problemas de rendimiento**

---

**¡Instalación Completada!** 🎉

Para soporte adicional, consulte la [documentación completa](README.md) o contacte al equipo de soporte.

