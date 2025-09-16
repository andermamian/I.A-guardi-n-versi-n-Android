package com.guardianai.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.util.Log
import com.guardianai.membership.MembershipStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * Optimizador IA para VPN Guardian IA
 * Implementa algoritmos de machine learning para optimizar la conexión VPN
 */
@Singleton
class VPNAIOptimizer @Inject constructor(
    private val context: Context,
    private val serverManager: VPNServerManager
) {

    companion object {
        private const val TAG = "VPNAIOptimizer"
        private const val OPTIMIZATION_INTERVAL = 30_000L // 30 segundos
        private const val LEARNING_RATE = 0.01f
        private const val MIN_SAMPLES_FOR_LEARNING = 10
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val _optimizationStatus = MutableStateFlow(OptimizationStatus.IDLE)
    val optimizationStatus: StateFlow<OptimizationStatus> = _optimizationStatus.asStateFlow()

    private val _connectionMetrics = MutableStateFlow(ConnectionMetrics())
    val connectionMetrics: StateFlow<ConnectionMetrics> = _connectionMetrics.asStateFlow()

    private var optimizationJob: Job? = null
    private var currentServer: VPNServer? = null
    
    // Datos para machine learning
    private val networkHistory = mutableListOf<NetworkSample>()
    private val serverPerformance = mutableMapOf<String, ServerPerformanceData>()
    
    // Modelo de red neuronal simple
    private val neuralNetwork = SimpleNeuralNetwork()

    /**
     * Selecciona el servidor óptimo usando IA
     */
    suspend fun selectOptimalServer(membership: MembershipStatus): VPNServer? {
        return withContext(Dispatchers.IO) {
            try {
                val availableServers = serverManager.getServersForMembership(membership)
                if (availableServers.isEmpty()) {
                    Log.w(TAG, "No hay servidores disponibles para la membresía: $membership")
                    return@withContext null
                }

                // Obtener contexto de red actual
                val networkContext = getCurrentNetworkContext()
                
                // Calcular puntuación para cada servidor
                val serverScores = availableServers.map { server ->
                    val score = calculateServerScore(server, networkContext, membership)
                    server to score
                }.sortedByDescending { it.second }

                val selectedServer = serverScores.firstOrNull()?.first
                
                Log.d(TAG, "Servidor seleccionado: ${selectedServer?.location} (Score: ${serverScores.firstOrNull()?.second})")
                
                selectedServer
                
            } catch (e: Exception) {
                Log.e(TAG, "Error al seleccionar servidor óptimo: ${e.message}", e)
                // Fallback al servidor más rápido
                serverManager.getFastestServer(membership)
            }
        }
    }

    /**
     * Calcula la puntuación de un servidor usando múltiples factores
     */
    private fun calculateServerScore(
        server: VPNServer, 
        networkContext: NetworkContext, 
        membership: MembershipStatus
    ): Float {
        var score = 0f
        
        // Factor de latencia (peso: 30%)
        val latencyScore = when {
            server.latency < 50 -> 1.0f
            server.latency < 100 -> 0.8f
            server.latency < 200 -> 0.6f
            else -> 0.3f
        }
        score += latencyScore * 0.3f
        
        // Factor de carga del servidor (peso: 25%)
        val loadScore = 1.0f - server.load
        score += loadScore * 0.25f
        
        // Factor de protocolo (peso: 15%)
        val protocolScore = when (server.protocol) {
            "WireGuard" -> 1.0f
            "OpenVPN" -> 0.8f
            else -> 0.6f
        }
        score += protocolScore * 0.15f
        
        // Factor de ubicación geográfica (peso: 20%)
        val locationScore = calculateLocationScore(server, networkContext)
        score += locationScore * 0.2f
        
        // Factor de membresía (peso: 10%)
        val membershipScore = when {
            server.isPremium && membership == MembershipStatus.ENTERPRISE -> 1.0f
            !server.isPremium && membership == MembershipStatus.PREMIUM -> 0.9f
            else -> 0.7f
        }
        score += membershipScore * 0.1f
        
        // Aplicar machine learning si hay suficientes datos
        if (networkHistory.size >= MIN_SAMPLES_FOR_LEARNING) {
            val mlScore = neuralNetwork.predict(server, networkContext)
            score = score * 0.7f + mlScore * 0.3f // Combinar con ML
        }
        
        return score
    }

    /**
     * Calcula puntuación basada en ubicación geográfica
     */
    private fun calculateLocationScore(server: VPNServer, networkContext: NetworkContext): Float {
        // Preferir servidores en la misma región
        val userRegion = getUserRegion()
        val serverRegion = getServerRegion(server.country)
        
        return when {
            userRegion == serverRegion -> 1.0f
            isNearbyRegion(userRegion, serverRegion) -> 0.8f
            else -> 0.6f
        }
    }

    /**
     * Inicia la optimización continua
     */
    fun startOptimization(server: VPNServer) {
        currentServer = server
        _optimizationStatus.value = OptimizationStatus.OPTIMIZING
        
        optimizationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    performOptimizationCycle()
                    delay(OPTIMIZATION_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en ciclo de optimización: ${e.message}", e)
                    delay(OPTIMIZATION_INTERVAL * 2) // Esperar más tiempo si hay error
                }
            }
        }
        
        Log.d(TAG, "Optimización iniciada para servidor: ${server.location}")
    }

    /**
     * Detiene la optimización
     */
    fun stopOptimization() {
        optimizationJob?.cancel()
        optimizationJob = null
        currentServer = null
        _optimizationStatus.value = OptimizationStatus.IDLE
        
        Log.d(TAG, "Optimización detenida")
    }

    /**
     * Realiza un ciclo de optimización
     */
    private suspend fun performOptimizationCycle() {
        val server = currentServer ?: return
        
        // Recopilar métricas actuales
        val metrics = collectConnectionMetrics()
        _connectionMetrics.value = metrics
        
        // Registrar muestra para aprendizaje
        val networkSample = NetworkSample(
            timestamp = System.currentTimeMillis(),
            serverId = server.id,
            latency = metrics.latency,
            throughput = metrics.throughput,
            packetLoss = metrics.packetLoss,
            networkType = getCurrentNetworkType(),
            signalStrength = getSignalStrength()
        )
        
        networkHistory.add(networkSample)
        
        // Mantener solo las últimas 1000 muestras
        if (networkHistory.size > 1000) {
            networkHistory.removeAt(0)
        }
        
        // Actualizar datos de rendimiento del servidor
        updateServerPerformance(server.id, metrics)
        
        // Entrenar modelo si hay suficientes datos
        if (networkHistory.size >= MIN_SAMPLES_FOR_LEARNING && 
            networkHistory.size % 50 == 0) { // Entrenar cada 50 muestras
            trainNeuralNetwork()
        }
        
        // Verificar si necesita cambiar de servidor
        if (shouldSwitchServer(metrics)) {
            Log.i(TAG, "Recomendando cambio de servidor debido a bajo rendimiento")
            // Aquí se podría notificar al servicio VPN para cambiar servidor
        }
    }

    /**
     * Recopila métricas de conexión actuales
     */
    private fun collectConnectionMetrics(): ConnectionMetrics {
        // En implementación real, obtener métricas reales de la conexión VPN
        // Por ahora, simular métricas
        return ConnectionMetrics(
            latency = Random.nextLong(20, 200),
            throughput = Random.nextFloat() * 100f, // Mbps
            packetLoss = Random.nextFloat() * 0.05f, // 0-5%
            jitter = Random.nextLong(1, 20),
            connectionTime = System.currentTimeMillis() - (Random.nextLong(60, 3600) * 1000),
            bytesTransferred = Random.nextLong(1024 * 1024, 1024 * 1024 * 100) // 1MB - 100MB
        )
    }

    /**
     * Procesa y optimiza un paquete VPN
     */
    suspend fun optimizePacket(packet: ByteBuffer, server: VPNServer) {
        withContext(Dispatchers.IO) {
            try {
                // Análisis del paquete
                val packetInfo = analyzePacket(packet)
                
                // Aplicar optimizaciones según el tipo de tráfico
                when (packetInfo.type) {
                    PacketType.HTTP -> optimizeHTTPPacket(packet)
                    PacketType.HTTPS -> optimizeHTTPSPacket(packet)
                    PacketType.VIDEO -> optimizeVideoPacket(packet)
                    PacketType.GAMING -> optimizeGamingPacket(packet)
                    else -> optimizeGenericPacket(packet)
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Error al optimizar paquete: ${e.message}")
            }
        }
    }

    /**
     * Determina si debe conectarse automáticamente
     */
    fun shouldAutoConnect(): Boolean {
        val networkContext = getCurrentNetworkContext()
        
        // Auto-conectar en redes WiFi públicas
        if (networkContext.isPublicWifi) {
            Log.d(TAG, "Auto-conexión recomendada: Red WiFi pública detectada")
            return true
        }
        
        // Auto-conectar si la red es insegura
        if (!networkContext.isSecure) {
            Log.d(TAG, "Auto-conexión recomendada: Red insegura detectada")
            return true
        }
        
        return false
    }

    // Métodos auxiliares privados
    
    private fun getCurrentNetworkContext(): NetworkContext {
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        val isWifi = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isMetered = connectivityManager.isActiveNetworkMetered
        
        val isPublicWifi = isWifi && isPublicWifiNetwork()
        val isSecure = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == true
        
        return NetworkContext(
            isWifi = isWifi,
            isCellular = isCellular,
            isMetered = isMetered,
            isPublicWifi = isPublicWifi,
            isSecure = isSecure,
            signalStrength = getSignalStrength()
        )
    }

    private fun isPublicWifiNetwork(): Boolean {
        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo?.ssid?.replace("\"", "") ?: ""
        
        // Lista de SSIDs comunes de WiFi público
        val publicWifiPatterns = listOf(
            "free", "public", "guest", "open", "hotel", "airport", 
            "starbucks", "mcdonalds", "mall", "library"
        )
        
        return publicWifiPatterns.any { pattern ->
            ssid.contains(pattern, ignoreCase = true)
        }
    }

    private fun getSignalStrength(): Int {
        return try {
            when {
                wifiManager.connectionInfo != null -> {
                    val rssi = wifiManager.connectionInfo.rssi
                    WifiManager.calculateSignalLevel(rssi, 5) // 0-4 scale
                }
                else -> {
                    // Para datos móviles, usar TelephonyManager
                    Random.nextInt(0, 5)
                }
            }
        } catch (e: Exception) {
            2 // Valor por defecto
        }
    }

    private fun getCurrentNetworkType(): String {
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        return when {
            networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Unknown"
        }
    }

    private fun getUserRegion(): String {
        return Locale.getDefault().country
    }

    private fun getServerRegion(countryCode: String): String {
        return when (countryCode) {
            "US", "CA" -> "North America"
            "GB", "DE", "NL", "CH", "FR", "IT", "ES" -> "Europe"
            "JP", "SG", "KR", "CN" -> "Asia"
            "AU", "NZ" -> "Oceania"
            "BR", "AR", "MX" -> "South America"
            else -> "Other"
        }
    }

    private fun isNearbyRegion(userRegion: String, serverRegion: String): Boolean {
        val regionGroups = mapOf(
            "North America" to listOf("US", "CA", "MX"),
            "Europe" to listOf("GB", "DE", "FR", "IT", "ES", "NL", "CH"),
            "Asia" to listOf("JP", "SG", "KR", "CN", "IN"),
            "Oceania" to listOf("AU", "NZ")
        )
        
        return regionGroups.values.any { group ->
            group.contains(userRegion) && group.contains(serverRegion)
        }
    }

    private fun updateServerPerformance(serverId: String, metrics: ConnectionMetrics) {
        val existing = serverPerformance[serverId] ?: ServerPerformanceData()
        
        serverPerformance[serverId] = existing.copy(
            averageLatency = (existing.averageLatency + metrics.latency) / 2,
            averageThroughput = (existing.averageThroughput + metrics.throughput) / 2,
            averagePacketLoss = (existing.averagePacketLoss + metrics.packetLoss) / 2,
            connectionCount = existing.connectionCount + 1,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun shouldSwitchServer(metrics: ConnectionMetrics): Boolean {
        return metrics.latency > 300 || // Latencia muy alta
               metrics.packetLoss > 0.1f || // Pérdida de paquetes > 10%
               metrics.throughput < 1.0f // Throughput muy bajo
    }

    private fun trainNeuralNetwork() {
        // Implementación simplificada de entrenamiento
        Log.d(TAG, "Entrenando red neuronal con ${networkHistory.size} muestras")
        
        val trainingData = networkHistory.takeLast(100) // Usar últimas 100 muestras
        neuralNetwork.train(trainingData)
    }

    private fun analyzePacket(packet: ByteBuffer): PacketInfo {
        // Análisis simplificado del paquete
        val size = packet.remaining()
        val type = when {
            size < 100 -> PacketType.CONTROL
            size < 1500 -> PacketType.HTTP
            else -> PacketType.DATA
        }
        
        return PacketInfo(type, size)
    }

    private fun optimizeHTTPPacket(packet: ByteBuffer) {
        // Optimizaciones específicas para HTTP
    }

    private fun optimizeHTTPSPacket(packet: ByteBuffer) {
        // Optimizaciones específicas para HTTPS
    }

    private fun optimizeVideoPacket(packet: ByteBuffer) {
        // Optimizaciones específicas para video
    }

    private fun optimizeGamingPacket(packet: ByteBuffer) {
        // Optimizaciones específicas para gaming
    }

    private fun optimizeGenericPacket(packet: ByteBuffer) {
        // Optimizaciones genéricas
    }
}

// Clases de datos auxiliares

enum class OptimizationStatus {
    IDLE, OPTIMIZING, ERROR
}

data class ConnectionMetrics(
    val latency: Long = 0,
    val throughput: Float = 0f, // Mbps
    val packetLoss: Float = 0f, // Porcentaje
    val jitter: Long = 0,
    val connectionTime: Long = 0,
    val bytesTransferred: Long = 0
)

data class NetworkContext(
    val isWifi: Boolean,
    val isCellular: Boolean,
    val isMetered: Boolean,
    val isPublicWifi: Boolean,
    val isSecure: Boolean,
    val signalStrength: Int
)

data class NetworkSample(
    val timestamp: Long,
    val serverId: String,
    val latency: Long,
    val throughput: Float,
    val packetLoss: Float,
    val networkType: String,
    val signalStrength: Int
)

data class ServerPerformanceData(
    val averageLatency: Long = 0,
    val averageThroughput: Float = 0f,
    val averagePacketLoss: Float = 0f,
    val connectionCount: Int = 0,
    val lastUpdated: Long = 0
)

enum class PacketType {
    HTTP, HTTPS, VIDEO, GAMING, CONTROL, DATA
}

data class PacketInfo(
    val type: PacketType,
    val size: Int
)

/**
 * Red neuronal simple para optimización VPN
 */
class SimpleNeuralNetwork {
    private val weights = FloatArray(10) { Random.nextFloat() }
    private val bias = Random.nextFloat()
    
    fun predict(server: VPNServer, context: NetworkContext): Float {
        val inputs = floatArrayOf(
            server.latency.toFloat() / 1000f,
            server.load,
            if (server.isPremium) 1f else 0f,
            if (context.isWifi) 1f else 0f,
            if (context.isCellular) 1f else 0f,
            context.signalStrength.toFloat() / 5f,
            if (context.isPublicWifi) 1f else 0f,
            if (context.isSecure) 1f else 0f,
            if (context.isMetered) 1f else 0f,
            Random.nextFloat() // Factor aleatorio
        )
        
        var sum = bias
        for (i in inputs.indices) {
            sum += inputs[i] * weights[i]
        }
        
        return sigmoid(sum)
    }
    
    fun train(samples: List<NetworkSample>) {
        // Implementación simplificada de entrenamiento
        // En implementación real, usar backpropagation
    }
    
    private fun sigmoid(x: Float): Float {
        return (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
    }
}

