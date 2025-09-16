package com.guardianai.activities

import android.content.Intent
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
 * Actividad Principal del Sistema Guardian IA
 * Trabaja con activity_main.xml (SIN MODIFICAR EL LAYOUT)
 * Punto de entrada y coordinador central de todo el sistema Guardian
 */
class MainActivity : AppCompatActivity() {
    
    // Managers principales del sistema
    private lateinit var guardianSystemOrchestrator: GuardianSystemOrchestrator
    private lateinit var mainDashboardManager: MainDashboardManager
    private lateinit var navigationManager: NavigationManager
    private lateinit var systemStatusManager: SystemStatusManager
    private lateinit var quickActionsManager: QuickActionsManager
    private lateinit var notificationManager: NotificationManager
    
    // UI Components (basados en tu layout)
    private lateinit var tvWelcomeMessage: TextView
    private lateinit var tvSystemStatus: TextView
    private lateinit var tvGuardianPersonality: TextView
    private lateinit var tvQuickStats: TextView
    private lateinit var tvLastActivity: TextView
    private lateinit var tvSystemHealth: TextView
    private lateinit var tvSecurityStatus: TextView
    private lateinit var tvActiveModules: TextView
    private lateinit var progressSystemHealth: ProgressBar
    private lateinit var progressSecurityLevel: ProgressBar
    private lateinit var progressPersonalitySync: ProgressBar
    private lateinit var btnMainInterface: Button
    private lateinit var btnProtectionCenter: Button
    private lateinit var btnCommunicationHub: Button
    private lateinit var btnEmotionalBonding: Button
    private lateinit var btnPersonalityDesigner: Button
    private lateinit var btnAICommunication: Button
    private lateinit var btnMusicCreator: Button
    private lateinit var btnAdminDashboard: Button
    private lateinit var btnAntiTheft: Button
    private lateinit var btnConfigurationCenter: Button
    private lateinit var btnRealTimeMonitoring: Button
    private lateinit var btnQuickSetup: Button
    private lateinit var switchGuardianActive: Switch
    private lateinit var switchAutoMode: Switch
    private lateinit var switchNotifications: Switch
    private lateinit var rvQuickActions: RecyclerView
    private lateinit var rvSystemModules: RecyclerView
    private lateinit var rvRecentActivities: RecyclerView
    private lateinit var ivGuardianAvatar: ImageView
    
    // Adapters
    private lateinit var quickActionsAdapter: QuickActionsAdapter
    private lateinit var systemModulesAdapter: SystemModulesAdapter
    private lateinit var recentActivitiesAdapter: RecentActivitiesAdapter
    
    // Estados del sistema principal
    private var isGuardianActive = false
    private var isAutoModeEnabled = true
    private var currentSystemHealth = 0.95f
    private var currentSecurityLevel = 0.88f
    private var currentPersonalitySync = 0.92f
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // TU LAYOUT ORIGINAL
        
