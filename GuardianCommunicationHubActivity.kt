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
 * Actividad del Hub de Comunicación Guardian
 * Trabaja con activity_guardian_communication_hub.xml (SIN MODIFICAR EL LAYOUT)
 * Implementa toda la lógica de comunicación avanzada con IA
 */
class GuardianCommunicationHubActivity : AppCompatActivity() {
    
    // Managers especializados
    private lateinit var communicationManager: CommunicationManager
    private lateinit var aiPersonalityManager: AIPersonalityManager
    private lateinit var voiceManager: VoiceManager
    private lateinit var chatManager: ChatManager
    private lateinit var emotionalAnalysisManager: EmotionalAnalysisManager
    
    // UI Components (basados en tu layout)
    private lateinit var tvGuardianGreeting: TextView
    private lateinit var tvCommunicationStatus: TextView
    private lateinit var tvLastInteraction: TextView
    private lateinit var etUserMessage: EditText
    private lateinit var btnSendMessage: Button
    private lateinit var btnVoiceInput: Button
    private lateinit var btnEmergencyCall: Button
    private lateinit var switchVoiceMode: Switch
    private lateinit var switchProactiveMode: Switch
    private lateinit var rvChatHistory: RecyclerView
    private lateinit var rvQuickResponses: RecyclerView
    private lateinit var progressVoiceLevel: ProgressBar
    private lateinit var ivGuardianAvatar: ImageView
    private lateinit var tvGuardianMood: TextView
    
    // Adapters
    private lateinit var chatHistoryAdapter: ChatHistoryAdapter
    private lateinit var quickResponsesAdapter: QuickResponsesAdapter
    
