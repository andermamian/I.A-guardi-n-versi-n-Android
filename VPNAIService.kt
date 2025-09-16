package com.guardianai.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.guardianai.R
import com.guardianai.activities.MainActivity
import com.guardianai.membership.MembershipManager
import com.guardianai.membership.MembershipStatus
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import javax.inject.Inject

/**
 * Servicio VPN IA inteligente para Guardian IA
 * Proporciona conexión VPN con selección automática de servidores y optimización IA
 */
class VPNAIService : VpnService() {

    companion object {
        private const val TAG = "VPNAIService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vpn_ai_channel"
        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        
        // Estados de conexión
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        const val STATE_DISCONNECTING = 3
    }

    @Inject
    lateinit var membershipManager: MembershipManager
    
    @Inject
    lateinit var vpnServerManager: VPNServerManager
    
    @Inject
    lateinit var vpnAIOptimizer: VPNAIOptimizer

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var currentState = STATE_DISCONNECTED
    private var currentServer: VPNServer? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "VPN IA Service creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val serverLocation = intent.getStringExtra(EXTRA_SERVER_LOCATION)
                connectVPN(serverLocation)
            }
            ACTION_DISCONNECT -> {
                disconnectVPN()
            }
            ACTION_AUTO_CONNECT -> {
                autoConnectVPN()
            }
        }
        return START_STICKY
    }

    private fun connectVPN(serverLocation: String?) {
        if (currentState == STATE_CONNECTING || currentState == STATE_CONNECTED) {
            Log.w(TAG, "VPN ya está conectado o conectando")
            return
        }

        serviceScope.launch {
            try {
                currentState = STATE_CONNECTING
                updateNotification("Conectando VPN IA...", STATE_CONNECTING)
                
                // Verificar membresía
                if (!hasVPNAccess()) {
                    Log.w(TAG, "Usuario no tiene acceso a VPN")
                    currentState = STATE_DISCONNECTED
                    updateNotification("VPN requiere membresía Premium", STATE_DISCONNECTED)
                    return@launch
                }

                // Seleccionar servidor óptimo
                currentServer = if (serverLocation != null) {
                    vpnServerManager.getServerByLocation(serverLocation)
                } else {
                    vpnAIOptimizer.selectOptimalServer(getUserMembershipLevel())
                }

                if (currentServer == null) {
                    Log.e(TAG, "No se pudo seleccionar servidor VPN")
                    currentState = STATE_DISCONNECTED
                    updateNotification("Error: No hay servidores disponibles", STATE_DISCONNECTED)
                    return@launch
                }

                // Establecer conexión VPN
                establishVPNConnection()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error al conectar VPN: ${e.message}", e)
                currentState = STATE_DISCONNECTED
                updateNotification("Error de conexión VPN", STATE_DISCONNECTED)
            }
        }
    }

    private fun establishVPNConnection() {
        try {
            // Configurar interfaz VPN
            val builder = Builder()
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setSession("Guardian IA VPN")
                .setConfigureIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )

            // Aplicar configuraciones específicas según membresía
            when (getUserMembershipLevel()) {
                MembershipStatus.PREMIUM -> {
                    builder.setBlocking(false)
                }
                MembershipStatus.ENTERPRISE -> {
                    builder.setBlocking(false)
                    builder.setUnderlyingNetworks(null) // Usar todas las redes disponibles
                }
                else -> {
                    // Básico no tiene acceso
                    return
                }
            }

            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                currentState = STATE_CONNECTED
                updateNotification("Conectado a ${currentServer?.location}", STATE_CONNECTED)
                startVPNThread()
                
                // Iniciar optimización IA
                vpnAIOptimizer.startOptimization(currentServer!!)
                
                Log.i(TAG, "VPN conectado exitosamente a ${currentServer?.location}")
            } else {
                throw Exception("No se pudo establecer la interfaz VPN")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error al establecer conexión VPN: ${e.message}", e)
            currentState = STATE_DISCONNECTED
            updateNotification("Error de conexión", STATE_DISCONNECTED)
        }
    }

    private fun startVPNThread() {
        isRunning = true
        vpnThread = Thread {
            try {
                val vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
                val vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
                val buffer = ByteBuffer.allocate(VPN_MTU)
                
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    // Leer datos del túnel VPN
                    val length = vpnInput.read(buffer.array())
                    if (length > 0) {
                        // Procesar paquetes con IA
                        buffer.limit(length)
                        processVPNPacket(buffer)
                        buffer.clear()
                    }
                }
                
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Error en hilo VPN: ${e.message}", e)
                }
            }
        }
        vpnThread?.start()
    }

    private fun processVPNPacket(packet: ByteBuffer) {
        // Aquí se implementaría el procesamiento inteligente de paquetes
        // Por ahora, simplemente reenviar el paquete
        serviceScope.launch {
            vpnAIOptimizer.optimizePacket(packet, currentServer!!)
        }
    }

    private fun autoConnectVPN() {
        serviceScope.launch {
            try {
                // Verificar si debe conectarse automáticamente
                if (vpnAIOptimizer.shouldAutoConnect()) {
                    val optimalServer = vpnAIOptimizer.selectOptimalServer(getUserMembershipLevel())
                    if (optimalServer != null) {
                        connectVPN(optimalServer.location)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en auto-conexión: ${e.message}", e)
            }
        }
    }

    private fun disconnectVPN() {
        if (currentState == STATE_DISCONNECTED || currentState == STATE_DISCONNECTING) {
            return
        }

        currentState = STATE_DISCONNECTING
        updateNotification("Desconectando VPN...", STATE_DISCONNECTING)

        try {
            isRunning = false
            vpnThread?.interrupt()
            vpnInterface?.close()
            vpnInterface = null
            
            vpnAIOptimizer.stopOptimization()
            
            currentState = STATE_DISCONNECTED
            currentServer = null
            
            updateNotification("VPN desconectado", STATE_DISCONNECTED)
            stopForeground(true)
            stopSelf()
            
            Log.i(TAG, "VPN desconectado exitosamente")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error al desconectar VPN: ${e.message}", e)
        }
    }

    private fun hasVPNAccess(): Boolean {
        val membership = membershipManager.membershipStatus.value
        return membership == MembershipStatus.PREMIUM || membership == MembershipStatus.ENTERPRISE
    }

    private fun getUserMembershipLevel(): MembershipStatus {
        return membershipManager.membershipStatus.value
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN IA Guardian",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificaciones del servicio VPN IA"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(message: String, state: Int) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = when (state) {
            STATE_CONNECTED -> R.drawable.ic_vpn_connected
            STATE_CONNECTING -> R.drawable.ic_vpn_connecting
            else -> R.drawable.ic_vpn_disconnected
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guardian IA VPN")
            .setContentText(message)
            .setSmallIcon(iconRes)
            .setContentIntent(pendingIntent)
            .setOngoing(state == STATE_CONNECTED || state == STATE_CONNECTING)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectVPN()
        serviceScope.cancel()
        Log.d(TAG, "VPN IA Service destruido")
    }

    // Métodos estáticos para controlar el servicio
    companion object {
        const val ACTION_CONNECT = "com.guardianai.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.guardianai.vpn.DISCONNECT"
        const val ACTION_AUTO_CONNECT = "com.guardianai.vpn.AUTO_CONNECT"
        const val EXTRA_SERVER_LOCATION = "server_location"

        fun startVPN(context: Context, serverLocation: String? = null) {
            val intent = Intent(context, VPNAIService::class.java).apply {
                action = ACTION_CONNECT
                serverLocation?.let { putExtra(EXTRA_SERVER_LOCATION, it) }
            }
            context.startForegroundService(intent)
        }

        fun stopVPN(context: Context) {
            val intent = Intent(context, VPNAIService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }

        fun autoConnect(context: Context) {
            val intent = Intent(context, VPNAIService::class.java).apply {
                action = ACTION_AUTO_CONNECT
            }
            context.startService(intent)
        }
    }
}

/**
 * Datos de servidor VPN
 */
data class VPNServer(
    val id: String,
    val location: String,
    val country: String,
    val city: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val latency: Long = 0,
    val load: Float = 0f,
    val isPremium: Boolean = false
)