        initializeComponents()
        initializeManagers()
        setupAdapters()
        setupEventListeners()
        startGuardianSystem()
    }
    
    private fun initializeComponents() {
        // Mapear componentes de tu layout original
        tvWelcomeMessage = findViewById(R.id.tv_welcome_message)
        tvSystemStatus = findViewById(R.id.tv_system_status)
        tvGuardianPersonality = findViewById(R.id.tv_guardian_personality)
        tvQuickStats = findViewById(R.id.tv_quick_stats)
        tvLastActivity = findViewById(R.id.tv_last_activity)
        tvSystemHealth = findViewById(R.id.tv_system_health)
        tvSecurityStatus = findViewById(R.id.tv_security_status)
        tvActiveModules = findViewById(R.id.tv_active_modules)
        progressSystemHealth = findViewById(R.id.progress_system_health)
        progressSecurityLevel = findViewById(R.id.progress_security_level)
        progressPersonalitySync = findViewById(R.id.progress_personality_sync)
        btnMainInterface = findViewById(R.id.btn_main_interface)
        btnProtectionCenter = findViewById(R.id.btn_protection_center)
        btnCommunicationHub = findViewById(R.id.btn_communication_hub)
        btnEmotionalBonding = findViewById(R.id.btn_emotional_bonding)
        btnPersonalityDesigner = findViewById(R.id.btn_personality_designer)
        btnAICommunication = findViewById(R.id.btn_ai_communication)
        btnMusicCreator = findViewById(R.id.btn_music_creator)
        btnAdminDashboard = findViewById(R.id.btn_admin_dashboard)
        btnAntiTheft = findViewById(R.id.btn_anti_theft)
        btnConfigurationCenter = findViewById(R.id.btn_configuration_center)
        btnRealTimeMonitoring = findViewById(R.id.btn_real_time_monitoring)
        btnQuickSetup = findViewById(R.id.btn_quick_setup)
        switchGuardianActive = findViewById(R.id.switch_guardian_active)
        switchAutoMode = findViewById(R.id.switch_auto_mode)
        switchNotifications = findViewById(R.id.switch_notifications)
        rvQuickActions = findViewById(R.id.rv_quick_actions)
        rvSystemModules = findViewById(R.id.rv_system_modules)
        rvRecentActivities = findViewById(R.id.rv_recent_activities)
        ivGuardianAvatar = findViewById(R.id.iv_guardian_avatar)
        
        // Configurar estado inicial
        updateWelcomeMessage()
        updateSystemStatus()
        updateProgressBars()
    }
    
    private fun initializeManagers() {
        guardianSystemOrchestrator = GuardianSystemOrchestrator(this)
        mainDashboardManager = MainDashboardManager(this)
        navigationManager = NavigationManager(this)
        systemStatusManager = SystemStatusManager(this)
        quickActionsManager = QuickActionsManager(this)
        notificationManager = NotificationManager(this)
    }
    
    private fun setupAdapters() {
        quickActionsAdapter = QuickActionsAdapter { action ->
            onQuickActionClicked(action)
        }
        rvQuickActions.adapter = quickActionsAdapter
        
        systemModulesAdapter = SystemModulesAdapter { module ->
            onSystemModuleClicked(module)
        }
        rvSystemModules.adapter = systemModulesAdapter
        
        recentActivitiesAdapter = RecentActivitiesAdapter { activity ->
            onRecentActivityClicked(activity)
        }
        rvRecentActivities.adapter = recentActivitiesAdapter
    }
    
    private fun setupEventListeners() {
        // Botones de navegación a módulos principales
        btnMainInterface.setOnClickListener {
            navigateToMainInterface()
        }
        
        btnProtectionCenter.setOnClickListener {
            navigateToProtectionCenter()
        }
        
        btnCommunicationHub.setOnClickListener {
            navigateToCommunicationHub()
        }
        
        btnEmotionalBonding.setOnClickListener {
            navigateToEmotionalBonding()
        }
        
        btnPersonalityDesigner.setOnClickListener {
            navigateToPersonalityDesigner()
        }
        
        btnAICommunication.setOnClickListener {
            navigateToAICommunication()
        }
        
        btnMusicCreator.setOnClickListener {
            navigateToMusicCreator()
        }
        
        btnAdminDashboard.setOnClickListener {
            navigateToAdminDashboard()
        }
        
        btnAntiTheft.setOnClickListener {
            navigateToAntiTheft()
        }
        
        btnConfigurationCenter.setOnClickListener {
            navigateToConfigurationCenter()
        }
        
        btnRealTimeMonitoring.setOnClickListener {
            navigateToRealTimeMonitoring()
        }
        
        btnQuickSetup.setOnClickListener {
            startQuickSetup()
        }
        
        // Switches principales
        switchGuardianActive.setOnCheckedChangeListener { _, isChecked ->
            toggleGuardianSystem(isChecked)
        }
        
        switchAutoMode.setOnCheckedChangeListener { _, isChecked ->
            toggleAutoMode(isChecked)
        }
        
        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            toggleNotifications(isChecked)
        }
        
        // Avatar de Guardian
        ivGuardianAvatar.setOnClickListener {
            onGuardianAvatarClicked()
        }
    }
    
    private fun startGuardianSystem() {
        lifecycleScope.launch {
            // Mostrar splash de inicio
            showGuardianStartupMessage()
            
            // Inicializar todos los managers
            guardianSystemOrchestrator.initialize()
            mainDashboardManager.initialize()
            navigationManager.initialize()
            systemStatusManager.initialize()
            quickActionsManager.initialize()
            notificationManager.initialize()
            
            // Cargar configuración del sistema
            loadSystemConfiguration()
            
            // Cargar datos del dashboard
            loadDashboardData()
            
            // Iniciar monitoreo del sistema
            launch { monitorSystemHealth() }
            launch { monitorSecurityStatus() }
            launch { monitorPersonalitySync() }
            launch { monitorSystemActivities() }
            
            // Verificar estado inicial
            checkInitialSystemState()
            
            // Mostrar mensaje de bienvenida personalizado
            showPersonalizedWelcome()
        }
    }
    
    private suspend fun monitorSystemHealth() {
        systemStatusManager.systemHealth.collect { health ->
            runOnUiThread {
                updateSystemHealthUI(health)
            }
        }
    }
    
    private suspend fun monitorSecurityStatus() {
        systemStatusManager.securityStatus.collect { security ->
            runOnUiThread {
                updateSecurityStatusUI(security)
            }
        }
    }
    
    private suspend fun monitorPersonalitySync() {
        systemStatusManager.personalitySync.collect { sync ->
            runOnUiThread {
                updatePersonalitySyncUI(sync)
            }
        }
    }
    
    private suspend fun monitorSystemActivities() {
        systemStatusManager.recentActivities.collect { activities ->
            runOnUiThread {
                updateRecentActivitiesUI(activities)
            }
        }
    }
    
    private fun navigateToMainInterface() {
        val intent = Intent(this, GuardianMainInterfaceActivity::class.java)
        startActivity(intent)
    }
    
    private fun navigateToProtectionCenter() {
        val intent = Intent(this, GuardianProtectionCenterActivity::class.java)
        startActivity(intent)
    }
    
    private fun navigateToCommunicationHub() {
        val intent = Intent(this, GuardianCommunicationHubActivity::class.java)
        startActivity(intent)
    }
    
    private fun navigateToEmotionalBonding() {
        val intent = Intent(this, GuardianEmotionalBondingActivity::class.java)
        startActivity(intent)
    }
    
    private fun navigateToPersonalityDesigner() {
        val intent = Intent(this, GuardianPersonalityDesignerActivity::class.java)
        startActivity(intent)
    }
    
    private fun navigateToAICommunication() {
        val intent = Intent(this, AICommunicationActivity::class.java)
        startActivity(intent)
    }
    
    private fun navigateToMusicCreator() {
        val intent = Intent(this, GuardianMusicCreatorActivity::class.java)
        startActivity(intent)
    }
    
    private fun navigateToAdminDashboard() {
        val intent = Intent(this, GuardianAdminDashboardActivity::class.java)
        startActivity(intent)
    }
    
    private fun navigateToAntiTheft() {
        val intent = Intent(this, GuardianAntiTheftActivity::class.java)
        startActivity(intent)
    }
    
    private fun navigateToConfigurationCenter() {
        val intent = Intent(this, GuardianConfigurationCenterActivity::class.java)
        startActivity(intent)
    }
    
    private fun navigateToRealTimeMonitoring() {
        val intent = Intent(this, GuardianRealTimeMonitoringActivity::class.java)
        startActivity(intent)
    }
    
    private fun startQuickSetup() {
        lifecycleScope.launch {
            showQuickSetupDialog()
        }
    }
    
    private fun toggleGuardianSystem(active: Boolean) {
        isGuardianActive = active
        
        lifecycleScope.launch {
            if (active) {
                guardianSystemOrchestrator.activateGuardianSystem()
                updateSystemStatus("🟢 Guardian IA Activo")
                showToast("Sistema Guardian activado")
                
                // Animar avatar
                animateGuardianAvatar(true)
            } else {
                guardianSystemOrchestrator.deactivateGuardianSystem()
                updateSystemStatus("🔴 Guardian IA Inactivo")
                showToast("Sistema Guardian desactivado")
                
                // Animar avatar
                animateGuardianAvatar(false)
            }
            
            updateSystemModulesAvailability(active)
        }
    }
    
    private fun toggleAutoMode(enabled: Boolean) {
        isAutoModeEnabled = enabled
        
        lifecycleScope.launch {
            if (enabled) {
                guardianSystemOrchestrator.enableAutoMode()
                showToast("Modo automático activado")
            } else {
                guardianSystemOrchestrator.disableAutoMode()
                showToast("Modo automático desactivado")
            }
        }
    }
    
    private fun toggleNotifications(enabled: Boolean) {
        lifecycleScope.launch {
            if (enabled) {
                notificationManager.enableNotifications()
                showToast("Notificaciones activadas")
            } else {
                notificationManager.disableNotifications()
                showToast("Notificaciones desactivadas")
            }
        }
    }
    
    private fun onGuardianAvatarClicked() {
        lifecycleScope.launch {
            // Interacción especial con Guardian
            val guardianResponse = guardianSystemOrchestrator.generateAvatarInteraction()
            showGuardianInteractionDialog(guardianResponse)
            
            // Animar avatar
            animateGuardianAvatar(true)
        }
    }
    
    private fun updateWelcomeMessage() {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when {
            currentHour < 12 -> "Buenos días"
            currentHour < 18 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        
        tvWelcomeMessage.text = "$greeting, bienvenido a Guardian IA"
    }
    
    private fun updateSystemStatus(status: String = "") {
        if (status.isNotEmpty()) {
            tvSystemStatus.text = status
        } else {
            tvSystemStatus.text = if (isGuardianActive) {
                "🟢 Sistema Operacional"
            } else {
                "🔴 Sistema Inactivo"
            }
        }
    }
    
    private fun updateProgressBars() {
        progressSystemHealth.progress = (currentSystemHealth * 100).toInt()
        progressSecurityLevel.progress = (currentSecurityLevel * 100).toInt()
        progressPersonalitySync.progress = (currentPersonalitySync * 100).toInt()
    }
    
    private fun updateSystemHealthUI(health: Float) {
        currentSystemHealth = health
        progressSystemHealth.progress = (health * 100).toInt()
        tvSystemHealth.text = "Salud del Sistema: ${(health * 100).toInt()}%"
        
        val color = when {
            health >= 0.9f -> getColor(android.R.color.holo_green_dark)
            health >= 0.7f -> getColor(android.R.color.holo_blue_light)
            health >= 0.5f -> getColor(android.R.color.holo_orange_light)
            else -> getColor(android.R.color.holo_red_dark)
        }
        
        progressSystemHealth.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }
    
    private fun updateSecurityStatusUI(security: Float) {
        currentSecurityLevel = security
        progressSecurityLevel.progress = (security * 100).toInt()
        tvSecurityStatus.text = "Nivel de Seguridad: ${(security * 100).toInt()}%"
        
        val color = when {
            security >= 0.9f -> getColor(android.R.color.holo_green_dark)
            security >= 0.7f -> getColor(android.R.color.holo_blue_light)
            security >= 0.5f -> getColor(android.R.color.holo_orange_light)
            else -> getColor(android.R.color.holo_red_dark)
        }
        
        progressSecurityLevel.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }
    
    private fun updatePersonalitySyncUI(sync: Float) {
        currentPersonalitySync = sync
        progressPersonalitySync.progress = (sync * 100).toInt()
        
        val personalityStatus = when {
            sync >= 0.9f -> "Perfectamente sincronizado"
            sync >= 0.8f -> "Bien sincronizado"
            sync >= 0.7f -> "Sincronización regular"
            sync >= 0.6f -> "Sincronización limitada"
            else -> "Requiere calibración"
        }
        
        tvGuardianPersonality.text = "Personalidad: $personalityStatus"
    }
    
    private fun updateRecentActivitiesUI(activities: List<SystemActivity>) {
        recentActivitiesAdapter.updateActivities(activities)
        
        if (activities.isNotEmpty()) {
            val lastActivity = activities.first()
            tvLastActivity.text = "Última actividad: ${lastActivity.description}"
        }
    }
    
    private fun updateSystemModulesAvailability(systemActive: Boolean) {
        val alpha = if (systemActive) 1.0f else 0.5f
        
        btnMainInterface.alpha = alpha
        btnProtectionCenter.alpha = alpha
        btnCommunicationHub.alpha = alpha
        btnEmotionalBonding.alpha = alpha
        btnPersonalityDesigner.alpha = alpha
        btnAICommunication.alpha = alpha
        btnMusicCreator.alpha = alpha
        btnAdminDashboard.alpha = alpha
        btnAntiTheft.alpha = alpha
        btnConfigurationCenter.alpha = 1.0f // Siempre disponible
        btnRealTimeMonitoring.alpha = alpha
        
        // Habilitar/deshabilitar botones
        btnMainInterface.isEnabled = systemActive
        btnProtectionCenter.isEnabled = systemActive
        btnCommunicationHub.isEnabled = systemActive
        btnEmotionalBonding.isEnabled = systemActive
        btnPersonalityDesigner.isEnabled = systemActive
        btnAICommunication.isEnabled = systemActive
        btnMusicCreator.isEnabled = systemActive
        btnAdminDashboard.isEnabled = systemActive
        btnAntiTheft.isEnabled = systemActive
        btnRealTimeMonitoring.isEnabled = systemActive
    }
    
    private fun animateGuardianAvatar(active: Boolean) {
        if (active) {
            ivGuardianAvatar.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .alpha(1.0f)
                .setDuration(500)
                .withEndAction {
                    ivGuardianAvatar.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(500)
                }
        } else {
            ivGuardianAvatar.animate()
                .alpha(0.6f)
                .setDuration(300)
        }
    }
    
    private suspend fun loadSystemConfiguration() {
        val config = guardianSystemOrchestrator.loadSystemConfiguration()
        
        runOnUiThread {
            // Aplicar configuración cargada
            switchGuardianActive.isChecked = config.systemActive
            switchAutoMode.isChecked = config.autoModeEnabled
            switchNotifications.isChecked = config.notificationsEnabled
            
            isGuardianActive = config.systemActive
            isAutoModeEnabled = config.autoModeEnabled
            
            updateSystemStatus()
            updateSystemModulesAvailability(config.systemActive)
        }
    }
    
    private suspend fun loadDashboardData() {
        // Cargar acciones rápidas
        val quickActions = quickActionsManager.getQuickActions()
        quickActionsAdapter.updateActions(quickActions)
        
        // Cargar módulos del sistema
        val systemModules = mainDashboardManager.getSystemModules()
        systemModulesAdapter.updateModules(systemModules)
        
        // Cargar actividades recientes
        val recentActivities = systemStatusManager.getRecentActivities()
        recentActivitiesAdapter.updateActivities(recentActivities)
        
        // Actualizar estadísticas rápidas
        val quickStats = mainDashboardManager.getQuickStats()
        runOnUiThread {
            updateQuickStats(quickStats)
        }
    }
    
    private suspend fun checkInitialSystemState() {
        val systemState = guardianSystemOrchestrator.checkSystemState()
        
        runOnUiThread {
            if (systemState.hasIssues) {
                showSystemIssuesDialog(systemState.issues)
            }
            
            if (systemState.requiresSetup) {
                showInitialSetupDialog()
            }
            
            updateActiveModulesCount(systemState.activeModules)
        }
    }
    
    private suspend fun showPersonalizedWelcome() {
        val personalizedMessage = guardianSystemOrchestrator.generatePersonalizedWelcome()
        
        runOnUiThread {
            showWelcomeDialog(personalizedMessage)
        }
    }
    
    private fun showGuardianStartupMessage() {
        tvSystemStatus.text = "🔄 Iniciando Guardian IA..."
    }
    
    private fun updateQuickStats(stats: QuickStats) {
        tvQuickStats.text = """
            Módulos activos: ${stats.activeModules}
            Alertas: ${stats.activeAlerts}
            Uptime: ${stats.uptime}
        """.trimIndent()
    }
    
    private fun updateActiveModulesCount(count: Int) {
        tvActiveModules.text = "Módulos Activos: $count"
    }
    
    private fun onQuickActionClicked(action: QuickAction) {
        lifecycleScope.launch {
            quickActionsManager.executeQuickAction(action)
            showToast("Ejecutando: ${action.name}")
        }
    }
    
    private fun onSystemModuleClicked(module: SystemModule) {
        when (module.id) {
            "main_interface" -> navigateToMainInterface()
            "protection_center" -> navigateToProtectionCenter()
            "communication_hub" -> navigateToCommunicationHub()
            "emotional_bonding" -> navigateToEmotionalBonding()
            "personality_designer" -> navigateToPersonalityDesigner()
            "ai_communication" -> navigateToAICommunication()
            "music_creator" -> navigateToMusicCreator()
            "admin_dashboard" -> navigateToAdminDashboard()
            "anti_theft" -> navigateToAntiTheft()
            "configuration_center" -> navigateToConfigurationCenter()
            "real_time_monitoring" -> navigateToRealTimeMonitoring()
        }
    }
    
    private fun onRecentActivityClicked(activity: SystemActivity) {
        showActivityDetailsDialog(activity)
    }
    
    // Métodos de diálogos
    private fun showQuickSetupDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🚀 Configuración Rápida")
            .setMessage("""
                La configuración rápida le ayudará a configurar Guardian IA en pocos pasos:
                
                1. Configuración básica del sistema
                2. Personalización de la IA
                3. Configuración de seguridad
                4. Preferencias de usuario
                5. Activación de módulos
                
                ¿Desea iniciar la configuración rápida?
            """.trimIndent())
            .setPositiveButton("Iniciar Configuración") { _, _ ->
                startQuickSetupWizard()
            }
            .setNegativeButton("Más tarde", null)
            .show()
    }
    
    private fun showGuardianInteractionDialog(response: GuardianInteractionResponse) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🤖 Guardian IA")
            .setMessage("""
                ${response.message}
                
                Estado actual: ${response.currentState}
                Nivel de consciencia: ${(response.consciousnessLevel * 100).toInt()}%
                
                ${response.personalizedNote}
            """.trimIndent())
            .setPositiveButton("Entendido", null)
            .setNeutralButton("Conversar") { _, _ ->
                navigateToAICommunication()
            }
            .show()
    }
    
    private fun showSystemIssuesDialog(issues: List<String>) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Problemas del Sistema")
            .setMessage("""
                Se detectaron los siguientes problemas:
                
                ${issues.joinToString("\n• ", "• ")}
                
                ¿Desea intentar resolverlos automáticamente?
            """.trimIndent())
            .setPositiveButton("Resolver Automáticamente") { _, _ ->
                resolveSystemIssues()
            }
            .setNegativeButton("Resolver Manualmente") { _, _ ->
                navigateToAdminDashboard()
            }
            .setNeutralButton("Más tarde", null)
            .show()
    }
    
    private fun showInitialSetupDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("👋 Bienvenido a Guardian IA")
            .setMessage("""
                Parece que es la primera vez que usa Guardian IA.
                
                Para obtener la mejor experiencia, recomendamos completar la configuración inicial que incluye:
                
                • Configuración de personalidad de IA
                • Configuración de seguridad
                • Personalización de la interfaz
                • Configuración de módulos
                
                ¿Desea completar la configuración ahora?
            """.trimIndent())
            .setPositiveButton("Configurar Ahora") { _, _ ->
                startInitialSetup()
            }
            .setNegativeButton("Configurar Más Tarde") { _, _ ->
                navigateToConfigurationCenter()
            }
            .show()
    }
    
    private fun showWelcomeDialog(message: PersonalizedWelcomeMessage) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🌟 ${message.greeting}")
            .setMessage("""
                ${message.personalizedMessage}
                
                ${message.dailyInsight}
                
                Recomendación del día:
                ${message.dailyRecommendation}
                
                ${message.motivationalQuote}
            """.trimIndent())
            .setPositiveButton("¡Empezar!", null)
            .show()
    }
    
    private fun showActivityDetailsDialog(activity: SystemActivity) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📋 Detalle de Actividad")
            .setMessage("""
                Actividad: ${activity.name}
                Módulo: ${activity.module}
                Timestamp: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(activity.timestamp)}
                
                Descripción: ${activity.description}
                
                Estado: ${activity.status}
                Duración: ${activity.duration}ms
                
                ${if (activity.details != null) "Detalles adicionales:\n${activity.details}" else ""}
            """.trimIndent())
            .setPositiveButton("Cerrar", null)
            .show()
    }
    
    private fun startQuickSetupWizard() {
        // Implementar asistente de configuración rápida
        navigateToConfigurationCenter()
        showToast("Iniciando configuración rápida...")
    }
    
    private fun startInitialSetup() {
        // Implementar configuración inicial
        navigateToConfigurationCenter()
        showToast("Iniciando configuración inicial...")
    }
    
    private fun resolveSystemIssues() {
        lifecycleScope.launch {
            try {
                guardianSystemOrchestrator.resolveSystemIssues()
                showToast("Problemas del sistema resueltos")
            } catch (e: Exception) {
                showToast("Error resolviendo problemas: ${e.message}")
            }
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onResume() {
        super.onResume()
        // Actualizar datos cuando se regresa a la actividad principal
        lifecycleScope.launch {
            loadDashboardData()
        }
    }
    
    override fun onBackPressed() {
        // Mostrar diálogo de confirmación para salir
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Salir de Guardian IA")
            .setMessage("¿Está seguro de que desea salir de Guardian IA?")
            .setPositiveButton("Salir") { _, _ ->
                super.onBackPressed()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    // Clases de datos
    data class SystemConfiguration(
        val systemActive: Boolean,
        val autoModeEnabled: Boolean,
        val notificationsEnabled: Boolean
    )
    
    data class SystemState(
        val hasIssues: Boolean,
        val issues: List<String>,
        val requiresSetup: Boolean,
        val activeModules: Int
    )
    
    data class QuickStats(
        val activeModules: Int,
        val activeAlerts: Int,
        val uptime: String
    )
    
    data class QuickAction(
        val id: String,
        val name: String,
        val description: String,
        val icon: String
    )
    
    data class SystemModule(
        val id: String,
        val name: String,
        val description: String,
        val status: String,
        val icon: String
    )
    
    data class SystemActivity(
        val name: String,
        val module: String,
        val timestamp: Long,
        val description: String,
        val status: String,
        val duration: Long,
        val details: String?
    )
    
    data class GuardianInteractionResponse(
        val message: String,
        val currentState: String,
        val consciousnessLevel: Float,
        val personalizedNote: String
    )
    
    data class PersonalizedWelcomeMessage(
        val greeting: String,
        val personalizedMessage: String,
        val dailyInsight: String,
        val dailyRecommendation: String,
        val motivationalQuote: String
    )
}

