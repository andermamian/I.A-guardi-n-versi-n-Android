package com.guardianai.membership

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.guardianai.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestor de membresías para Guardian IA
 * Maneja suscripciones Premium y Enterprise con Google Play Billing
 */
@Singleton
class MembershipManager @Inject constructor(
    private val context: Context
) : PurchasesUpdatedListener, BillingClientStateListener {

    companion object {
        private const val TAG = "MembershipManager"
        
        // IDs de productos de suscripción
        const val PREMIUM_MONTHLY = "guardian_ia_premium_monthly"
        const val PREMIUM_YEARLY = "guardian_ia_premium_yearly"
        const val ENTERPRISE_MONTHLY = "guardian_ia_enterprise_monthly"
        const val ENTERPRISE_YEARLY = "guardian_ia_enterprise_yearly"
        
        // Características por nivel
        val BASIC_FEATURES = setOf(
            "basic_threat_detection",
            "basic_optimization",
            "basic_chatbot"
        )
        
        val PREMIUM_FEATURES = setOf(
            "advanced_threat_detection",
            "full_optimization",
            "advanced_chatbot",
            "basic_vpn",
            "real_time_monitoring"
        )
        
        val ENTERPRISE_FEATURES = setOf(
            "real_time_threat_detection",
            "predictive_optimization",
            "personalized_chatbot",
            "premium_vpn",
            "forensic_analysis",
            "priority_support",
            "advanced_analytics"
        )
    }

    private var billingClient: BillingClient? = null
    private val _membershipStatus = MutableStateFlow(MembershipStatus.BASIC)
    val membershipStatus: StateFlow<MembershipStatus> = _membershipStatus.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _availableProducts = MutableStateFlow<List<ProductDetails>>(emptyList())
    val availableProducts: StateFlow<List<ProductDetails>> = _availableProducts.asStateFlow()

    init {
        initializeBillingClient()
    }

    private fun initializeBillingClient() {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()
        
        billingClient?.startConnection(this)
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "Billing client conectado exitosamente")
            _isConnected.value = true
            queryAvailableProducts()
            queryActivePurchases()
        } else {
            Log.e(TAG, "Error al conectar billing client: ${billingResult.debugMessage}")
            _isConnected.value = false
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.w(TAG, "Billing service desconectado")
        _isConnected.value = false
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(TAG, "Usuario canceló la compra")
        } else {
            Log.e(TAG, "Error en la compra: ${billingResult.debugMessage}")
        }
    }

    private fun queryAvailableProducts() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PREMIUM_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PREMIUM_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(ENTERPRISE_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(ENTERPRISE_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient?.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                _availableProducts.value = productDetailsList
                Log.d(TAG, "Productos disponibles: ${productDetailsList.size}")
            } else {
                Log.e(TAG, "Error al consultar productos: ${billingResult.debugMessage}")
            }
        }
    }

    private fun queryActivePurchases() {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var highestMembership = MembershipStatus.BASIC
                
                for (purchase in purchases) {
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        val membership = getMembershipFromProductId(purchase.products.firstOrNull())
                        if (membership.level > highestMembership.level) {
                            highestMembership = membership
                        }
                    }
                }
                
                _membershipStatus.value = highestMembership
                Log.d(TAG, "Membresía activa: ${highestMembership.name}")
            } else {
                Log.e(TAG, "Error al consultar compras: ${billingResult.debugMessage}")
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            // Verificar la compra en el servidor si es necesario
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                
                billingClient?.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d(TAG, "Compra reconocida exitosamente")
                    }
                }
            }
            
            // Actualizar estado de membresía
            val membership = getMembershipFromProductId(purchase.products.firstOrNull())
            if (membership.level > _membershipStatus.value.level) {
                _membershipStatus.value = membership
            }
        }
    }

    private fun getMembershipFromProductId(productId: String?): MembershipStatus {
        return when (productId) {
            PREMIUM_MONTHLY, PREMIUM_YEARLY -> MembershipStatus.PREMIUM
            ENTERPRISE_MONTHLY, ENTERPRISE_YEARLY -> MembershipStatus.ENTERPRISE
            else -> MembershipStatus.BASIC
        }
    }

    /**
     * Inicia el flujo de compra para una suscripción
     */
    fun purchaseSubscription(activity: Activity, productId: String) {
        val productDetails = _availableProducts.value.find { it.productId == productId }
        
        if (productDetails != null) {
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            
            if (offerToken != null) {
                val productDetailsParamsList = listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build()
                )

                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)
                    .build()

                billingClient?.launchBillingFlow(activity, billingFlowParams)
            } else {
                Log.e(TAG, "No se encontró offer token para el producto: $productId")
            }
        } else {
            Log.e(TAG, "No se encontraron detalles para el producto: $productId")
        }
    }

    /**
     * Verifica si el usuario tiene acceso a una característica específica
     */
    fun hasFeature(feature: String): Boolean {
        return when (_membershipStatus.value) {
            MembershipStatus.BASIC -> BASIC_FEATURES.contains(feature)
            MembershipStatus.PREMIUM -> BASIC_FEATURES.contains(feature) || PREMIUM_FEATURES.contains(feature)
            MembershipStatus.ENTERPRISE -> BASIC_FEATURES.contains(feature) || 
                                         PREMIUM_FEATURES.contains(feature) || 
                                         ENTERPRISE_FEATURES.contains(feature)
        }
    }

    /**
     * Obtiene el precio formateado de un producto
     */
    fun getProductPrice(productId: String): String? {
        return _availableProducts.value.find { it.productId == productId }
            ?.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.firstOrNull()
            ?.formattedPrice
    }

    /**
     * Verifica si una funcionalidad requiere upgrade
     */
    fun requiresUpgrade(feature: String): Boolean {
        return !hasFeature(feature)
    }

    /**
     * Obtiene la membresía recomendada para una característica
     */
    fun getRequiredMembershipForFeature(feature: String): MembershipStatus {
        return when {
            ENTERPRISE_FEATURES.contains(feature) -> MembershipStatus.ENTERPRISE
            PREMIUM_FEATURES.contains(feature) -> MembershipStatus.PREMIUM
            else -> MembershipStatus.BASIC
        }
    }

    /**
     * Libera recursos del billing client
     */
    fun destroy() {
        billingClient?.endConnection()
        billingClient = null
    }
}

/**
 * Estados de membresía disponibles
 */
enum class MembershipStatus(val level: Int, val displayName: String) {
    BASIC(0, "Básico"),
    PREMIUM(1, "Premium"),
    ENTERPRISE(2, "Enterprise");
    
    fun getFeatures(): Set<String> {
        return when (this) {
            BASIC -> MembershipManager.BASIC_FEATURES
            PREMIUM -> MembershipManager.BASIC_FEATURES + MembershipManager.PREMIUM_FEATURES
            ENTERPRISE -> MembershipManager.BASIC_FEATURES + MembershipManager.PREMIUM_FEATURES + MembershipManager.ENTERPRISE_FEATURES
        }
    }
    
    fun getDescription(): String {
        return when (this) {
            BASIC -> "Funcionalidades básicas de Guardian IA"
            PREMIUM -> "Funcionalidades avanzadas + VPN básica"
            ENTERPRISE -> "Todas las funcionalidades + VPN premium + Soporte prioritario"
        }
    }
    
    fun getPrice(): String {
        return when (this) {
            BASIC -> "Gratis"
            PREMIUM -> "$9.99/mes"
            ENTERPRISE -> "$19.99/mes"
        }
    }
}

