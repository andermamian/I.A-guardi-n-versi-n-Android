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
 * Actividad del Centro de Protección Guardian
 * Trabaja con activity_guardian_protection_center.xml (SIN MODIFICAR EL LAYOUT)
 * Implementa toda la lógica de protección avanzada
 */
class GuardianProtectionCenterActivity : AppCompatActivity() {
    
    // Managers especializados
    private lateinit var protectionManager: ProtectionManager
    private lateinit var securityManager: SecurityManager
    private lateinit var threatDetectionManager: ThreatDetectionManager
    private lateinit var shieldManager: ShieldManager
    private lateinit var quarantineManager: QuarantineManager
    
    // UI Components (basados en tu layout)
    private lateinit var tvProtectionStatus: TextView
    private lateinit var tvActiveThreats: TextView
    private lateinit var tvProtectionLevel: TextView
    private lateinit var progressProtectionHealth: ProgressBar
    private lateinit var switchRealTimeProtection: Switch
    private lateinit var switchFirewall: Switch
    private lateinit var switchAntiMalware: Switch
    private lateinit var switchBehaviorAnalysis: Switch
    private lateinit var btnQuickScan: Button
    private lateinit var btnFullScan: Button
    private lateinit var btnEmergencyProtocol: Button
    private lateinit var rvThreatHistory: RecyclerView
    private lateinit var rvActiveProtections: RecyclerView
    
    // Adapters
    private lateinit var threatHistoryAdapter: ThreatHistoryAdapter
    private lateinit var activeProtectionsAdapter: ActiveProtectionsAdapter
    
