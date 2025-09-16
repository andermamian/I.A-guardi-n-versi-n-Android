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
 * Actividad del Diseñador de Personalidad Guardian
 * Trabaja con activity_guardian_personality_designer.xml (SIN MODIFICAR EL LAYOUT)
 * Implementa sistema avanzado de personalización de IA y adaptación de personalidad
 */
class GuardianPersonalityDesignerActivity : AppCompatActivity() {
    
    // Managers especializados
    private lateinit var personalityDesignerManager: PersonalityDesignerManager
    private lateinit var aiPersonalityManager: AIPersonalityManager
    private lateinit var behaviorAnalysisManager: BehaviorAnalysisManager
    private lateinit var learningEngine: LearningEngine
    private lateinit var personalityTraitsManager: PersonalityTraitsManager
    private lateinit var adaptationEngine: AdaptationEngine
    
    // UI Components (basados en tu layout)
    private lateinit var tvCurrentPersonality: TextView
    private lateinit var tvPersonalityDescription: TextView
    private lateinit var tvAdaptationLevel: TextView
    private lateinit var tvLearningProgress: TextView
    private lateinit var progressPersonalityMatch: ProgressBar
    private lateinit var progressLearningProgress: ProgressBar
    private lateinit var progressAdaptationLevel: ProgressBar
    private lateinit var spinnerPersonalityType: Spinner
    private lateinit var seekBarEmpathy: SeekBar
    private lateinit var seekBarProtectiveness: SeekBar
    private lateinit var seekBarPlayfulness: SeekBar
    private lateinit var seekBarFormality: SeekBar
    private lateinit var seekBarProactiveness: SeekBar
    private lateinit var switchAdaptiveLearning: Switch
    private lateinit var switchBehaviorAnalysis: Switch
    private lateinit var switchPersonalityEvolution: Switch
    private lateinit var btnApplyPersonality: Button
    private lateinit var btnResetToDefault: Button
    private lateinit var btnCreateCustom: Button
    private lateinit var btnTestPersonality: Button
    private lateinit var rvPersonalityPresets: RecyclerView
    private lateinit var rvPersonalityHistory: RecyclerView
    private lateinit var ivPersonalityAvatar: ImageView
    
    // Adapters
    private lateinit var personalityPresetsAdapter: PersonalityPresetsAdapter
    private lateinit var personalityHistoryAdapter: PersonalityHistoryAdapter
    
