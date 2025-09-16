package com.guardianai.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guardianai.membership.MembershipManager
import com.guardianai.membership.MembershipStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel para la gestión de membresías
 * Maneja el estado de la UI y las operaciones de membresía
 */
@HiltViewModel
class MembershipViewModel @Inject constructor(
    private val membershipManager: MembershipManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MembershipUiState())
    val uiState: StateFlow<MembershipUiState> = _uiState.asStateFlow()

    // Exponer flows del MembershipManager
    val membershipStatus = membershipManager.membershipStatus
    val isConnected = membershipManager.isConnected
    val availableProducts = membershipManager.availableProducts

    init {
        observeMembershipChanges()
    }

    private fun observeMembershipChanges() {
        viewModelScope.launch {
            membershipManager.membershipStatus.collect { status ->
                _uiState.value = _uiState.value.copy(
                    currentMembership = status,
                    canUpgradeToPremium = status.level < MembershipStatus.PREMIUM.level,
                    canUpgradeToEnterprise = status.level < MembershipStatus.ENTERPRISE.level,
                    hasActiveSubscription = status != MembershipStatus.BASIC
                )
            }
        }
    }

    /**
     * Verifica si el usuario tiene acceso a una característica
     */
    fun hasFeature(feature: String): Boolean {
        return membershipManager.hasFeature(feature)
    }

    /**
     * Verifica si una característica requiere upgrade
     */
    fun requiresUpgrade(feature: String): Boolean {
        return membershipManager.requiresUpgrade(feature)
    }

    /**
     * Obtiene la membresía requerida para una característica
     */
    fun getRequiredMembershipForFeature(feature: String): MembershipStatus {
        return membershipManager.getRequiredMembershipForFeature(feature)
    }

    /**
     * Obtiene el precio de un producto
     */
    fun getProductPrice(productId: String): String? {
        return membershipManager.getProductPrice(productId)
    }

    /**
     * Obtiene información detallada de una membresía
     */
    fun getMembershipInfo(status: MembershipStatus): MembershipInfo {
        return when (status) {
            MembershipStatus.BASIC -> MembershipInfo(
                name = "Básico",
                price = "Gratis",
                description = "Funcionalidades básicas de Guardian IA",
                features = listOf(
                    "Detección básica de amenazas",
                    "Optimización básica del sistema",
                    "Asistente IA básico",
                    "Monitoreo básico"
                ),
                color = "#6B7280",
                isRecommended = false
            )
            MembershipStatus.PREMIUM -> MembershipInfo(
                name = "Premium",
                price = getProductPrice(MembershipManager.PREMIUM_MONTHLY) ?: "$9.99/mes",
                description = "Funcionalidades avanzadas + VPN básica",
                features = listOf(
                    "Detección avanzada de amenazas",
                    "Optimización completa del sistema",
                    "Asistente IA avanzado",
                    "VPN básica (5 ubicaciones)",
                    "Monitoreo en tiempo real",
                    "Análisis de rendimiento"
                ),
                color = "#3B82F6",
                isRecommended = true
            )
            MembershipStatus.ENTERPRISE -> MembershipInfo(
                name = "Enterprise",
                price = getProductPrice(MembershipManager.ENTERPRISE_MONTHLY) ?: "$19.99/mes",
                description = "Todas las funcionalidades + VPN premium + Soporte prioritario",
                features = listOf(
                    "Detección de amenazas en tiempo real",
                    "Optimización predictiva",
                    "Asistente IA personalizado",
                    "VPN premium (50+ ubicaciones)",
                    "Análisis forense completo",
                    "Soporte prioritario 24/7",
                    "Análisis avanzado de seguridad",
                    "Reportes personalizados"
                ),
                color = "#7C3AED",
                isRecommended = false
            )
        }
    }

    /**
     * Obtiene todas las membresías disponibles
     */
    fun getAllMemberships(): List<MembershipInfo> {
        return listOf(
            getMembershipInfo(MembershipStatus.BASIC),
            getMembershipInfo(MembershipStatus.PREMIUM),
            getMembershipInfo(MembershipStatus.ENTERPRISE)
        )
    }

    /**
     * Calcula el ahorro anual para un plan
     */
    fun getYearlyDiscount(monthlyProductId: String, yearlyProductId: String): String? {
        val monthlyPrice = getProductPrice(monthlyProductId)
        val yearlyPrice = getProductPrice(yearlyProductId)
        
        if (monthlyPrice != null && yearlyPrice != null) {
            // Extraer números de los precios (simplificado)
            val monthlyValue = extractPriceValue(monthlyPrice)
            val yearlyValue = extractPriceValue(yearlyPrice)
            
            if (monthlyValue != null && yearlyValue != null) {
                val yearlyFromMonthly = monthlyValue * 12
                val savings = yearlyFromMonthly - yearlyValue
                val percentage = (savings / yearlyFromMonthly * 100).toInt()
                
                return "Ahorra $percentage% anualmente"
            }
        }
        
        return null
    }

    private fun extractPriceValue(price: String): Double? {
        return try {
            price.replace(Regex("[^\\d.]"), "").toDoubleOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Maneja errores de compra
     */
    fun handlePurchaseError(errorCode: Int, errorMessage: String) {
        _uiState.value = _uiState.value.copy(
            error = "Error en la compra: $errorMessage",
            isLoading = false
        )
    }

    /**
     * Limpia errores
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Establece estado de carga
     */
    fun setLoading(isLoading: Boolean) {
        _uiState.value = _uiState.value.copy(isLoading = isLoading)
    }
}

/**
 * Estado de la UI para membresías
 */
data class MembershipUiState(
    val currentMembership: MembershipStatus = MembershipStatus.BASIC,
    val canUpgradeToPremium: Boolean = true,
    val canUpgradeToEnterprise: Boolean = true,
    val hasActiveSubscription: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * Información detallada de una membresía
 */
data class MembershipInfo(
    val name: String,
    val price: String,
    val description: String,
    val features: List<String>,
    val color: String,
    val isRecommended: Boolean
)

