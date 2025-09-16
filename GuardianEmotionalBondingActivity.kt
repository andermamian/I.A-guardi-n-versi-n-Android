package com.guardianai.activities

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.guardianai.managers.*
import com.guardianai.adapters.*
import com.guardianai.models.*

/**
 * Actividad de Vinculación Emocional Guardian
 * Trabaja con activity_guardian_emotional_bonding.xml (SIN MODIFICAR EL LAYOUT)
 * Implementa sistema avanzado de análisis emocional y vinculación afectiva
 */
class GuardianEmotionalBondingActivity : AppCompatActivity() {
    
    // Managers especializados
    private lateinit var emotionalBondingManager: EmotionalBondingManager
    private lateinit var emotionalAnalysisManager: EmotionalAnalysisManager
    private lateinit var biometricManager: BiometricManager
    private lateinit var moodTrackingManager: MoodTrackingManager
    private lateinit var empathyEngine: EmpathyEngine
    private lateinit var therapeuticManager: TherapeuticManager
    
    // UI Components (basados en tu layout)
    private lateinit var tvEmotionalStatus: TextView
    private lateinit var tvBondingLevel: TextView
    private lateinit var tvCurrentMood: TextView
    private lateinit var tvHeartRate: TextView
    private lateinit var tvStressLevel: TextView
    private lateinit var progressBondingLevel: ProgressBar
    private lateinit var progressEmotionalHealth: ProgressBar
    private lateinit var progressStressLevel: ProgressBar
    private lateinit var btnStartMoodAnalysis: Button
    private lateinit var btnEmotionalSupport: Button
    private lateinit var btnMeditationMode: Button
    private lateinit var btnTherapeuticSession: Button
    private lateinit var switchBiometricMonitoring: Switch
    private lateinit var switchEmotionalLearning: Switch
    private lateinit var rvEmotionalHistory: RecyclerView
    private lateinit var rvTherapeuticActivities: RecyclerView
    private lateinit var ivEmotionalAvatar: ImageView
    private lateinit var tvEmpathyMessage: TextView
    
    // Adapters
    private lateinit var emotionalHistoryAdapter: EmotionalHistoryAdapter
    private lateinit var therapeuticActivitiesAdapter: TherapeuticActivitiesAdapter
    