    // Estados de personalidad
    private var currentPersonalityType = PersonalityType.GUARDIAN
    private var personalityTraits = PersonalityTraits()
    private var isAdaptiveLearningEnabled = true
    private var isBehaviorAnalysisEnabled = true
    private var isPersonalityEvolutionEnabled = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_personality_designer) // TU LAYOUT ORIGINAL
        
        initializeComponents()
        initializeManagers()
        setupAdapters()
        setupEventListeners()
        startPersonalityDesignSystem()
    }
    
    private fun initializeComponents() {
        // Mapear componentes de tu layout original
        tvCurrentPersonality = findViewById(R.id.tv_current_personality)
        tvPersonalityDescription = findViewById(R.id.tv_personality_description)
        tvAdaptationLevel = findViewById(R.id.tv_adaptation_level)
        tvLearningProgress = findViewById(R.id.tv_learning_progress)
        progressPersonalityMatch = findViewById(R.id.progress_personality_match)
        progressLearningProgress = findViewById(R.id.progress_learning_progress)
        progressAdaptationLevel = findViewById(R.id.progress_adaptation_level)
        spinnerPersonalityType = findViewById(R.id.spinner_personality_type)
        seekBarEmpathy = findViewById(R.id.seekbar_empathy)
        seekBarProtectiveness = findViewById(R.id.seekbar_protectiveness)
        seekBarPlayfulness = findViewById(R.id.seekbar_playfulness)
        seekBarFormality = findViewById(R.id.seekbar_formality)
        seekBarProactiveness = findViewById(R.id.seekbar_proactiveness)
        switchAdaptiveLearning = findViewById(R.id.switch_adaptive_learning)
        switchBehaviorAnalysis = findViewById(R.id.switch_behavior_analysis)
        switchPersonalityEvolution = findViewById(R.id.switch_personality_evolution)
        btnApplyPersonality = findViewById(R.id.btn_apply_personality)
        btnResetToDefault = findViewById(R.id.btn_reset_to_default)
        btnCreateCustom = findViewById(R.id.btn_create_custom)
        btnTestPersonality = findViewById(R.id.btn_test_personality)
        rvPersonalityPresets = findViewById(R.id.rv_personality_presets)
        rvPersonalityHistory = findViewById(R.id.rv_personality_history)
        ivPersonalityAvatar = findViewById(R.id.iv_personality_avatar)
        
        // Configurar estado inicial
        updateCurrentPersonality(PersonalityType.GUARDIAN)
        setupPersonalitySpinner()
        setupSeekBars()
    }
    
    private fun initializeManagers() {
        personalityDesignerManager = PersonalityDesignerManager(this)
        aiPersonalityManager = AIPersonalityManager(this)
        behaviorAnalysisManager = BehaviorAnalysisManager(this)
        learningEngine = LearningEngine(this)
        personalityTraitsManager = PersonalityTraitsManager(this)
        adaptationEngine = AdaptationEngine(this)
    }
    
    private fun setupAdapters() {
        personalityPresetsAdapter = PersonalityPresetsAdapter { preset ->
            onPersonalityPresetClicked(preset)
        }
        rvPersonalityPresets.adapter = personalityPresetsAdapter
        
        personalityHistoryAdapter = PersonalityHistoryAdapter { entry ->
            onPersonalityHistoryClicked(entry)
        }
        rvPersonalityHistory.adapter = personalityHistoryAdapter
        
        // Cargar presets de personalidad
        loadPersonalityPresets()
    }
    
    private fun setupEventListeners() {
        // Spinner de tipo de personalidad
        spinnerPersonalityType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedType = PersonalityType.values()[position]
                onPersonalityTypeSelected(selectedType)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // SeekBars de rasgos de personalidad
        seekBarEmpathy.setOnSeekBarChangeListener(createSeekBarListener { value ->
            personalityTraits.empathy = value / 100f
            updatePersonalityPreview()
        })
        
        seekBarProtectiveness.setOnSeekBarChangeListener(createSeekBarListener { value ->
            personalityTraits.protectiveness = value / 100f
            updatePersonalityPreview()
        })
        
        seekBarPlayfulness.setOnSeekBarChangeListener(createSeekBarListener { value ->
            personalityTraits.playfulness = value / 100f
            updatePersonalityPreview()
        })
        
        seekBarFormality.setOnSeekBarChangeListener(createSeekBarListener { value ->
            personalityTraits.formality = value / 100f
            updatePersonalityPreview()
        })
        
        seekBarProactiveness.setOnSeekBarChangeListener(createSeekBarListener { value ->
            personalityTraits.proactiveness = value / 100f
            updatePersonalityPreview()
        })
        
        // Switches
        switchAdaptiveLearning.setOnCheckedChangeListener { _, isChecked ->
            toggleAdaptiveLearning(isChecked)
        }
        
        switchBehaviorAnalysis.setOnCheckedChangeListener { _, isChecked ->
            toggleBehaviorAnalysis(isChecked)
        }
        
        switchPersonalityEvolution.setOnCheckedChangeListener { _, isChecked ->
            togglePersonalityEvolution(isChecked)
        }
        
        // Botones
        btnApplyPersonality.setOnClickListener {
            applyPersonalityChanges()
        }
        
        btnResetToDefault.setOnClickListener {
            resetToDefaultPersonality()
        }
        
        btnCreateCustom.setOnClickListener {
            createCustomPersonality()
        }
        
        btnTestPersonality.setOnClickListener {
            testCurrentPersonality()
        }
        
        // Avatar de personalidad
        ivPersonalityAvatar.setOnClickListener {
            onPersonalityAvatarClicked()
        }
    }
    
    private fun startPersonalityDesignSystem() {
        lifecycleScope.launch {
            // Inicializar sistemas de personalidad
            personalityDesignerManager.initialize()
            aiPersonalityManager.initialize()
            behaviorAnalysisManager.initialize()
            learningEngine.initialize()
            personalityTraitsManager.initialize()
            adaptationEngine.initialize()
            
            // Cargar personalidad actual
            loadCurrentPersonality()
            
            // Cargar historial
            loadPersonalityHistory()
            
            // Iniciar monitoreo
            launch { monitorPersonalityAdaptation() }
            launch { monitorLearningProgress() }
            launch { monitorBehaviorAnalysis() }
            
            // Análisis inicial de compatibilidad
            performPersonalityCompatibilityAnalysis()
        }
    }
    
    private suspend fun monitorPersonalityAdaptation() {
        adaptationEngine.adaptationLevel.collect { level ->
            runOnUiThread {
                updateAdaptationLevelUI(level)
            }
        }
    }
    
    private suspend fun monitorLearningProgress() {
        learningEngine.learningProgress.collect { progress ->
            runOnUiThread {
                updateLearningProgressUI(progress)
            }
        }
    }
    
    private suspend fun monitorBehaviorAnalysis() {
        if (isBehaviorAnalysisEnabled) {
            behaviorAnalysisManager.behaviorInsights.collect { insights ->
                runOnUiThread {
                    processBehaviorInsights(insights)
                }
            }
        }
    }
    
    private fun setupPersonalitySpinner() {
        val personalityTypes = PersonalityType.values().map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, personalityTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPersonalityType.adapter = adapter
    }
    
    private fun setupSeekBars() {
        // Configurar valores iniciales de los SeekBars
        seekBarEmpathy.progress = (personalityTraits.empathy * 100).toInt()
        seekBarProtectiveness.progress = (personalityTraits.protectiveness * 100).toInt()
        seekBarPlayfulness.progress = (personalityTraits.playfulness * 100).toInt()
        seekBarFormality.progress = (personalityTraits.formality * 100).toInt()
        seekBarProactiveness.progress = (personalityTraits.proactiveness * 100).toInt()
    }
    
    private fun createSeekBarListener(onProgressChanged: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onProgressChanged(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    }
    
    private fun onPersonalityTypeSelected(type: PersonalityType) {
        currentPersonalityType = type
        
        // Cargar rasgos predefinidos para este tipo
        val predefinedTraits = personalityTraitsManager.getTraitsForType(type)
        updatePersonalityTraits(predefinedTraits)
        
        // Actualizar descripción
        updatePersonalityDescription(type)
        
        // Actualizar avatar
        updatePersonalityAvatar(type)
    }
    
    private fun updatePersonalityTraits(traits: PersonalityTraits) {
        personalityTraits = traits
        
        // Actualizar SeekBars
        seekBarEmpathy.progress = (traits.empathy * 100).toInt()
        seekBarProtectiveness.progress = (traits.protectiveness * 100).toInt()
        seekBarPlayfulness.progress = (traits.playfulness * 100).toInt()
        seekBarFormality.progress = (traits.formality * 100).toInt()
        seekBarProactiveness.progress = (traits.proactiveness * 100).toInt()
        
        updatePersonalityPreview()
    }
    
    private fun updatePersonalityPreview() {
        lifecycleScope.launch {
            // Generar vista previa de la personalidad
            val preview = personalityDesignerManager.generatePersonalityPreview(
                currentPersonalityType, personalityTraits
            )
            
            runOnUiThread {
                updatePersonalityDescription(preview)
            }
        }
    }
    
    private fun toggleAdaptiveLearning(enabled: Boolean) {
        isAdaptiveLearningEnabled = enabled
        
        lifecycleScope.launch {
            if (enabled) {
                learningEngine.enableAdaptiveLearning()
                showToast("Aprendizaje adaptativo activado")
            } else {
                learningEngine.disableAdaptiveLearning()
                showToast("Aprendizaje adaptativo desactivado")
            }
        }
    }
    
    private fun toggleBehaviorAnalysis(enabled: Boolean) {
        isBehaviorAnalysisEnabled = enabled
        
        lifecycleScope.launch {
            if (enabled) {
                behaviorAnalysisManager.startAnalysis()
                launch { monitorBehaviorAnalysis() }
                showToast("Análisis de comportamiento activado")
            } else {
                behaviorAnalysisManager.stopAnalysis()
                showToast("Análisis de comportamiento desactivado")
            }
        }
    }
    
    private fun togglePersonalityEvolution(enabled: Boolean) {
        isPersonalityEvolutionEnabled = enabled
        
        lifecycleScope.launch {
            if (enabled) {
                adaptationEngine.enableEvolution()
                showToast("Evolución de personalidad activada")
            } else {
                adaptationEngine.disableEvolution()
                showToast("Evolución de personalidad desactivada")
            }
        }
    }
    
    private fun applyPersonalityChanges() {
        lifecycleScope.launch {
            try {
                btnApplyPersonality.isEnabled = false
                btnApplyPersonality.text = "Aplicando..."
                
                // Crear nueva configuración de personalidad
                val newPersonality = PersonalityConfiguration(
                    type = currentPersonalityType,
                    traits = personalityTraits,
                    adaptiveLearning = isAdaptiveLearningEnabled,
                    behaviorAnalysis = isBehaviorAnalysisEnabled,
                    evolution = isPersonalityEvolutionEnabled
                )
                
                // Aplicar cambios
                val success = aiPersonalityManager.applyPersonalityConfiguration(newPersonality)
                
                if (success) {
                    // Guardar en historial
                    savePersonalityToHistory(newPersonality)
                    
                    // Actualizar UI
                    updateCurrentPersonality(currentPersonalityType)
                    
                    // Realizar test de compatibilidad
                    performPersonalityCompatibilityAnalysis()
                    
                    showToast("Personalidad aplicada exitosamente")
                } else {
                    showToast("Error al aplicar personalidad")
                }
                
            } catch (e: Exception) {
                showToast("Error: ${e.message}")
            } finally {
                btnApplyPersonality.isEnabled = true
                btnApplyPersonality.text = "Aplicar Personalidad"
            }
        }
    }
    
    private fun resetToDefaultPersonality() {
        lifecycleScope.launch {
            showResetConfirmationDialog { confirmed ->
                if (confirmed) {
                    lifecycleScope.launch {
                        val defaultPersonality = personalityTraitsManager.getDefaultPersonality()
                        
                        currentPersonalityType = PersonalityType.GUARDIAN
                        updatePersonalityTraits(defaultPersonality.traits)
                        
                        spinnerPersonalityType.setSelection(0)
                        updateCurrentPersonality(PersonalityType.GUARDIAN)
                        
                        showToast("Personalidad restablecida a valores por defecto")
                    }
                }
            }
        }
    }
    
    private fun createCustomPersonality() {
        lifecycleScope.launch {
            showCustomPersonalityDialog { name, description ->
                if (name.isNotEmpty()) {
                    val customPersonality = CustomPersonality(
                        name = name,
                        description = description,
                        traits = personalityTraits.copy(),
                        createdAt = System.currentTimeMillis()
                    )
                    
                    personalityDesignerManager.saveCustomPersonality(customPersonality)
                    personalityPresetsAdapter.addCustomPersonality(customPersonality)
                    
                    showToast("Personalidad personalizada '$name' creada")
                }
            }
        }
    }
    
    private fun testCurrentPersonality() {
        lifecycleScope.launch {
            try {
                btnTestPersonality.isEnabled = false
                btnTestPersonality.text = "Probando..."
                
                // Realizar test de personalidad
                val testResult = personalityDesignerManager.testPersonality(
                    currentPersonalityType, personalityTraits
                )
                
                // Mostrar resultados del test
                showPersonalityTestResults(testResult)
                
            } catch (e: Exception) {
                showToast("Error en test: ${e.message}")
            } finally {
                btnTestPersonality.isEnabled = true
                btnTestPersonality.text = "Probar Personalidad"
            }
        }
    }
    
    private fun onPersonalityAvatarClicked() {
        lifecycleScope.launch {
            // Mostrar opciones de personalización del avatar
            showAvatarCustomizationDialog()
        }
    }
    
    private fun updateCurrentPersonality(type: PersonalityType) {
        tvCurrentPersonality.text = "Personalidad Actual: ${type.displayName}"
        updatePersonalityDescription(type)
        updatePersonalityAvatar(type)
    }
    
    private fun updatePersonalityDescription(type: PersonalityType) {
        tvPersonalityDescription.text = when (type) {
            PersonalityType.GUARDIAN -> "Protector confiable y vigilante, siempre alerta para mantener la seguridad."
            PersonalityType.COMPANION -> "Amigable y conversador, enfocado en brindar compañía y apoyo emocional."
            PersonalityType.ADVISOR -> "Sabio y analítico, proporciona consejos reflexivos y soluciones prácticas."
            PersonalityType.PROTECTOR -> "Fuerte y decidido, prioriza la seguridad y protección por encima de todo."
            PersonalityType.FRIEND -> "Casual y relajado, interactúa de manera natural y espontánea."
            PersonalityType.PROFESSIONAL -> "Formal y eficiente, mantiene un enfoque profesional en todas las interacciones."
            PersonalityType.CUSTOM -> "Personalidad única diseñada según tus preferencias específicas."
        }
    }
    
    private fun updatePersonalityDescription(preview: PersonalityPreview) {
        tvPersonalityDescription.text = preview.description
    }
    
    private fun updatePersonalityAvatar(type: PersonalityType) {
        // Cambiar imagen del avatar según el tipo de personalidad
        // ivPersonalityAvatar.setImageResource(getAvatarResourceForPersonality(type))
        
        // Animar cambio de avatar
        ivPersonalityAvatar.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                // Cambiar imagen aquí
                ivPersonalityAvatar.animate()
                    .alpha(1f)
                    .setDuration(200)
            }
    }
    
    private fun updateAdaptationLevelUI(level: Float) {
        progressAdaptationLevel.progress = (level * 100).toInt()
        tvAdaptationLevel.text = "Adaptación: ${(level * 100).toInt()}%"
    }
    
    private fun updateLearningProgressUI(progress: Float) {
        progressLearningProgress.progress = (progress * 100).toInt()
        tvLearningProgress.text = "Aprendizaje: ${(progress * 100).toInt()}%"
    }
    
    private fun processBehaviorInsights(insights: BehaviorInsights) {
        // Procesar insights de comportamiento para sugerir ajustes de personalidad
        if (insights.suggestedAdjustments.isNotEmpty()) {
            showBehaviorInsightsDialog(insights)
        }
    }
    
    private fun performPersonalityCompatibilityAnalysis() {
        lifecycleScope.launch {
            val compatibility = personalityDesignerManager.analyzeCompatibility(
                currentPersonalityType, personalityTraits
            )
            
            runOnUiThread {
                updatePersonalityMatchUI(compatibility)
            }
        }
    }
    
    private fun updatePersonalityMatchUI(compatibility: Float) {
        progressPersonalityMatch.progress = (compatibility * 100).toInt()
        
        val color = when {
            compatibility >= 0.8f -> getColor(android.R.color.holo_green_dark)
            compatibility >= 0.6f -> getColor(android.R.color.holo_blue_light)
            compatibility >= 0.4f -> getColor(android.R.color.holo_orange_light)
            else -> getColor(android.R.color.holo_red_light)
        }
        
        progressPersonalityMatch.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }
    
    private fun loadCurrentPersonality() {
        lifecycleScope.launch {
            val currentConfig = aiPersonalityManager.getCurrentPersonalityConfiguration()
            
            runOnUiThread {
                currentPersonalityType = currentConfig.type
                personalityTraits = currentConfig.traits
                
                spinnerPersonalityType.setSelection(currentPersonalityType.ordinal)
                updatePersonalityTraits(personalityTraits)
                updateCurrentPersonality(currentPersonalityType)
            }
        }
    }
    
    private fun loadPersonalityHistory() {
        lifecycleScope.launch {
            val history = personalityDesignerManager.getPersonalityHistory()
            personalityHistoryAdapter.updateHistory(history)
        }
    }
    
    private fun loadPersonalityPresets() {
        val presets = listOf(
            PersonalityPreset("Guardian Clásico", "Protector tradicional y confiable", PersonalityType.GUARDIAN),
            PersonalityPreset("Compañero Amigable", "Sociable y empático", PersonalityType.COMPANION),
            PersonalityPreset("Consejero Sabio", "Analítico y reflexivo", PersonalityType.ADVISOR),
            PersonalityPreset("Protector Fuerte", "Seguridad máxima", PersonalityType.PROTECTOR),
            PersonalityPreset("Amigo Casual", "Relajado y natural", PersonalityType.FRIEND),
            PersonalityPreset("Asistente Profesional", "Formal y eficiente", PersonalityType.PROFESSIONAL)
        )
        
        personalityPresetsAdapter.updatePresets(presets)
    }
    
    private fun onPersonalityPresetClicked(preset: PersonalityPreset) {
        currentPersonalityType = preset.type
        val presetTraits = personalityTraitsManager.getTraitsForType(preset.type)
        
        spinnerPersonalityType.setSelection(preset.type.ordinal)
        updatePersonalityTraits(presetTraits)
        
        showToast("Preset '${preset.name}' cargado")
    }
    
    private fun onPersonalityHistoryClicked(entry: PersonalityHistoryEntry) {
        showPersonalityHistoryDetailsDialog(entry)
    }
    
    private fun savePersonalityToHistory(config: PersonalityConfiguration) {
        lifecycleScope.launch {
            val historyEntry = PersonalityHistoryEntry(
                timestamp = System.currentTimeMillis(),
                type = config.type,
                traits = config.traits,
                description = personalityDesignerManager.generatePersonalityDescription(config)
            )
            
            personalityDesignerManager.saveToHistory(historyEntry)
            personalityHistoryAdapter.addEntry(historyEntry)
        }
    }
    
    // Métodos de diálogos
    private fun showResetConfirmationDialog(callback: (Boolean) -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Restablecer Personalidad")
            .setMessage("¿Está seguro de que desea restablecer la personalidad a los valores por defecto? Se perderán todos los cambios actuales.")
            .setPositiveButton("Restablecer") { _, _ -> callback(true) }
            .setNegativeButton("Cancelar") { _, _ -> callback(false) }
            .show()
    }
    
    private fun showCustomPersonalityDialog(callback: (String, String) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_personality, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_personality_name)
        val etDescription = dialogView.findViewById<EditText>(R.id.et_personality_description)
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Crear Personalidad Personalizada")
            .setView(dialogView)
            .setPositiveButton("Crear") { _, _ ->
                callback(etName.text.toString(), etDescription.text.toString())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun showPersonalityTestResults(result: PersonalityTestResult) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Resultados del Test de Personalidad")
            .setMessage("""
                Compatibilidad: ${(result.compatibility * 100).toInt()}%
                Efectividad: ${(result.effectiveness * 100).toInt()}%
                Satisfacción del usuario: ${(result.userSatisfaction * 100).toInt()}%
                
                Fortalezas:
                ${result.strengths.joinToString("\n• ", "• ")}
                
                Áreas de mejora:
                ${result.improvements.joinToString("\n• ", "• ")}
                
                Recomendaciones:
                ${result.recommendations}
            """.trimIndent())
            .setPositiveButton("Entendido", null)
            .setNeutralButton("Aplicar Sugerencias") { _, _ ->
                applyTestSuggestions(result)
            }
            .show()
    }
    
    private fun showBehaviorInsightsDialog(insights: BehaviorInsights) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("💡 Insights de Comportamiento")
            .setMessage("""
                He notado algunos patrones en tu comportamiento que podrían mejorar nuestra interacción:
                
                ${insights.observations.joinToString("\n\n")}
                
                Sugerencias de ajuste:
                ${insights.suggestedAdjustments.joinToString("\n• ", "• ")}
            """.trimIndent())
            .setPositiveButton("Aplicar Sugerencias") { _, _ ->
                applyBehaviorSuggestions(insights)
            }
            .setNegativeButton("Ignorar", null)
            .show()
    }
    
    private fun showAvatarCustomizationDialog() {
        val options = arrayOf("Cambiar Expresión", "Cambiar Color", "Cambiar Estilo", "Personalizar Completo")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Personalizar Avatar")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> customizeAvatarExpression()
                    1 -> customizeAvatarColor()
                    2 -> customizeAvatarStyle()
                    3 -> openFullAvatarCustomization()
                }
            }
            .show()
    }
    
    private fun showPersonalityHistoryDetailsDialog(entry: PersonalityHistoryEntry) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Historial de Personalidad")
            .setMessage("""
                Fecha: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(entry.timestamp)}
                Tipo: ${entry.type.displayName}
                
                Rasgos:
                • Empatía: ${(entry.traits.empathy * 100).toInt()}%
                • Protección: ${(entry.traits.protectiveness * 100).toInt()}%
                • Diversión: ${(entry.traits.playfulness * 100).toInt()}%
                • Formalidad: ${(entry.traits.formality * 100).toInt()}%
                • Proactividad: ${(entry.traits.proactiveness * 100).toInt()}%
                
                Descripción: ${entry.description}
            """.trimIndent())
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Restaurar") { _, _ ->
                restorePersonalityFromHistory(entry)
            }
            .show()
    }
    
    private fun applyTestSuggestions(result: PersonalityTestResult) {
        lifecycleScope.launch {
            // Aplicar sugerencias del test
            val adjustedTraits = personalityTraitsManager.applyTestSuggestions(personalityTraits, result)
            updatePersonalityTraits(adjustedTraits)
            showToast("Sugerencias aplicadas")
        }
    }
    
    private fun applyBehaviorSuggestions(insights: BehaviorInsights) {
        lifecycleScope.launch {
            // Aplicar sugerencias de comportamiento
            val adjustedTraits = personalityTraitsManager.applyBehaviorSuggestions(personalityTraits, insights)
            updatePersonalityTraits(adjustedTraits)
            showToast("Ajustes de comportamiento aplicados")
        }
    }
    
    private fun restorePersonalityFromHistory(entry: PersonalityHistoryEntry) {
        currentPersonalityType = entry.type
        updatePersonalityTraits(entry.traits)
        spinnerPersonalityType.setSelection(entry.type.ordinal)
        showToast("Personalidad restaurada desde historial")
    }
    
    private fun customizeAvatarExpression() {
        // Implementar personalización de expresión
        showToast("Personalización de expresión - Próximamente")
    }
    
    private fun customizeAvatarColor() {
        // Implementar personalización de color
        showToast("Personalización de color - Próximamente")
    }
    
    private fun customizeAvatarStyle() {
        // Implementar personalización de estilo
        showToast("Personalización de estilo - Próximamente")
    }
    
    private fun openFullAvatarCustomization() {
        // Abrir interfaz completa de personalización
        showToast("Personalización completa - Próximamente")
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    // Enums y clases de datos
    enum class PersonalityType(val displayName: String) {
        GUARDIAN("Guardian"),
        COMPANION("Compañero"),
        ADVISOR("Consejero"),
        PROTECTOR("Protector"),
        FRIEND("Amigo"),
        PROFESSIONAL("Profesional"),
        CUSTOM("Personalizado")
    }
    
    data class PersonalityTraits(
        var empathy: Float = 0.8f,
        var protectiveness: Float = 0.9f,
        var playfulness: Float = 0.6f,
        var formality: Float = 0.5f,
        var proactiveness: Float = 0.7f
    )
    
    data class PersonalityConfiguration(
        val type: PersonalityType,
        val traits: PersonalityTraits,
        val adaptiveLearning: Boolean,
        val behaviorAnalysis: Boolean,
        val evolution: Boolean
    )
    
    data class PersonalityPreview(
        val description: String,
        val strengths: List<String>,
        val characteristics: List<String>
    )
    
    data class PersonalityPreset(
        val name: String,
        val description: String,
        val type: PersonalityType
    )
    
    data class CustomPersonality(
        val name: String,
        val description: String,
        val traits: PersonalityTraits,
        val createdAt: Long
    )
    
    data class PersonalityTestResult(
        val compatibility: Float,
        val effectiveness: Float,
        val userSatisfaction: Float,
        val strengths: List<String>,
        val improvements: List<String>,
        val recommendations: String
    )
    
    data class BehaviorInsights(
        val observations: List<String>,
        val suggestedAdjustments: List<String>,
        val confidence: Float
    )
    
    data class PersonalityHistoryEntry(
        val timestamp: Long,
        val type: PersonalityType,
        val traits: PersonalityTraits,
        val description: String
    )
}

