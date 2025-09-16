package com.guardianai.vpn

import android.content.Context
import android.util.Log
import com.guardianai.BuildConfig
import com.guardianai.membership.MembershipStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Gestor de servidores VPN para Guardian IA
 * Maneja la lista de servidores disponibles y su estado
 */
@Singleton
class VPNServerManager @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "VPNServerManager"
    }

    private val vpnApi = Retrofit.Builder()
        .baseUrl(BuildConfig.VPN_AI_API_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(VPNApiService::class.java)

    private val _availableServers = MutableStateFlow<List<VPNServer>>(emptyList())
    val availableServers: StateFlow<List<VPNServer>> = _availableServers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadDefaultServers()
    }

    /**
     * Carga servidores por defecto (offline)
     */
    private fun loadDefaultServers() {
        val defaultServers = listOf(
            // Servidores básicos (Premium)
            VPNServer(
                id = "us-east-1",
                location = "Estados Unidos - Este",
                country = "US",
                city = "Nueva York",
                host = "us-east.guardianai-vpn.com",
                port = 1194,
                protocol = "OpenVPN",
                latency = Random.nextLong(20, 50),
                load = Random.nextFloat() * 0.7f,
                isPremium = false
            ),
            VPNServer(
                id = "eu-west-1",
                location = "Reino Unido",
                country = "GB",
                city = "Londres",
                host = "uk.guardianai-vpn.com",
                port = 1194,
                protocol = "OpenVPN",
                latency = Random.nextLong(30, 60),
                load = Random.nextFloat() * 0.6f,
                isPremium = false
            ),
            VPNServer(
                id = "asia-1",
                location = "Japón",
                country = "JP",
                city = "Tokio",
                host = "jp.guardianai-vpn.com",
                port = 1194,
                protocol = "OpenVPN",
                latency = Random.nextLong(80, 120),
                load = Random.nextFloat() * 0.5f,
                isPremium = false
            ),
            VPNServer(
                id = "ca-1",
                location = "Canadá",
                country = "CA",
                city = "Toronto",
                host = "ca.guardianai-vpn.com",
                port = 1194,
                protocol = "OpenVPN",
                latency = Random.nextLong(25, 45),
                load = Random.nextFloat() * 0.4f,
                isPremium = false
            ),
            VPNServer(
                id = "au-1",
                location = "Australia",
                country = "AU",
                city = "Sídney",
                host = "au.guardianai-vpn.com",
                port = 1194,
                protocol = "OpenVPN",
                latency = Random.nextLong(150, 200),
                load = Random.nextFloat() * 0.6f,
                isPremium = false
            ),

            // Servidores premium (Enterprise)
            VPNServer(
                id = "us-west-premium",
                location = "Estados Unidos - Oeste Premium",
                country = "US",
                city = "Los Ángeles",
                host = "us-west-premium.guardianai-vpn.com",
                port = 443,
                protocol = "WireGuard",
                latency = Random.nextLong(15, 30),
                load = Random.nextFloat() * 0.3f,
                isPremium = true
            ),
            VPNServer(
                id = "de-premium",
                location = "Alemania Premium",
                country = "DE",
                city = "Berlín",
                host = "de-premium.guardianai-vpn.com",
                port = 443,
                protocol = "WireGuard",
                latency = Random.nextLong(20, 40),
                load = Random.nextFloat() * 0.2f,
                isPremium = true
            ),
            VPNServer(
                id = "sg-premium",
                location = "Singapur Premium",
                country = "SG",
                city = "Singapur",
                host = "sg-premium.guardianai-vpn.com",
                port = 443,
                protocol = "WireGuard",
                latency = Random.nextLong(60, 90),
                load = Random.nextFloat() * 0.25f,
                isPremium = true
            ),
            VPNServer(
                id = "nl-premium",
                location = "Países Bajos Premium",
                country = "NL",
                city = "Ámsterdam",
                host = "nl-premium.guardianai-vpn.com",
                port = 443,
                protocol = "WireGuard",
                latency = Random.nextLong(25, 45),
                load = Random.nextFloat() * 0.3f,
                isPremium = true
            ),
            VPNServer(
                id = "ch-premium",
                location = "Suiza Premium",
                country = "CH",
                city = "Zúrich",
                host = "ch-premium.guardianai-vpn.com",
                port = 443,
                protocol = "WireGuard",
                latency = Random.nextLong(30, 50),
                load = Random.nextFloat() * 0.2f,
                isPremium = true
            )
        )

        _availableServers.value = defaultServers
        Log.d(TAG, "Cargados ${defaultServers.size} servidores por defecto")
    }

    /**
     * Actualiza la lista de servidores desde el servidor
     */
    suspend fun refreshServers() {
        _isLoading.value = true
        try {
            withContext(Dispatchers.IO) {
                // Intentar obtener servidores del API
                try {
                    val response = vpnApi.getAvailableServers()
                    if (response.isNotEmpty()) {
                        _availableServers.value = response
                        Log.d(TAG, "Servidores actualizados desde API: ${response.size}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo conectar al API, usando servidores por defecto: ${e.message}")
                    // Mantener servidores por defecto
                }
                
                // Actualizar latencias
                updateServerLatencies()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al actualizar servidores: ${e.message}", e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Obtiene servidores disponibles según el nivel de membresía
     */
    fun getServersForMembership(membership: MembershipStatus): List<VPNServer> {
        val allServers = _availableServers.value
        
        return when (membership) {
            MembershipStatus.BASIC -> emptyList() // Sin acceso a VPN
            MembershipStatus.PREMIUM -> allServers.filter { !it.isPremium }.take(5)
            MembershipStatus.ENTERPRISE -> allServers // Acceso completo
        }
    }

    /**
     * Obtiene un servidor por ubicación
     */
    fun getServerByLocation(location: String): VPNServer? {
        return _availableServers.value.find { 
            it.location.equals(location, ignoreCase = true) || 
            it.id.equals(location, ignoreCase = true) 
        }
    }

    /**
     * Obtiene servidores por país
     */
    fun getServersByCountry(countryCode: String): List<VPNServer> {
        return _availableServers.value.filter { 
            it.country.equals(countryCode, ignoreCase = true) 
        }
    }

    /**
     * Obtiene el servidor más rápido disponible
     */
    fun getFastestServer(membership: MembershipStatus): VPNServer? {
        val availableServers = getServersForMembership(membership)
        return availableServers.minByOrNull { it.latency }
    }

    /**
     * Obtiene el servidor con menor carga
     */
    fun getLeastLoadedServer(membership: MembershipStatus): VPNServer? {
        val availableServers = getServersForMembership(membership)
        return availableServers.minByOrNull { it.load }
    }

    /**
     * Actualiza las latencias de los servidores
     */
    private suspend fun updateServerLatencies() {
        val servers = _availableServers.value.toMutableList()
        
        servers.forEachIndexed { index, server ->
            try {
                // Simular ping al servidor (en implementación real, hacer ping real)
                val latency = simulatePing(server.host)
                servers[index] = server.copy(latency = latency)
            } catch (e: Exception) {
                Log.w(TAG, "Error al hacer ping a ${server.host}: ${e.message}")
            }
        }
        
        _availableServers.value = servers
    }

    /**
     * Simula un ping al servidor (implementación simplificada)
     */
    private suspend fun simulatePing(host: String): Long {
        return withContext(Dispatchers.IO) {
            try {
                // En implementación real, usar InetAddress.getByName(host).isReachable()
                // Por ahora, simular latencia basada en la ubicación
                when {
                    host.contains("us-") -> Random.nextLong(20, 60)
                    host.contains("eu-") || host.contains("uk") || host.contains("de") || host.contains("nl") || host.contains("ch") -> Random.nextLong(30, 70)
                    host.contains("asia") || host.contains("jp") || host.contains("sg") -> Random.nextLong(80, 150)
                    host.contains("au") -> Random.nextLong(150, 250)
                    host.contains("ca") -> Random.nextLong(25, 55)
                    else -> Random.nextLong(50, 100)
                }
            } catch (e: Exception) {
                999L // Latencia alta si hay error
            }
        }
    }

    /**
     * Obtiene estadísticas de los servidores
     */
    fun getServerStats(): VPNServerStats {
        val servers = _availableServers.value
        val totalServers = servers.size
        val premiumServers = servers.count { it.isPremium }
        val basicServers = totalServers - premiumServers
        val averageLatency = if (servers.isNotEmpty()) {
            servers.map { it.latency }.average().toLong()
        } else 0L
        val averageLoad = if (servers.isNotEmpty()) {
            servers.map { it.load }.average().toFloat()
        } else 0f

        return VPNServerStats(
            totalServers = totalServers,
            basicServers = basicServers,
            premiumServers = premiumServers,
            averageLatency = averageLatency,
            averageLoad = averageLoad,
            onlineServers = servers.count { it.latency < 500 }
        )
    }
}

/**
 * API para obtener servidores VPN
 */
interface VPNApiService {
    @GET("servers")
    suspend fun getAvailableServers(): List<VPNServer>
    
    @GET("servers/ping")
    suspend fun pingServer(@Query("serverId") serverId: String): Long
}

/**
 * Estadísticas de servidores VPN
 */
data class VPNServerStats(
    val totalServers: Int,
    val basicServers: Int,
    val premiumServers: Int,
    val averageLatency: Long,
    val averageLoad: Float,
    val onlineServers: Int
)