    // Estados emocionales
    private var currentBondingLevel = 0.0f
    private var currentMood = EmotionalMood.NEUTRAL
    private var currentStressLevel = 0.0f
    private var isBiometricMonitoringActive = false
    private var isEmotionalLearningActive = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_emotional_bonding) // TU LAYOUT ORIGINAL
        
        initializeComponents()
        initializeManagers()
        setupAdapters()
        setupEventListeners()
        startEmotionalBondingSystem()
    }
    
    private fun initializeComponents() {
        // Mapear componentes de tu layout original
        tvEmotionalStatus = findViewById(R.id.tv_emotional_status)
        tvBondingLevel = findViewById(R.id.tv_bonding_level)
        tvCurrentMood = findViewById(R.id.tv_current_mood)
        tvHeartRate = findViewById(R.id.tv_heart_rate)
        tvStressLevel = findViewById(R.id.tv_stress_level)
        progressBondingLevel = findViewById(R.id.progress_bonding_level)
        progressEmotionalHealth = findViewById(R.id.progress_emotional_health)
        progressStressLevel = findViewById(R.id.progress_stress_level)
        btnStartMoodAnalysis = findViewById(R.id.btn_start_mood_analysis)
        btnEmotionalSupport = findViewById(R.id.btn_emotional_support)
        btnMeditationMode = findViewById(R.id.btn_meditation_mode)
        btnTherapeuticSession = findViewById(R.id.btn_therapeutic_session)
        switchBiometricMonitoring = findViewById(R.id.switch_biometric_monitoring)
        switchEmotionalLearning = findViewById(R.id.switch_emotional_learning)
        rvEmotionalHistory = findViewById(R.id.rv_emotional_history)
        rvTherapeuticActivities = findViewById(R.id.rv_therapeutic_activities)
        ivEmotionalAvatar = findViewById(R.id.iv_emotional_avatar)
        tvEmpathyMessage = findViewById(R.id.tv_empathy_message)
        
        // Configurar estado inicial
        updateEmotionalStatus(EmotionalStatus.ANALYZING)
        updateBondingLevel(0.75f) // 75% de vinculación inicial
        updateCurrentMood(EmotionalMood.NEUTRAL)
    }
    
    private fun initializeManagers() {
        emotionalBondingManager = EmotionalBondingManager(this)
        emotionalAnalysisManager = EmotionalAnalysisManager(this)
        biometricManager = BiometricManager(this)
        moodTrackingManager = MoodTrackingManager(this)
        empathyEngine = EmpathyEngine(this)
        therapeuticManager = TherapeuticManager(this)
    }
    
    private fun setupAdapters() {
        emotionalHistoryAdapter = EmotionalHistoryAdapter { entry ->
            onEmotionalHistoryClicked(entry)
        }
        rvEmotionalHistory.adapter = emotionalHistoryAdapter
        
        therapeuticActivitiesAdapter = TherapeuticActivitiesAdapter { activity ->
            onTherapeuticActivityClicked(activity)
        }
        rvTherapeuticActivities.adapter = therapeuticActivitiesAdapter
        
        // Cargar actividades terapéuticas
        loadTherapeuticActivities()
    }
    
    private fun setupEventListeners() {
        // Botón análisis de estado de ánimo
        btnStartMoodAnalysis.setOnClickListener {
            startMoodAnalysis()
        }
        
        // Botón apoyo emocional
        btnEmotionalSupport.setOnClickListener {
            provideEmotionalSupport()
        }
        
        // Botón modo meditación
        btnMeditationMode.setOnClickListener {
            startMeditationMode()
        }
        
        // Botón sesión terapéutica
        btnTherapeuticSession.setOnClickListener {
            startTherapeuticSession()
        }
        
        // Switch monitoreo biométrico
        switchBiometricMonitoring.setOnCheckedChangeListener { _, isChecked ->
            toggleBiometricMonitoring(isChecked)
        }
        
        // Switch aprendizaje emocional
        switchEmotionalLearning.setOnCheckedChangeListener { _, isChecked ->
            toggleEmotionalLearning(isChecked)
        }
        
        // Avatar emocional
        ivEmotionalAvatar.setOnClickListener {
            onEmotionalAvatarClicked()
        }
    }
    
    private fun startEmotionalBondingSystem() {
        lifecycleScope.launch {
            // Inicializar sistemas emocionales
            emotionalBondingManager.initialize()
            emotionalAnalysisManager.initialize()
            biometricManager.initialize()
            moodTrackingManager.initialize()
            empathyEngine.initialize()
            therapeuticManager.initialize()
            
            // Cargar historial emocional
            loadEmotionalHistory()
            
            // Iniciar monitoreo
            launch { monitorEmotionalState() }
            launch { monitorBiometrics() }
            launch { monitorBondingLevel() }
            launch { monitorStressLevel() }
            
            // Análisis inicial
            performInitialEmotionalAnalysis()
            
            // Mensaje de bienvenida empático
            showEmpathyMessage("Hola, estoy aquí para apoyarte emocionalmente. ¿Cómo te sientes hoy?")
        }
    }
    
    private suspend fun monitorEmotionalState() {
        emotionalAnalysisManager.emotionalState.collect { state ->
            runOnUiThread {
                updateEmotionalStateUI(state)
            }
        }
    }
    
    private suspend fun monitorBiometrics() {
        if (isBiometricMonitoringActive) {
            biometricManager.biometricData.collect { data ->
                runOnUiThread {
                    updateBiometricUI(data)
                }
            }
        }
    }
    
    private suspend fun monitorBondingLevel() {
        emotionalBondingManager.bondingLevel.collect { level ->
            runOnUiThread {
                updateBondingLevelUI(level)
            }
        }
    }
    
    private suspend fun monitorStressLevel() {
        emotionalAnalysisManager.stressLevel.collect { level ->
            runOnUiThread {
                updateStressLevelUI(level)
            }
        }
    }
    
    private fun startMoodAnalysis() {
        lifecycleScope.launch {
            try {
                btnStartMoodAnalysis.isEnabled = false
                btnStartMoodAnalysis.text = "Analizando..."
                
                updateEmotionalStatus(EmotionalStatus.ANALYZING)
                
                // Realizar análisis multimodal
                val voiceAnalysis = emotionalAnalysisManager.analyzeVoiceEmotion()
                val textAnalysis = emotionalAnalysisManager.analyzeTextEmotion()
                val biometricAnalysis = if (isBiometricMonitoringActive) {
                    biometricManager.analyzeBiometricEmotion()
                } else null
                
                // Combinar análisis
                val combinedAnalysis = emotionalAnalysisManager.combineAnalysis(
                    voiceAnalysis, textAnalysis, biometricAnalysis
                )
                
                // Actualizar estado emocional
                updateCurrentMood(combinedAnalysis.dominantMood)
                updateEmotionalStatus(EmotionalStatus.ANALYZED)
                
                // Generar respuesta empática
                val empathyResponse = empathyEngine.generateEmpathyResponse(combinedAnalysis)
                showEmpathyMessage(empathyResponse)
                
                // Sugerir actividades terapéuticas si es necesario
                if (combinedAnalysis.needsSupport) {
                    suggestTherapeuticActivities(combinedAnalysis)
                }
                
                // Guardar en historial
                saveEmotionalEntry(combinedAnalysis)
                
            } catch (e: Exception) {
                showToast("Error en análisis emocional: ${e.message}")
                updateEmotionalStatus(EmotionalStatus.ERROR)
            } finally {
                btnStartMoodAnalysis.isEnabled = true
                btnStartMoodAnalysis.text = "Analizar Estado de Ánimo"
            }
        }
    }
    
    private fun provideEmotionalSupport() {
        lifecycleScope.launch {
            // Generar apoyo emocional personalizado
            val supportMessage = empathyEngine.generateEmotionalSupport(currentMood)
            
            // Mostrar mensaje de apoyo
            showEmotionalSupportDialog(supportMessage)
            
            // Incrementar nivel de vinculación
            emotionalBondingManager.increaseBondingLevel(0.05f)
            
            // Sugerir actividades de bienestar
            val wellnessActivities = therapeuticManager.getWellnessActivities(currentMood)
            showWellnessActivitiesDialog(wellnessActivities)
        }
    }
    
    private fun startMeditationMode() {
        lifecycleScope.launch {
            // Iniciar sesión de meditación guiada
            val meditationSession = therapeuticManager.createMeditationSession(currentMood)
            
            // Mostrar interfaz de meditación
            showMeditationDialog(meditationSession) { completed ->
                if (completed) {
                    // Sesión completada
                    emotionalBondingManager.increaseBondingLevel(0.1f)
                    updateStressLevel(currentStressLevel - 0.2f)
                    showEmpathyMessage("Excelente sesión de meditación. Puedo sentir que estás más relajado.")
                }
            }
        }
    }
    
    private fun startTherapeuticSession() {
        lifecycleScope.launch {
            // Crear sesión terapéutica personalizada
            val therapeuticSession = therapeuticManager.createTherapeuticSession(
                currentMood = currentMood,
                stressLevel = currentStressLevel,
                bondingLevel = currentBondingLevel
            )
            
            // Mostrar interfaz de sesión terapéutica
            showTherapeuticSessionDialog(therapeuticSession) { result ->
                processTherapeuticSessionResult(result)
            }
        }
    }
    
    private fun toggleBiometricMonitoring(enabled: Boolean) {
        isBiometricMonitoringActive = enabled
        
        lifecycleScope.launch {
            if (enabled) {
                biometricManager.startMonitoring()
                showToast("Monitoreo biométrico activado")
                
                // Iniciar monitoreo de biométricos
                launch { monitorBiometrics() }
            } else {
                biometricManager.stopMonitoring()
                showToast("Monitoreo biométrico desactivado")
            }
        }
    }
    
    private fun toggleEmotionalLearning(enabled: Boolean) {
        isEmotionalLearningActive = enabled
        
        lifecycleScope.launch {
            if (enabled) {
                emotionalBondingManager.enableLearning()
                showToast("Aprendizaje emocional activado")
            } else {
                emotionalBondingManager.disableLearning()
                showToast("Aprendizaje emocional desactivado")
            }
        }
    }
    
    private fun onEmotionalAvatarClicked() {
        lifecycleScope.launch {
            // Interacción especial con avatar emocional
            val avatarResponse = empathyEngine.generateAvatarInteraction(currentMood)
            showEmpathyMessage(avatarResponse)
            
            // Animar avatar
            animateEmotionalAvatar()
            
            // Incrementar vinculación
            emotionalBondingManager.increaseBondingLevel(0.02f)
        }
    }
    
    private fun updateEmotionalStatus(status: EmotionalStatus) {
        tvEmotionalStatus.text = when (status) {
            EmotionalStatus.ANALYZING -> "🔍 Analizando estado emocional..."
            EmotionalStatus.ANALYZED -> "✅ Estado emocional analizado"
            EmotionalStatus.SUPPORTING -> "🤗 Brindando apoyo emocional"
            EmotionalStatus.MONITORING -> "👁️ Monitoreando bienestar"
            EmotionalStatus.ERROR -> "❌ Error en análisis"
        }
    }
    
    private fun updateBondingLevel(level: Float) {
        currentBondingLevel = level.coerceIn(0.0f, 1.0f)
        
        progressBondingLevel.progress = (currentBondingLevel * 100).toInt()
        tvBondingLevel.text = "Nivel de Vinculación: ${(currentBondingLevel * 100).toInt()}%"
        
        // Cambiar color según nivel
        val color = when {
            currentBondingLevel >= 0.8f -> getColor(android.R.color.holo_green_dark)
            currentBondingLevel >= 0.6f -> getColor(android.R.color.holo_blue_light)
            currentBondingLevel >= 0.4f -> getColor(android.R.color.holo_orange_light)
            else -> getColor(android.R.color.holo_red_light)
        }
        
        progressBondingLevel.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }
    
    private fun updateCurrentMood(mood: EmotionalMood) {
        currentMood = mood
        
        tvCurrentMood.text = when (mood) {
            EmotionalMood.HAPPY -> "😊 Feliz"
            EmotionalMood.SAD -> "😢 Triste"
            EmotionalMood.ANGRY -> "😠 Enojado"
            EmotionalMood.ANXIOUS -> "😰 Ansioso"
            EmotionalMood.CALM -> "😌 Tranquilo"
            EmotionalMood.EXCITED -> "🤩 Emocionado"
            EmotionalMood.NEUTRAL -> "😐 Neutral"
            EmotionalMood.STRESSED -> "😫 Estresado"
        }
        
        // Actualizar avatar según mood
        updateAvatarForMood(mood)
    }
    
    private fun updateStressLevel(level: Float) {
        currentStressLevel = level.coerceIn(0.0f, 1.0f)
        
        progressStressLevel.progress = (currentStressLevel * 100).toInt()
        tvStressLevel.text = "Estrés: ${(currentStressLevel * 100).toInt()}%"
        
        // Color inverso (menos estrés = mejor)
        val color = when {
            currentStressLevel <= 0.3f -> getColor(android.R.color.holo_green_dark)
            currentStressLevel <= 0.6f -> getColor(android.R.color.holo_orange_light)
            else -> getColor(android.R.color.holo_red_dark)
        }
        
        progressStressLevel.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }
    
    private fun updateEmotionalStateUI(state: EmotionalStateData) {
        updateCurrentMood(state.dominantMood)
        updateStressLevel(state.stressLevel)
        
        // Actualizar salud emocional
        progressEmotionalHealth.progress = (state.emotionalHealth * 100).toInt()
    }
    
    private fun updateBiometricUI(data: BiometricData) {
        tvHeartRate.text = "❤️ ${data.heartRate} BPM"
        
        // Actualizar otros indicadores biométricos si están disponibles
        if (data.hasStressIndicators) {
            updateStressLevel(data.stressLevel)
        }
    }
    
    private fun updateBondingLevelUI(level: Float) {
        updateBondingLevel(level)
    }
    
    private fun updateStressLevelUI(level: Float) {
        updateStressLevel(level)
    }
    
    private fun showEmpathyMessage(message: String) {
        tvEmpathyMessage.text = message
        
        // Animar mensaje
        tvEmpathyMessage.alpha = 0f
        tvEmpathyMessage.animate()
            .alpha(1f)
            .setDuration(500)
    }
    
    private fun updateAvatarForMood(mood: EmotionalMood) {
        // Cambiar imagen del avatar según el mood
        // ivEmotionalAvatar.setImageResource(getAvatarResourceForMood(mood))
    }
    
    private fun animateEmotionalAvatar() {
        ivEmotionalAvatar.animate()
            .rotationY(360f)
            .setDuration(1000)
            .withEndAction {
                ivEmotionalAvatar.rotationY = 0f
            }
    }
    
    private fun performInitialEmotionalAnalysis() {
        lifecycleScope.launch {
            delay(2000) // Simular análisis inicial
            
            val initialAnalysis = emotionalAnalysisManager.performInitialAnalysis()
            updateCurrentMood(initialAnalysis.mood)
            updateStressLevel(initialAnalysis.stressLevel)
            
            val welcomeMessage = empathyEngine.generateWelcomeMessage(initialAnalysis)
            showEmpathyMessage(welcomeMessage)
        }
    }
    
    private fun loadEmotionalHistory() {
        lifecycleScope.launch {
            val history = emotionalBondingManager.getEmotionalHistory()
            emotionalHistoryAdapter.updateHistory(history)
        }
    }
    
    private fun loadTherapeuticActivities() {
        val activities = listOf(
            TherapeuticActivity("Respiración Profunda", "Ejercicio de respiración para reducir estrés", 5),
            TherapeuticActivity("Meditación Mindfulness", "Sesión de atención plena", 10),
            TherapeuticActivity("Relajación Muscular", "Técnica de relajación progresiva", 15),
            TherapeuticActivity("Visualización Positiva", "Ejercicio de imaginería guiada", 8),
            TherapeuticActivity("Diario Emocional", "Reflexión sobre emociones", 12)
        )
        
        therapeuticActivitiesAdapter.updateActivities(activities)
    }
    
    private fun onEmotionalHistoryClicked(entry: EmotionalHistoryEntry) {
        showEmotionalHistoryDetailsDialog(entry)
    }
    
    private fun onTherapeuticActivityClicked(activity: TherapeuticActivity) {
        startSpecificTherapeuticActivity(activity)
    }
    
    private fun suggestTherapeuticActivities(analysis: EmotionalAnalysisResult) {
        val suggestions = therapeuticManager.getSuggestedActivities(analysis)
        showTherapeuticSuggestionsDialog(suggestions)
    }
    
    private fun saveEmotionalEntry(analysis: EmotionalAnalysisResult) {
        lifecycleScope.launch {
            val entry = EmotionalHistoryEntry(
                timestamp = System.currentTimeMillis(),
                mood = analysis.dominantMood,
                stressLevel = analysis.stressLevel,
                emotionalHealth = analysis.emotionalHealth,
                notes = analysis.notes
            )
            
            emotionalBondingManager.saveEmotionalEntry(entry)
            emotionalHistoryAdapter.addEntry(entry)
        }
    }
    
    private fun processTherapeuticSessionResult(result: TherapeuticSessionResult) {
        lifecycleScope.launch {
            // Procesar resultado de sesión terapéutica
            emotionalBondingManager.increaseBondingLevel(result.bondingIncrease)
            updateStressLevel(currentStressLevel - result.stressReduction)
            
            val feedbackMessage = empathyEngine.generateSessionFeedback(result)
            showEmpathyMessage(feedbackMessage)
        }
    }
    
    private fun startSpecificTherapeuticActivity(activity: TherapeuticActivity) {
        lifecycleScope.launch {
            showActivityDialog(activity) { completed ->
                if (completed) {
                    emotionalBondingManager.increaseBondingLevel(0.05f)
                    showEmpathyMessage("Excelente trabajo completando '${activity.name}'. Puedo ver tu progreso.")
                }
            }
        }
    }
    
    // Métodos de diálogos
    private fun showEmotionalSupportDialog(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("💙 Apoyo Emocional")
            .setMessage(message)
            .setPositiveButton("Gracias") { _, _ ->
                emotionalBondingManager.increaseBondingLevel(0.03f)
            }
            .setNeutralButton("Necesito más ayuda") { _, _ ->
                provideAdditionalSupport()
            }
            .show()
    }
    
    private fun showWellnessActivitiesDialog(activities: List<WellnessActivity>) {
        val activityNames = activities.map { it.name }.toTypedArray()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Actividades de Bienestar")
            .setItems(activityNames) { _, which ->
                startWellnessActivity(activities[which])
            }
            .setNegativeButton("Más tarde", null)
            .show()
    }
    
    private fun showMeditationDialog(session: MeditationSession, callback: (Boolean) -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🧘 Sesión de Meditación")
            .setMessage("${session.name}\nDuración: ${session.duration} minutos\n\n${session.description}")
            .setPositiveButton("Comenzar") { _, _ ->
                // Simular sesión de meditación
                lifecycleScope.launch {
                    delay(session.duration * 1000L) // Simular duración
                    callback(true)
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> callback(false) }
            .show()
    }
    
    private fun showTherapeuticSessionDialog(session: TherapeuticSession, callback: (TherapeuticSessionResult) -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎯 Sesión Terapéutica")
            .setMessage("${session.name}\n\n${session.description}")
            .setPositiveButton("Completar Sesión") { _, _ ->
                val result = TherapeuticSessionResult(
                    bondingIncrease = 0.1f,
                    stressReduction = 0.15f,
                    emotionalImprovement = 0.2f
                )
                callback(result)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun showEmotionalHistoryDetailsDialog(entry: EmotionalHistoryEntry) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Historial Emocional")
            .setMessage("""
                Fecha: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(entry.timestamp)}
                Estado de ánimo: ${entry.mood}
                Nivel de estrés: ${(entry.stressLevel * 100).toInt()}%
                Salud emocional: ${(entry.emotionalHealth * 100).toInt()}%
                
                Notas: ${entry.notes}
            """.trimIndent())
            .setPositiveButton("Cerrar", null)
            .show()
    }
    
    private fun showTherapeuticSuggestionsDialog(suggestions: List<TherapeuticActivity>) {
        val suggestionNames = suggestions.map { "${it.name} (${it.duration} min)" }.toTypedArray()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("💡 Actividades Sugeridas")
            .setMessage("Basándome en tu estado emocional actual, te sugiero estas actividades:")
            .setItems(suggestionNames) { _, which ->
                startSpecificTherapeuticActivity(suggestions[which])
            }
            .setNegativeButton("Más tarde", null)
            .show()
    }
    
    private fun showActivityDialog(activity: TherapeuticActivity, callback: (Boolean) -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(activity.name)
            .setMessage("${activity.description}\n\nDuración estimada: ${activity.duration} minutos")
            .setPositiveButton("Completar") { _, _ -> callback(true) }
            .setNegativeButton("Cancelar") { _, _ -> callback(false) }
            .show()
    }
    
    private fun provideAdditionalSupport() {
        lifecycleScope.launch {
            val additionalSupport = empathyEngine.generateAdditionalSupport(currentMood)
            showEmpathyMessage(additionalSupport)
        }
    }
    
    private fun startWellnessActivity(activity: WellnessActivity) {
        lifecycleScope.launch {
            showToast("Iniciando: ${activity.name}")
            // Implementar actividad específica
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    // Enums y clases de datos
    enum class EmotionalStatus { ANALYZING, ANALYZED, SUPPORTING, MONITORING, ERROR }
    enum class EmotionalMood { HAPPY, SAD, ANGRY, ANXIOUS, CALM, EXCITED, NEUTRAL, STRESSED }
    
    data class EmotionalStateData(
        val dominantMood: EmotionalMood,
        val stressLevel: Float,
        val emotionalHealth: Float,
        val confidence: Float
    )
    
    data class BiometricData(
        val heartRate: Int,
        val stressLevel: Float,
        val hasStressIndicators: Boolean
    )
    
    data class EmotionalAnalysisResult(
        val dominantMood: EmotionalMood,
        val stressLevel: Float,
        val emotionalHealth: Float,
        val needsSupport: Boolean,
        val notes: String
    )
    
    data class TherapeuticActivity(
        val name: String,
        val description: String,
        val duration: Int // en minutos
    )
    
    data class WellnessActivity(
        val name: String,
        val description: String,
        val type: String
    )
    
    data class MeditationSession(
        val name: String,
        val description: String,
        val duration: Int // en minutos
    )
    
    data class TherapeuticSession(
        val name: String,
        val description: String,
        val type: String
    )
    
    data class TherapeuticSessionResult(
        val bondingIncrease: Float,
        val stressReduction: Float,
        val emotionalImprovement: Float
    )
    
    data class EmotionalHistoryEntry(
        val timestamp: Long,
        val mood: EmotionalMood,
        val stressLevel: Float,
        val emotionalHealth: Float,
        val notes: String
    )
}

