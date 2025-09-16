# 📚 Documentación de API - Guardian IA

## 🌟 Introducción

Esta documentación describe la API interna del sistema Guardian IA, incluyendo todos los managers, servicios, y interfaces disponibles para el desarrollo y extensión del sistema.

## 📋 Tabla de Contenidos

1. [Arquitectura General](#arquitectura-general)
2. [Managers Principales](#managers-principales)
3. [Servicios del Sistema](#servicios-del-sistema)
4. [Modelos de Datos](#modelos-de-datos)
5. [Interfaces y Callbacks](#interfaces-y-callbacks)
6. [Ejemplos de Uso](#ejemplos-de-uso)
7. [Extensibilidad](#extensibilidad)

## 🏗️ Arquitectura General

### Patrón de Diseño
```
┌─────────────────────────────────────────┐
│              UI Layer                   │
│  (Activities, Fragments, Composables)  │
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│            ViewModel Layer              │
│     (ViewModels, UI State)             │
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│           Manager Layer                 │
│  (Business Logic, Orchestration)       │
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│          Repository Layer               │
│    (Data Access, Caching)              │
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│           Data Layer                    │
│  (Database, Network, Files)            │
└─────────────────────────────────────────┘
```

### Inyección de Dependencias
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object GuardianModule {
    
    @Provides
    @Singleton
    fun provideGuardianSystemOrchestrator(
        context: Context
    ): GuardianSystemOrchestrator = GuardianSystemOrchestrator(context)
    
    @Provides
    @Singleton
    fun provideSecurityManager(
        context: Context
    ): SecurityManager = SecurityManager(context)
}
```

## 🎯 Managers Principales

### 1. GuardianSystemOrchestrator

**Descripción**: Coordinador principal del sistema Guardian IA.

#### Métodos Principales
```kotlin
class GuardianSystemOrchestrator @Inject constructor(
    private val context: Context
) {
    
    /**
     * Inicializa todo el sistema Guardian
     */
    suspend fun initialize(): SystemInitializationResult
    
    /**
     * Activa el sistema Guardian
     */
    suspend fun activateGuardianSystem(): ActivationResult
    
    /**
     * Desactiva el sistema Guardian
     */
    suspend fun deactivateGuardianSystem(): DeactivationResult
    
    /**
     * Habilita el modo automático
     */
    suspend fun enableAutoMode(): Result<Unit>
    
    /**
     * Deshabilita el modo automático
     */
    suspend fun disableAutoMode(): Result<Unit>
    
    /**
     * Verifica el estado del sistema
     */
    suspend fun checkSystemState(): SystemState
    
    /**
     * Resuelve problemas del sistema automáticamente
     */
    suspend fun resolveSystemIssues(): ResolutionResult
    
    /**
     * Genera interacción con el avatar
     */
    suspend fun generateAvatarInteraction(): GuardianInteractionResponse
    
    /**
     * Genera mensaje de bienvenida personalizado
     */
    suspend fun generatePersonalizedWelcome(): PersonalizedWelcomeMessage
    
    /**
     * Carga configuración del sistema
     */
    suspend fun loadSystemConfiguration(): SystemConfiguration
    
    /**
     * Guarda configuración del sistema
     */
    suspend fun saveSystemConfiguration(config: SystemConfiguration): Result<Unit>
}
```

#### Ejemplo de Uso
```kotlin
class MainActivity : AppCompatActivity() {
    
    @Inject
    lateinit var orchestrator: GuardianSystemOrchestrator
    
    private fun initializeGuardian() {
        lifecycleScope.launch {
            try {
                val result = orchestrator.initialize()
                if (result.success) {
                    orchestrator.activateGuardianSystem()
                    updateUI(SystemStatus.ACTIVE)
                } else {
                    handleInitializationError(result.error)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error initializing Guardian")
            }
        }
    }
}
```

### 2. SecurityManager

**Descripción**: Gestiona toda la seguridad del sistema.

#### Métodos Principales
```kotlin
class SecurityManager @Inject constructor(
    private val context: Context,
    private val encryptionService: EncryptionService,
    private val biometricService: BiometricService
) {
    
    /**
     * Inicializa el sistema de seguridad
     */
    suspend fun initialize(): SecurityInitializationResult
    
    /**
     * Autentica al usuario usando biometría
     */
    suspend fun authenticateUser(): AuthenticationResult
    
    /**
     * Encripta datos sensibles
     */
    suspend fun encryptData(data: ByteArray): EncryptionResult
    
    /**
     * Desencripta datos
     */
    suspend fun decryptData(encryptedData: ByteArray): DecryptionResult
    
    /**
     * Detecta amenazas en tiempo real
     */
    fun detectThreats(): Flow<ThreatDetectionResult>
    
    /**
     * Configura nivel de seguridad
     */
    suspend fun setSecurityLevel(level: SecurityLevel): Result<Unit>
    
    /**
     * Genera reporte de seguridad
     */
    suspend fun generateSecurityReport(): SecurityReport
    
    /**
     * Responde a incidente de seguridad
     */
    suspend fun respondToSecurityIncident(incident: SecurityIncident): ResponseResult
}
```

### 3. PersonalityManager

**Descripción**: Gestiona la personalidad de la IA Guardian.

#### Métodos Principales
```kotlin
class PersonalityManager @Inject constructor(
    private val aiEngine: AIEngine,
    private val personalityRepository: PersonalityRepository
) {
    
    /**
     * Carga personalidad actual
     */
    suspend fun loadPersonality(): PersonalityProfile
    
    /**
     * Actualiza personalidad
     */
    suspend fun updatePersonality(profile: PersonalityProfile): Result<Unit>
    
    /**
     * Genera respuesta basada en personalidad
     */
    suspend fun generatePersonalizedResponse(
        input: String,
        context: ConversationContext
    ): PersonalizedResponse
    
    /**
     * Evoluciona personalidad basada en interacciones
     */
    suspend fun evolvePersonality(interactions: List<Interaction>): EvolutionResult
    
    /**
     * Obtiene rasgos de personalidad disponibles
     */
    suspend fun getAvailableTraits(): List<PersonalityTrait>
    
    /**
     * Aplica preset de personalidad
     */
    suspend fun applyPersonalityPreset(preset: PersonalityPreset): Result<Unit>
    
    /**
     * Calcula compatibilidad con usuario
     */
    suspend fun calculateCompatibility(userProfile: UserProfile): CompatibilityScore
}
```

### 4. CommunicationManager

**Descripción**: Gestiona toda la comunicación del sistema.

#### Métodos Principales
```kotlin
class CommunicationManager @Inject constructor(
    private val aiEngine: AIEngine,
    private val speechService: SpeechService,
    private val nlpService: NLPService
) {
    
    /**
     * Procesa mensaje de entrada
     */
    suspend fun processMessage(
        message: String,
        context: ConversationContext
    ): ProcessingResult
    
    /**
     * Genera respuesta de IA
     */
    suspend fun generateResponse(
        input: ProcessedInput,
        personalityContext: PersonalityContext
    ): AIResponse
    
    /**
     * Convierte texto a voz
     */
    suspend fun textToSpeech(
        text: String,
        voiceSettings: VoiceSettings
    ): AudioResult
    
    /**
     * Convierte voz a texto
     */
    suspend fun speechToText(audioData: ByteArray): TranscriptionResult
    
    /**
     * Analiza sentimientos en texto
     */
    suspend fun analyzeSentiment(text: String): SentimentAnalysis
    
    /**
     * Obtiene historial de conversación
     */
    suspend fun getConversationHistory(limit: Int): List<ConversationEntry>
    
    /**
     * Guarda conversación
     */
    suspend fun saveConversation(entry: ConversationEntry): Result<Unit>
}
```

### 5. MonitoringManager

**Descripción**: Gestiona el monitoreo del sistema en tiempo real.

#### Métodos Principales
```kotlin
class MonitoringManager @Inject constructor(
    private val systemMetricsService: SystemMetricsService,
    private val alertService: AlertService
) {
    
    /**
     * Inicia monitoreo del sistema
     */
    suspend fun startMonitoring(): MonitoringResult
    
    /**
     * Detiene monitoreo del sistema
     */
    suspend fun stopMonitoring(): Result<Unit>
    
    /**
     * Obtiene métricas del sistema en tiempo real
     */
    fun getSystemMetrics(): Flow<SystemMetrics>
    
    /**
     * Obtiene alertas activas
     */
    fun getActiveAlerts(): Flow<List<SystemAlert>>
    
    /**
     * Configura umbral de alerta
     */
    suspend fun setAlertThreshold(
        metric: MetricType,
        threshold: Float
    ): Result<Unit>
    
    /**
     * Genera reporte de monitoreo
     */
    suspend fun generateMonitoringReport(
        period: TimePeriod
    ): MonitoringReport
    
    /**
     * Exporta datos de monitoreo
     */
    suspend fun exportMonitoringData(
        format: ExportFormat,
        period: TimePeriod
    ): ExportResult
}
```

## 🔧 Servicios del Sistema

### 1. GuardianBackgroundService

**Descripción**: Servicio en background para operaciones continuas.

```kotlin
class GuardianBackgroundService : Service() {
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        lifecycleScope.launch {
            // Monitoreo continuo
            monitorSystemHealth()
            monitorSecurityThreats()
            processBackgroundTasks()
        }
        
        return START_STICKY
    }
    
    private suspend fun monitorSystemHealth() {
        while (isActive) {
            val health = systemHealthService.checkHealth()
            if (health.hasIssues) {
                notificationManager.showHealthAlert(health)
            }
            delay(30_000) // Check every 30 seconds
        }
    }
}
```

### 2. GuardianAIService

**Descripción**: Servicio dedicado para operaciones de IA.

```kotlin
class GuardianAIService : Service() {
    
    private val aiEngine = AIEngine()
    
    override fun onCreate() {
        super.onCreate()
        initializeAIModels()
    }
    
    private suspend fun initializeAIModels() {
        aiEngine.loadModel("personality_model.tflite")
        aiEngine.loadModel("emotion_detection.tflite")
        aiEngine.loadModel("speech_recognition.tflite")
    }
    
    fun processAIRequest(request: AIRequest): AIResponse {
        return when (request.type) {
            AIRequestType.TEXT_PROCESSING -> processText(request.data)
            AIRequestType.EMOTION_ANALYSIS -> analyzeEmotion(request.data)
            AIRequestType.SPEECH_SYNTHESIS -> synthesizeSpeech(request.data)
            else -> AIResponse.error("Unknown request type")
        }
    }
}
```

## 📊 Modelos de Datos

### Core Models

```kotlin
/**
 * Perfil de usuario del sistema
 */
@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey val id: String,
    val name: String,
    val preferences: UserPreferences,
    val biometricData: BiometricData?,
    val personalityCompatibility: Float,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Configuración de personalidad de IA
 */
@Entity(tableName = "personality_profiles")
data class PersonalityProfile(
    @PrimaryKey val id: String,
    val name: String,
    val traits: Map<PersonalityTrait, Float>,
    val learningRate: Float,
    val adaptabilityLevel: Float,
    val emotionalIntelligence: Float,
    val creativityLevel: Float,
    val protectivenessLevel: Float
)

/**
 * Entrada de conversación
 */
@Entity(tableName = "conversations")
data class ConversationEntry(
    @PrimaryKey val id: String,
    val userId: String,
    val message: String,
    val response: String,
    val sentiment: SentimentScore,
    val context: ConversationContext,
    val timestamp: Long
)

/**
 * Evento de seguridad
 */
@Entity(tableName = "security_events")
data class SecurityEvent(
    @PrimaryKey val id: String,
    val type: SecurityEventType,
    val severity: SecuritySeverity,
    val description: String,
    val source: String,
    val resolved: Boolean,
    val timestamp: Long,
    val metadata: Map<String, Any>
)

/**
 * Métricas del sistema
 */
@Entity(tableName = "system_metrics")
data class SystemMetrics(
    @PrimaryKey val id: String,
    val cpuUsage: Float,
    val memoryUsage: Float,
    val diskUsage: Float,
    val networkUsage: Float,
    val batteryLevel: Float,
    val temperature: Float,
    val timestamp: Long
)
```

### Response Models

```kotlin
/**
 * Resultado de respuesta de IA
 */
data class AIResponse(
    val success: Boolean,
    val message: String,
    val confidence: Float,
    val emotionalTone: EmotionalTone,
    val suggestions: List<String>,
    val metadata: Map<String, Any>
) {
    companion object {
        fun success(message: String, confidence: Float = 1.0f) = AIResponse(
            success = true,
            message = message,
            confidence = confidence,
            emotionalTone = EmotionalTone.NEUTRAL,
            suggestions = emptyList(),
            metadata = emptyMap()
        )
        
        fun error(message: String) = AIResponse(
            success = false,
            message = message,
            confidence = 0.0f,
            emotionalTone = EmotionalTone.CONCERNED,
            suggestions = emptyList(),
            metadata = emptyMap()
        )
    }
}

/**
 * Resultado de autenticación
 */
data class AuthenticationResult(
    val success: Boolean,
    val method: AuthenticationMethod,
    val confidence: Float,
    val userId: String?,
    val error: String?
)

/**
 * Resultado de detección de amenazas
 */
data class ThreatDetectionResult(
    val threatDetected: Boolean,
    val threatType: ThreatType,
    val severity: ThreatSeverity,
    val confidence: Float,
    val description: String,
    val recommendedActions: List<String>
)
```

## 🔌 Interfaces y Callbacks

### Core Interfaces

```kotlin
/**
 * Interface para listeners de eventos del sistema
 */
interface GuardianSystemListener {
    fun onSystemActivated()
    fun onSystemDeactivated()
    fun onSystemError(error: SystemError)
    fun onConfigurationChanged(config: SystemConfiguration)
}

/**
 * Interface para callbacks de IA
 */
interface AIResponseCallback {
    fun onResponseGenerated(response: AIResponse)
    fun onResponseError(error: AIError)
    fun onProcessingStarted()
    fun onProcessingCompleted()
}

/**
 * Interface para eventos de seguridad
 */
interface SecurityEventListener {
    fun onThreatDetected(threat: ThreatDetectionResult)
    fun onSecurityBreach(breach: SecurityBreach)
    fun onAuthenticationSuccess(result: AuthenticationResult)
    fun onAuthenticationFailure(result: AuthenticationResult)
}

/**
 * Interface para monitoreo del sistema
 */
interface SystemMonitoringListener {
    fun onMetricsUpdated(metrics: SystemMetrics)
    fun onAlertTriggered(alert: SystemAlert)
    fun onSystemHealthChanged(health: SystemHealth)
}
```

### Custom Callbacks

```kotlin
/**
 * Callback para operaciones asíncronas
 */
interface AsyncOperationCallback<T> {
    fun onSuccess(result: T)
    fun onError(error: Throwable)
    fun onProgress(progress: Float)
}

/**
 * Callback para personalización de IA
 */
interface PersonalityEvolutionCallback {
    fun onPersonalityEvolved(oldProfile: PersonalityProfile, newProfile: PersonalityProfile)
    fun onTraitChanged(trait: PersonalityTrait, oldValue: Float, newValue: Float)
    fun onCompatibilityUpdated(compatibility: CompatibilityScore)
}
```

## 💡 Ejemplos de Uso

### 1. Comunicación con IA

```kotlin
class AICommunicationViewModel @Inject constructor(
    private val communicationManager: CommunicationManager,
    private val personalityManager: PersonalityManager
) : ViewModel() {
    
    fun sendMessage(message: String) {
        viewModelScope.launch {
            try {
                // Procesar mensaje
                val processedInput = communicationManager.processMessage(
                    message = message,
                    context = getCurrentContext()
                )
                
                // Obtener personalidad actual
                val personality = personalityManager.loadPersonality()
                
                // Generar respuesta personalizada
                val response = communicationManager.generateResponse(
                    input = processedInput.data,
                    personalityContext = PersonalityContext(personality)
                )
                
                // Actualizar UI
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + ConversationMessage(
                        text = response.message,
                        isFromUser = false,
                        timestamp = System.currentTimeMillis()
                    )
                )
                
                // Evolucionar personalidad basada en interacción
                personalityManager.evolvePersonality(
                    listOf(Interaction(message, response.message))
                )
                
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }
}
```

### 2. Configuración de Seguridad

```kotlin
class SecurityConfigurationViewModel @Inject constructor(
    private val securityManager: SecurityManager
) : ViewModel() {
    
    fun configureSecurityLevel(level: SecurityLevel) {
        viewModelScope.launch {
            try {
                // Configurar nivel de seguridad
                val result = securityManager.setSecurityLevel(level)
                
                if (result.isSuccess) {
                    // Iniciar monitoreo de amenazas
                    securityManager.detectThreats()
                        .collect { threat ->
                            if (threat.threatDetected) {
                                handleThreatDetection(threat)
                            }
                        }
                } else {
                    handleSecurityError(result.exceptionOrNull())
                }
                
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }
    
    private fun handleThreatDetection(threat: ThreatDetectionResult) {
        viewModelScope.launch {
            // Responder automáticamente a la amenaza
            val response = securityManager.respondToSecurityIncident(
                SecurityIncident.fromThreat(threat)
            )
            
            // Notificar al usuario
            _securityAlerts.value = _securityAlerts.value + SecurityAlert(
                type = threat.threatType,
                message = threat.description,
                severity = threat.severity,
                timestamp = System.currentTimeMillis()
            )
        }
    }
}
```

### 3. Monitoreo del Sistema

```kotlin
class MonitoringViewModel @Inject constructor(
    private val monitoringManager: MonitoringManager
) : ViewModel() {
    
    private val _systemMetrics = MutableStateFlow<SystemMetrics?>(null)
    val systemMetrics: StateFlow<SystemMetrics?> = _systemMetrics.asStateFlow()
    
    fun startMonitoring() {
        viewModelScope.launch {
            try {
                // Iniciar monitoreo
                val result = monitoringManager.startMonitoring()
                
                if (result.success) {
                    // Recopilar métricas en tiempo real
                    monitoringManager.getSystemMetrics()
                        .collect { metrics ->
                            _systemMetrics.value = metrics
                            
                            // Verificar umbrales críticos
                            checkCriticalThresholds(metrics)
                        }
                }
                
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }
    
    private fun checkCriticalThresholds(metrics: SystemMetrics) {
        when {
            metrics.cpuUsage > 0.9f -> {
                triggerAlert(AlertType.HIGH_CPU_USAGE, metrics.cpuUsage)
            }
            metrics.memoryUsage > 0.85f -> {
                triggerAlert(AlertType.HIGH_MEMORY_USAGE, metrics.memoryUsage)
            }
            metrics.temperature > 80f -> {
                triggerAlert(AlertType.HIGH_TEMPERATURE, metrics.temperature)
            }
        }
    }
}
```

## 🔧 Extensibilidad

### Creando Managers Personalizados

```kotlin
/**
 * Manager personalizado para funcionalidades específicas
 */
@Singleton
class CustomFeatureManager @Inject constructor(
    private val context: Context,
    private val repository: CustomFeatureRepository
) {
    
    /**
     * Inicializa el manager personalizado
     */
    suspend fun initialize(): InitializationResult {
        return try {
            // Lógica de inicialización
            repository.loadConfiguration()
            InitializationResult.success()
        } catch (e: Exception) {
            InitializationResult.error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Método personalizado específico
     */
    suspend fun executeCustomOperation(params: CustomParams): CustomResult {
        // Implementación específica
        return repository.performOperation(params)
    }
}

/**
 * Módulo de Hilt para el manager personalizado
 */
@Module
@InstallIn(SingletonComponent::class)
object CustomFeatureModule {
    
    @Provides
    @Singleton
    fun provideCustomFeatureManager(
        context: Context,
        repository: CustomFeatureRepository
    ): CustomFeatureManager = CustomFeatureManager(context, repository)
}
```

### Extendiendo la Personalidad de IA

```kotlin
/**
 * Trait personalizado de personalidad
 */
enum class CustomPersonalityTrait : PersonalityTrait {
    HUMOR_LEVEL,
    TECHNICAL_EXPERTISE,
    CULTURAL_AWARENESS,
    PHILOSOPHICAL_DEPTH;
    
    override val displayName: String
        get() = when (this) {
            HUMOR_LEVEL -> "Nivel de Humor"
            TECHNICAL_EXPERTISE -> "Expertise Técnico"
            CULTURAL_AWARENESS -> "Conciencia Cultural"
            PHILOSOPHICAL_DEPTH -> "Profundidad Filosófica"
        }
}

/**
 * Extensión del PersonalityManager
 */
class ExtendedPersonalityManager @Inject constructor(
    private val baseManager: PersonalityManager,
    private val customTraitProcessor: CustomTraitProcessor
) {
    
    suspend fun applyCustomTrait(
        trait: CustomPersonalityTrait,
        value: Float
    ): Result<Unit> {
        return try {
            val currentProfile = baseManager.loadPersonality()
            val updatedProfile = currentProfile.copy(
                traits = currentProfile.traits + (trait to value)
            )
            
            baseManager.updatePersonality(updatedProfile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

### Creando Servicios Personalizados

```kotlin
/**
 * Servicio personalizado para funcionalidades específicas
 */
class CustomBackgroundService : Service() {
    
    @Inject
    lateinit var customManager: CustomFeatureManager
    
    override fun onCreate() {
        super.onCreate()
        DaggerServiceComponent.create().inject(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(CUSTOM_NOTIFICATION_ID, createCustomNotification())
        
        lifecycleScope.launch {
            // Operaciones personalizadas en background
            performCustomBackgroundTasks()
        }
        
        return START_STICKY
    }
    
    private suspend fun performCustomBackgroundTasks() {
        while (isActive) {
            try {
                val result = customManager.executeCustomOperation(
                    CustomParams.default()
                )
                
                if (result.requiresUserAttention) {
                    sendCustomNotification(result)
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Error in custom background task")
            }
            
            delay(60_000) // Execute every minute
        }
    }
}
```

## 📈 Métricas y Analytics

### Recopilación de Métricas

```kotlin
/**
 * Servicio de analytics personalizado
 */
@Singleton
class GuardianAnalyticsService @Inject constructor(
    private val context: Context
) {
    
    /**
     * Registra evento de uso
     */
    fun trackUsageEvent(event: UsageEvent) {
        // Implementación de tracking
        analyticsRepository.recordEvent(event)
    }
    
    /**
     * Registra métricas de rendimiento
     */
    fun trackPerformanceMetrics(metrics: PerformanceMetrics) {
        // Implementación de métricas
        metricsRepository.recordMetrics(metrics)
    }
    
    /**
     * Genera reporte de analytics
     */
    suspend fun generateAnalyticsReport(period: TimePeriod): AnalyticsReport {
        return analyticsRepository.generateReport(period)
    }
}
```

---

**Documentación de API Completa** 📚

Para más información y ejemplos avanzados, consulte la [documentación completa](README.md) del proyecto.