    // Estados
    private var isVoiceModeActive = false
    private var isProactiveModeActive = true
    private var currentMood = GuardianMood.FRIENDLY
    private var isListening = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_communication_hub) // TU LAYOUT ORIGINAL
        
        initializeComponents()
        initializeManagers()
        setupAdapters()
        setupEventListeners()
        startCommunicationSystem()
    }
    
    private fun initializeComponents() {
        // Mapear componentes de tu layout original
        tvGuardianGreeting = findViewById(R.id.tv_guardian_greeting)
        tvCommunicationStatus = findViewById(R.id.tv_communication_status)
        tvLastInteraction = findViewById(R.id.tv_last_interaction)
        etUserMessage = findViewById(R.id.et_user_message)
        btnSendMessage = findViewById(R.id.btn_send_message)
        btnVoiceInput = findViewById(R.id.btn_voice_input)
        btnEmergencyCall = findViewById(R.id.btn_emergency_call)
        switchVoiceMode = findViewById(R.id.switch_voice_mode)
        switchProactiveMode = findViewById(R.id.switch_proactive_mode)
        rvChatHistory = findViewById(R.id.rv_chat_history)
        rvQuickResponses = findViewById(R.id.rv_quick_responses)
        progressVoiceLevel = findViewById(R.id.progress_voice_level)
        ivGuardianAvatar = findViewById(R.id.iv_guardian_avatar)
        tvGuardianMood = findViewById(R.id.tv_guardian_mood)
        
        // Configurar estado inicial
        updateGuardianGreeting()
        updateCommunicationStatus(CommunicationStatus.ONLINE)
        updateGuardianMood(GuardianMood.FRIENDLY)
    }
    
    private fun initializeManagers() {
        communicationManager = CommunicationManager(this)
        aiPersonalityManager = AIPersonalityManager(this)
        voiceManager = VoiceManager(this)
        chatManager = ChatManager(this)
        emotionalAnalysisManager = EmotionalAnalysisManager(this)
    }
    
    private fun setupAdapters() {
        chatHistoryAdapter = ChatHistoryAdapter { message ->
            onChatMessageClicked(message)
        }
        rvChatHistory.adapter = chatHistoryAdapter
        
        quickResponsesAdapter = QuickResponsesAdapter { response ->
            onQuickResponseClicked(response)
        }
        rvQuickResponses.adapter = quickResponsesAdapter
        
        // Cargar respuestas rápidas
        loadQuickResponses()
    }
    
    private fun setupEventListeners() {
        // Botón enviar mensaje
        btnSendMessage.setOnClickListener {
            sendUserMessage()
        }
        
        // Botón entrada de voz
        btnVoiceInput.setOnClickListener {
            toggleVoiceInput()
        }
        
        // Botón llamada de emergencia
        btnEmergencyCall.setOnClickListener {
            initiateEmergencyCall()
        }
        
        // Switch modo de voz
        switchVoiceMode.setOnCheckedChangeListener { _, isChecked ->
            toggleVoiceMode(isChecked)
        }
        
        // Switch modo proactivo
        switchProactiveMode.setOnCheckedChangeListener { _, isChecked ->
            toggleProactiveMode(isChecked)
        }
        
        // Avatar de Guardian
        ivGuardianAvatar.setOnClickListener {
            onGuardianAvatarClicked()
        }
        
        // Enter en campo de texto
        etUserMessage.setOnEditorActionListener { _, _, _ ->
            sendUserMessage()
            true
        }
    }
    
    private fun startCommunicationSystem() {
        lifecycleScope.launch {
            // Inicializar sistemas de comunicación
            communicationManager.initialize()
            aiPersonalityManager.initialize()
            voiceManager.initialize()
            chatManager.initialize()
            emotionalAnalysisManager.initialize()
            
            // Cargar historial de chat
            loadChatHistory()
            
            // Iniciar monitoreo
            launch { monitorCommunicationStatus() }
            launch { monitorAIPersonality() }
            launch { monitorVoiceInput() }
            launch { monitorEmotionalState() }
            
            // Modo proactivo
            if (isProactiveModeActive) {
                startProactiveCommunication()
            }
            
            // Saludo inicial
            sendGuardianMessage("¡Hola! Soy Guardian IA. ¿En qué puedo ayudarte hoy?")
        }
    }
    
    private suspend fun monitorCommunicationStatus() {
        communicationManager.communicationStatus.collect { status ->
            runOnUiThread {
                updateCommunicationStatusUI(status)
            }
        }
    }
    
    private suspend fun monitorAIPersonality() {
        aiPersonalityManager.personalityUpdates.collect { personality ->
            runOnUiThread {
                updateGuardianPersonalityUI(personality)
            }
        }
    }
    
    private suspend fun monitorVoiceInput() {
        voiceManager.voiceLevel.collect { level ->
            runOnUiThread {
                updateVoiceLevelUI(level)
            }
        }
    }
    
    private suspend fun monitorEmotionalState() {
        emotionalAnalysisManager.emotionalState.collect { emotion ->
            runOnUiThread {
                updateGuardianMoodBasedOnEmotion(emotion)
            }
        }
    }
    
    private fun sendUserMessage() {
        val message = etUserMessage.text.toString().trim()
        if (message.isNotEmpty()) {
            lifecycleScope.launch {
                // Agregar mensaje del usuario al chat
                val userMessage = ChatMessage(
                    id = generateMessageId(),
                    sender = MessageSender.USER,
                    content = message,
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.TEXT
                )
                
                chatHistoryAdapter.addMessage(userMessage)
                chatManager.saveMessage(userMessage)
                
                // Limpiar campo de texto
                etUserMessage.text.clear()
                
                // Analizar emoción del usuario
                val userEmotion = emotionalAnalysisManager.analyzeUserMessage(message)
                
                // Generar respuesta de Guardian
                val guardianResponse = aiPersonalityManager.generateResponse(
                    userMessage = message,
                    userEmotion = userEmotion,
                    context = chatManager.getRecentContext()
                )
                
                // Enviar respuesta de Guardian
                delay(1000) // Simular tiempo de procesamiento
                sendGuardianMessage(guardianResponse)
                
                // Actualizar última interacción
                updateLastInteraction()
            }
        }
    }
    
    private fun toggleVoiceInput() {
        if (isListening) {
            stopVoiceInput()
        } else {
            startVoiceInput()
        }
    }
    
    private fun startVoiceInput() {
        lifecycleScope.launch {
            try {
                isListening = true
                btnVoiceInput.text = "🔴 Escuchando..."
                btnVoiceInput.setBackgroundColor(getColor(android.R.color.holo_red_light))
                
                // Iniciar reconocimiento de voz
                val voiceText = voiceManager.startVoiceRecognition()
                
                if (voiceText.isNotEmpty()) {
                    etUserMessage.setText(voiceText)
                    sendUserMessage()
                }
                
            } catch (e: Exception) {
                showToast("Error en reconocimiento de voz: ${e.message}")
            } finally {
                stopVoiceInput()
            }
        }
    }
    
    private fun stopVoiceInput() {
        isListening = false
        btnVoiceInput.text = "🎤 Voz"
        btnVoiceInput.setBackgroundColor(getColor(android.R.color.holo_blue_light))
        voiceManager.stopVoiceRecognition()
    }
    
    private fun toggleVoiceMode(enabled: Boolean) {
        isVoiceModeActive = enabled
        
        lifecycleScope.launch {
            if (enabled) {
                voiceManager.enableTextToSpeech()
                showToast("Modo de voz activado")
            } else {
                voiceManager.disableTextToSpeech()
                showToast("Modo de voz desactivado")
            }
        }
    }
    
    private fun toggleProactiveMode(enabled: Boolean) {
        isProactiveModeActive = enabled
        
        lifecycleScope.launch {
            if (enabled) {
                communicationManager.enableProactiveMode()
                startProactiveCommunication()
                showToast("Modo proactivo activado")
            } else {
                communicationManager.disableProactiveMode()
                stopProactiveCommunication()
                showToast("Modo proactivo desactivado")
            }
        }
    }
    
    private fun initiateEmergencyCall() {
        lifecycleScope.launch {
            try {
                // Confirmar llamada de emergencia
                showEmergencyCallDialog { confirmed ->
                    if (confirmed) {
                        lifecycleScope.launch {
                            communicationManager.initiateEmergencyProtocol()
                            
                            // Cambiar mood a urgente
                            updateGuardianMood(GuardianMood.URGENT)
                            
                            // Mensaje de emergencia
                            sendGuardianMessage("🚨 Protocolo de emergencia activado. Contactando servicios de emergencia...")
                            
                            // Simular contacto con emergencias
                            delay(2000)
                            sendGuardianMessage("Servicios de emergencia han sido notificados. Mantén la calma, la ayuda está en camino.")
                        }
                    }
                }
            } catch (e: Exception) {
                showToast("Error al contactar emergencias: ${e.message}")
            }
        }
    }
    
    private fun onGuardianAvatarClicked() {
        lifecycleScope.launch {
            // Interacción especial con el avatar
            val specialResponse = aiPersonalityManager.generateAvatarInteraction()
            sendGuardianMessage(specialResponse)
            
            // Cambiar mood temporalmente
            updateGuardianMood(GuardianMood.PLAYFUL)
            
            delay(3000)
            updateGuardianMood(GuardianMood.FRIENDLY)
        }
    }
    
    private fun sendGuardianMessage(message: String) {
        lifecycleScope.launch {
            val guardianMessage = ChatMessage(
                id = generateMessageId(),
                sender = MessageSender.GUARDIAN,
                content = message,
                timestamp = System.currentTimeMillis(),
                type = MessageType.TEXT
            )
            
            chatHistoryAdapter.addMessage(guardianMessage)
            chatManager.saveMessage(guardianMessage)
            
            // Reproducir voz si está habilitado
            if (isVoiceModeActive) {
                voiceManager.speakText(message)
            }
            
            // Animar avatar
            animateGuardianAvatar()
        }
    }
    
    private fun startProactiveCommunication() {
        lifecycleScope.launch {
            while (isProactiveModeActive) {
                delay(300000) // Cada 5 minutos
                
                val proactiveMessage = aiPersonalityManager.generateProactiveMessage()
                if (proactiveMessage.isNotEmpty()) {
                    sendGuardianMessage(proactiveMessage)
                }
            }
        }
    }
    
    private fun stopProactiveCommunication() {
        // El bucle se detendrá automáticamente cuando isProactiveModeActive sea false
    }
    
    private fun updateGuardianGreeting() {
        val greeting = aiPersonalityManager.generateGreeting()
        tvGuardianGreeting.text = greeting
    }
    
    private fun updateCommunicationStatus(status: CommunicationStatus) {
        tvCommunicationStatus.text = when (status) {
            CommunicationStatus.ONLINE -> "🟢 En línea"
            CommunicationStatus.BUSY -> "🟡 Ocupado"
            CommunicationStatus.OFFLINE -> "🔴 Desconectado"
            CommunicationStatus.EMERGENCY -> "🚨 Emergencia"
        }
    }
    
    private fun updateGuardianMood(mood: GuardianMood) {
        currentMood = mood
        
        tvGuardianMood.text = when (mood) {
            GuardianMood.FRIENDLY -> "😊 Amigable"
            GuardianMood.PROTECTIVE -> "🛡️ Protector"
            GuardianMood.PLAYFUL -> "😄 Juguetón"
            GuardianMood.SERIOUS -> "😐 Serio"
            GuardianMood.URGENT -> "😰 Urgente"
            GuardianMood.CARING -> "🤗 Cariñoso"
        }
        
        // Cambiar avatar según mood
        updateAvatarForMood(mood)
    }
    
    private fun updateCommunicationStatusUI(status: CommunicationStatusData) {
        updateCommunicationStatus(status.status)
        
        // Actualizar otros indicadores
        if (status.hasNewMessages) {
            // Mostrar indicador de mensajes nuevos
        }
    }
    
    private fun updateGuardianPersonalityUI(personality: PersonalityData) {
        // Actualizar interfaz según personalidad
        updateGuardianMood(personality.currentMood)
    }
    
    private fun updateVoiceLevelUI(level: Float) {
        progressVoiceLevel.progress = (level * 100).toInt()
    }
    
    private fun updateGuardianMoodBasedOnEmotion(emotion: EmotionalState) {
        val newMood = when (emotion.dominantEmotion) {
            "happy" -> GuardianMood.PLAYFUL
            "sad" -> GuardianMood.CARING
            "angry" -> GuardianMood.PROTECTIVE
            "fear" -> GuardianMood.PROTECTIVE
            "neutral" -> GuardianMood.FRIENDLY
            else -> GuardianMood.FRIENDLY
        }
        
        updateGuardianMood(newMood)
    }
    
    private fun updateLastInteraction() {
        tvLastInteraction.text = "Última interacción: Ahora"
    }
    
    private fun updateAvatarForMood(mood: GuardianMood) {
        // Cambiar imagen del avatar según el mood
        // ivGuardianAvatar.setImageResource(getAvatarResourceForMood(mood))
    }
    
    private fun animateGuardianAvatar() {
        ivGuardianAvatar.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(200)
            .withEndAction {
                ivGuardianAvatar.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
            }
    }
    
    private fun loadChatHistory() {
        lifecycleScope.launch {
            val history = chatManager.getChatHistory()
            chatHistoryAdapter.updateMessages(history)
        }
    }
    
    private fun loadQuickResponses() {
        val quickResponses = listOf(
            "¿Cómo estás?",
            "Estado del sistema",
            "Realizar escaneo",
            "Activar protección",
            "Ayuda",
            "Configuración"
        )
        
        quickResponsesAdapter.updateResponses(quickResponses)
    }
    
    private fun onChatMessageClicked(message: ChatMessage) {
        // Mostrar opciones para el mensaje
        showMessageOptionsDialog(message)
    }
    
    private fun onQuickResponseClicked(response: String) {
        etUserMessage.setText(response)
        sendUserMessage()
    }
    
    private fun showEmergencyCallDialog(callback: (Boolean) -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Llamada de Emergencia")
            .setMessage("¿Está seguro de que desea contactar servicios de emergencia?")
            .setPositiveButton("Sí, contactar") { _, _ -> callback(true) }
            .setNegativeButton("Cancelar") { _, _ -> callback(false) }
            .show()
    }
    
    private fun showMessageOptionsDialog(message: ChatMessage) {
        val options = arrayOf("Copiar", "Reenviar", "Eliminar")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Opciones de mensaje")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyMessageToClipboard(message)
                    1 -> resendMessage(message)
                    2 -> deleteMessage(message)
                }
            }
            .show()
    }
    
    private fun copyMessageToClipboard(message: ChatMessage) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Guardian Message", message.content)
        clipboard.setPrimaryClip(clip)
        showToast("Mensaje copiado")
    }
    
    private fun resendMessage(message: ChatMessage) {
        etUserMessage.setText(message.content)
    }
    
    private fun deleteMessage(message: ChatMessage) {
        lifecycleScope.launch {
            chatManager.deleteMessage(message.id)
            chatHistoryAdapter.removeMessage(message)
            showToast("Mensaje eliminado")
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun generateMessageId(): String = "msg_${System.currentTimeMillis()}"
    
    // Enums y clases de datos
    enum class CommunicationStatus { ONLINE, BUSY, OFFLINE, EMERGENCY }
    enum class GuardianMood { FRIENDLY, PROTECTIVE, PLAYFUL, SERIOUS, URGENT, CARING }
    enum class MessageSender { USER, GUARDIAN }
    enum class MessageType { TEXT, VOICE, IMAGE, SYSTEM }
    
    data class ChatMessage(
        val id: String,
        val sender: MessageSender,
        val content: String,
        val timestamp: Long,
        val type: MessageType
    )
    
    data class CommunicationStatusData(
        val status: CommunicationStatus,
        val hasNewMessages: Boolean,
        val lastActivity: Long
    )
    
    data class PersonalityData(
        val currentMood: GuardianMood,
        val personalityType: String,
        val emotionalState: String
    )
    
    data class EmotionalState(
        val dominantEmotion: String,
        val confidence: Float,
        val emotions: Map<String, Float>
    )
}