    // Estados
    private var isRealTimeProtectionActive = false
    private var currentProtectionLevel = ProtectionLevel.HIGH
    private var activeThreatsCount = 0
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guardian_protection_center) // TU LAYOUT ORIGINAL
        
        initializeComponents()
        initializeManagers()
        setupAdapters()
        setupEventListeners()
        startProtectionMonitoring()
    }
    
    private fun initializeComponents() {
        // Mapear componentes de tu layout original
        tvProtectionStatus = findViewById(R.id.tv_protection_status)
        tvActiveThreats = findViewById(R.id.tv_active_threats)
        tvProtectionLevel = findViewById(R.id.tv_protection_level)
        progressProtectionHealth = findViewById(R.id.progress_protection_health)
        switchRealTimeProtection = findViewById(R.id.switch_realtime_protection)
        switchFirewall = findViewById(R.id.switch_firewall)
        switchAntiMalware = findViewById(R.id.switch_anti_malware)
        switchBehaviorAnalysis = findViewById(R.id.switch_behavior_analysis)
        btnQuickScan = findViewById(R.id.btn_quick_scan)
        btnFullScan = findViewById(R.id.btn_full_scan)
        btnEmergencyProtocol = findViewById(R.id.btn_emergency_protocol)
        rvThreatHistory = findViewById(R.id.rv_threat_history)
        rvActiveProtections = findViewById(R.id.rv_active_protections)
        
        // Configurar estado inicial
        updateProtectionStatus(ProtectionStatus.ACTIVE)
        updateProtectionLevel(ProtectionLevel.HIGH)
    }
    
    private fun initializeManagers() {
        protectionManager = ProtectionManager(this)
        securityManager = SecurityManager(this)
        threatDetectionManager = ThreatDetectionManager(this)
        shieldManager = ShieldManager(this)
        quarantineManager = QuarantineManager(this)
    }
    
    private fun setupAdapters() {
        threatHistoryAdapter = ThreatHistoryAdapter { threat ->
            onThreatItemClicked(threat)
        }
        rvThreatHistory.adapter = threatHistoryAdapter
        
        activeProtectionsAdapter = ActiveProtectionsAdapter { protection ->
            onProtectionItemClicked(protection)
        }
        rvActiveProtections.adapter = activeProtectionsAdapter
    }
    
    private fun setupEventListeners() {
        // Switch listeners
        switchRealTimeProtection.setOnCheckedChangeListener { _, isChecked ->
            toggleRealTimeProtection(isChecked)
        }
        
        switchFirewall.setOnCheckedChangeListener { _, isChecked ->
            toggleFirewall(isChecked)
        }
        
        switchAntiMalware.setOnCheckedChangeListener { _, isChecked ->
            toggleAntiMalware(isChecked)
        }
        
        switchBehaviorAnalysis.setOnCheckedChangeListener { _, isChecked ->
            toggleBehaviorAnalysis(isChecked)
        }
        
        // Button listeners
        btnQuickScan.setOnClickListener {
            performQuickScan()
        }
        
        btnFullScan.setOnClickListener {
            performFullScan()
        }
        
        btnEmergencyProtocol.setOnClickListener {
            activateEmergencyProtocol()
        }
    }
    
    private fun startProtectionMonitoring() {
        lifecycleScope.launch {
            // Monitorear estado de protección
            launch { monitorProtectionStatus() }
            
            // Monitorear amenazas
            launch { monitorThreats() }
            
            // Monitorear salud del sistema
            launch { monitorSystemHealth() }
            
            // Actualizar UI
            launch { updateProtectionUI() }
        }
    }
    
    private suspend fun monitorProtectionStatus() {
        protectionManager.protectionStatus.collect { status ->
            runOnUiThread {
                updateProtectionStatusUI(status)
            }
        }
    }
    
    private suspend fun monitorThreats() {
        threatDetectionManager.threatUpdates.collect { threat ->
            runOnUiThread {
                handleNewThreat(threat)
                updateThreatCounters()
            }
        }
    }
    
    private suspend fun monitorSystemHealth() {
        protectionManager.systemHealth.collect { health ->
            runOnUiThread {
                updateSystemHealthUI(health)
            }
        }
    }
    
    private suspend fun updateProtectionUI() {
        while (true) {
            runOnUiThread {
                // Actualizar contadores
                updateActiveThreatsCount()
                
                // Actualizar lista de protecciones activas
                updateActiveProtectionsList()
                
                // Actualizar historial de amenazas
                updateThreatHistoryList()
            }
            
            delay(5000) // Cada 5 segundos
        }
    }
    
    private fun toggleRealTimeProtection(enabled: Boolean) {
        lifecycleScope.launch {
            if (enabled) {
                protectionManager.enableRealTimeProtection()
                showToast("Protección en tiempo real activada")
            } else {
                protectionManager.disableRealTimeProtection()
                showToast("Protección en tiempo real desactivada")
            }
            
            isRealTimeProtectionActive = enabled
            updateProtectionStatus(if (enabled) ProtectionStatus.ACTIVE else ProtectionStatus.PARTIAL)
        }
    }
    
    private fun toggleFirewall(enabled: Boolean) {
        lifecycleScope.launch {
            if (enabled) {
                securityManager.enableFirewall()
                showToast("Firewall activado")
            } else {
                securityManager.disableFirewall()
                showToast("Firewall desactivado")
            }
        }
    }
    
    private fun toggleAntiMalware(enabled: Boolean) {
        lifecycleScope.launch {
            if (enabled) {
                securityManager.enableAntiMalware()
                showToast("Anti-malware activado")
            } else {
                securityManager.disableAntiMalware()
                showToast("Anti-malware desactivado")
            }
        }
    }
    
    private fun toggleBehaviorAnalysis(enabled: Boolean) {
        lifecycleScope.launch {
            if (enabled) {
                threatDetectionManager.enableBehaviorAnalysis()
                showToast("Análisis de comportamiento activado")
            } else {
                threatDetectionManager.disableBehaviorAnalysis()
                showToast("Análisis de comportamiento desactivado")
            }
        }
    }
    
    private fun performQuickScan() {
        lifecycleScope.launch {
            try {
                btnQuickScan.isEnabled = false
                btnQuickScan.text = "Escaneando..."
                
                val result = securityManager.performQuickScan()
                
                showScanResults(result, "Escaneo Rápido")
                
            } catch (e: Exception) {
                showToast("Error durante el escaneo: ${e.message}")
            } finally {
                btnQuickScan.isEnabled = true
                btnQuickScan.text = "Escaneo Rápido"
            }
        }
    }
    
    private fun performFullScan() {
        lifecycleScope.launch {
            try {
                btnFullScan.isEnabled = false
                btnFullScan.text = "Escaneando..."
                
                val result = securityManager.performFullSystemScan()
                
                showScanResults(result, "Escaneo Completo")
                
            } catch (e: Exception) {
                showToast("Error durante el escaneo: ${e.message}")
            } finally {
                btnFullScan.isEnabled = true
                btnFullScan.text = "Escaneo Completo"
            }
        }
    }
    
    private fun activateEmergencyProtocol() {
        lifecycleScope.launch {
            try {
                // Confirmar con el usuario
                showEmergencyConfirmationDialog { confirmed ->
                    if (confirmed) {
                        lifecycleScope.launch {
                            protectionManager.activateEmergencyProtocol()
                            securityManager.activateEmergencyProtocol()
                            threatDetectionManager.activateEmergencyMode()
                            
                            updateProtectionStatus(ProtectionStatus.EMERGENCY)
                            updateProtectionLevel(ProtectionLevel.MAXIMUM)
                            
                            showToast("Protocolo de emergencia activado")
                        }
                    }
                }
            } catch (e: Exception) {
                showToast("Error al activar protocolo de emergencia: ${e.message}")
            }
        }
    }
    
    private fun updateProtectionStatus(status: ProtectionStatus) {
        tvProtectionStatus.text = when (status) {
            ProtectionStatus.ACTIVE -> "🛡️ PROTECCIÓN ACTIVA"
            ProtectionStatus.PARTIAL -> "⚠️ PROTECCIÓN PARCIAL"
            ProtectionStatus.INACTIVE -> "❌ PROTECCIÓN INACTIVA"
            ProtectionStatus.EMERGENCY -> "🚨 PROTOCOLO DE EMERGENCIA"
            ProtectionStatus.MAINTENANCE -> "🔧 MANTENIMIENTO"
        }
        
        val color = when (status) {
            ProtectionStatus.ACTIVE -> getColor(android.R.color.holo_green_dark)
            ProtectionStatus.PARTIAL -> getColor(android.R.color.holo_orange_light)
            ProtectionStatus.INACTIVE -> getColor(android.R.color.holo_red_dark)
            ProtectionStatus.EMERGENCY -> getColor(android.R.color.holo_red_light)
            ProtectionStatus.MAINTENANCE -> getColor(android.R.color.holo_blue_light)
        }
        
        tvProtectionStatus.setTextColor(color)
    }
    
    private fun updateProtectionLevel(level: ProtectionLevel) {
        currentProtectionLevel = level
        
        tvProtectionLevel.text = when (level) {
            ProtectionLevel.LOW -> "Nivel: Básico"
            ProtectionLevel.MEDIUM -> "Nivel: Medio"
            ProtectionLevel.HIGH -> "Nivel: Alto"
            ProtectionLevel.ULTRA -> "Nivel: Ultra"
            ProtectionLevel.MAXIMUM -> "Nivel: Máximo"
        }
    }
    
    private fun updateProtectionStatusUI(status: ProtectionStatusData) {
        // Actualizar switches según estado actual
        switchRealTimeProtection.isChecked = status.isRealTimeActive
        switchFirewall.isChecked = status.isFirewallActive
        switchAntiMalware.isChecked = status.isAntiMalwareActive
        switchBehaviorAnalysis.isChecked = status.isBehaviorAnalysisActive
        
        // Actualizar nivel de protección
        updateProtectionLevel(status.protectionLevel)
    }
    
    private fun updateSystemHealthUI(health: SystemHealthData) {
        progressProtectionHealth.progress = (health.overallHealth * 100).toInt()
        
        // Cambiar color según salud
        val color = when {
            health.overallHealth >= 0.8f -> getColor(android.R.color.holo_green_dark)
            health.overallHealth >= 0.6f -> getColor(android.R.color.holo_orange_light)
            else -> getColor(android.R.color.holo_red_dark)
        }
        
        progressProtectionHealth.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }
    
    private fun handleNewThreat(threat: ThreatData) {
        activeThreatsCount++
        
        // Mostrar notificación de amenaza
        showThreatNotification(threat)
        
        // Actualizar lista de amenazas
        threatHistoryAdapter.addThreat(threat)
    }
    
    private fun updateThreatCounters() {
        tvActiveThreats.text = "Amenazas Activas: $activeThreatsCount"
    }
    
    private fun updateActiveThreatsCount() {
        lifecycleScope.launch {
            activeThreatsCount = threatDetectionManager.getActiveThreatsCount()
            tvActiveThreats.text = "Amenazas Activas: $activeThreatsCount"
        }
    }
    
    private fun updateActiveProtectionsList() {
        lifecycleScope.launch {
            val protections = protectionManager.getActiveProtections()
            activeProtectionsAdapter.updateProtections(protections)
        }
    }
    
    private fun updateThreatHistoryList() {
        lifecycleScope.launch {
            val threats = threatDetectionManager.getThreatHistory()
            threatHistoryAdapter.updateThreats(threats)
        }
    }
    
    private fun onThreatItemClicked(threat: ThreatData) {
        // Mostrar detalles de la amenaza
        showThreatDetailsDialog(threat)
    }
    
    private fun onProtectionItemClicked(protection: ProtectionData) {
        // Mostrar detalles de la protección
        showProtectionDetailsDialog(protection)
    }
    
    private fun showScanResults(result: ScanResult, scanType: String) {
        val message = """
            $scanType Completado
            
            Amenazas encontradas: ${result.threatsFound}
            Archivos escaneados: ${result.filesScanned}
            Tiempo: ${result.scanDuration}ms
            
            ${if (result.threatsFound > 0) "Se han tomado medidas automáticas." else "Sistema limpio."}
        """.trimIndent()
        
        showResultDialog(scanType, message)
    }
    
    private fun showThreatNotification(threat: ThreatData) {
        val message = "Amenaza detectada: ${threat.name}\nTipo: ${threat.type}\nSeveridad: ${threat.severity}"
        showToast(message)
    }
    
    private fun showEmergencyConfirmationDialog(callback: (Boolean) -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Protocolo de Emergencia")
            .setMessage("¿Está seguro de que desea activar el protocolo de emergencia? Esto bloqueará todas las conexiones sospechosas y activará la protección máxima.")
            .setPositiveButton("Activar") { _, _ -> callback(true) }
            .setNegativeButton("Cancelar") { _, _ -> callback(false) }
            .show()
    }
    
    private fun showThreatDetailsDialog(threat: ThreatData) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Detalles de Amenaza")
            .setMessage("""
                Nombre: ${threat.name}
                Tipo: ${threat.type}
                Severidad: ${threat.severity}
                Origen: ${threat.source}
                Detectado: ${threat.detectionTime}
                Estado: ${threat.status}
                
                Descripción: ${threat.description}
            """.trimIndent())
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Bloquear") { _, _ ->
                lifecycleScope.launch {
                    securityManager.blockThreat(threat.toThreatInfo())
                    showToast("Amenaza bloqueada")
                }
            }
            .show()
    }
    
    private fun showProtectionDetailsDialog(protection: ProtectionData) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Detalles de Protección")
            .setMessage("""
                Nombre: ${protection.name}
                Estado: ${protection.status}
                Nivel: ${protection.level}
                Última actualización: ${protection.lastUpdate}
                
                Descripción: ${protection.description}
            """.trimIndent())
            .setPositiveButton("Cerrar", null)
            .show()
    }
    
    private fun showResultDialog(title: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    // Enums y clases de datos
    enum class ProtectionStatus { ACTIVE, PARTIAL, INACTIVE, EMERGENCY, MAINTENANCE }
    enum class ProtectionLevel { LOW, MEDIUM, HIGH, ULTRA, MAXIMUM }
    
    data class ProtectionStatusData(
        val isRealTimeActive: Boolean,
        val isFirewallActive: Boolean,
        val isAntiMalwareActive: Boolean,
        val isBehaviorAnalysisActive: Boolean,
        val protectionLevel: ProtectionLevel
    )
    
    data class SystemHealthData(
        val overallHealth: Float,
        val cpuUsage: Float,
        val memoryUsage: Float,
        val networkStatus: String
    )
    
    data class ThreatData(
        val id: String,
        val name: String,
        val type: String,
        val severity: String,
        val source: String,
        val detectionTime: String,
        val status: String,
        val description: String
    ) {
        fun toThreatInfo(): ThreatInfo {
            return ThreatInfo(id, type, severity, description, source)
        }
    }
    
    data class ProtectionData(
        val name: String,
        val status: String,
        val level: String,
        val lastUpdate: String,
        val description: String
    )
    
    data class ScanResult(
        val threatsFound: Int,
        val filesScanned: Int,
        val scanDuration: Long
    )
    
    data class ThreatInfo(
        val id: String,
        val type: String,
        val severity: String,
        val description: String,
        val source: String
    )
}

