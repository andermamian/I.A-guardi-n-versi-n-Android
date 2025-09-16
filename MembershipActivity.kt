package com.guardianai.activities

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.guardianai.R
import com.guardianai.databinding.ActivityMembershipBinding
import com.guardianai.membership.MembershipManager
import com.guardianai.membership.MembershipStatus
import com.guardianai.viewmodels.MembershipViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Actividad para gestión de membresías de Guardian IA
 * Permite ver el estado actual y actualizar suscripciones
 */
@AndroidEntryPoint
class MembershipActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMembershipBinding
    private val viewModel: MembershipViewModel by viewModels()
    
    @Inject
    lateinit var membershipManager: MembershipManager
    
    private lateinit var membershipAdapter: MembershipAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMembershipBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        setupObservers()
        setupRecyclerView()
    }

    private fun setupUI() {
        // Configurar toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "Membresías Guardian IA"
        }
        
        // Configurar botones
        binding.btnUpgradePremium.setOnClickListener {
            upgradeToPremium()
        }
        
        binding.btnUpgradeEnterprise.setOnClickListener {
            upgradeToEnterprise()
        }
        
        binding.btnManageSubscription.setOnClickListener {
            openSubscriptionManagement()
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            membershipManager.membershipStatus.collect { status ->
                updateMembershipUI(status)
            }
        }
        
        lifecycleScope.launch {
            membershipManager.availableProducts.collect { products ->
                membershipAdapter.updateProducts(products)
                updatePricing()
            }
        }
        
        lifecycleScope.launch {
            membershipManager.isConnected.collect { connected ->
                binding.progressBar.visibility = if (connected) {
                    android.view.View.GONE
                } else {
                    android.view.View.VISIBLE
                }
            }
        }
    }

    private fun setupRecyclerView() {
        membershipAdapter = MembershipAdapter { productId ->
            membershipManager.purchaseSubscription(this, productId)
        }
        
        binding.recyclerViewPlans.apply {
            layoutManager = LinearLayoutManager(this@MembershipActivity)
            adapter = membershipAdapter
        }
    }

    private fun updateMembershipUI(status: MembershipStatus) {
        // Actualizar información del plan actual
        binding.tvCurrentPlan.text = status.displayName
        binding.tvPlanDescription.text = status.getDescription()
        binding.tvPlanPrice.text = status.getPrice()
        
        // Actualizar indicador visual
        val colorRes = when (status) {
            MembershipStatus.BASIC -> R.color.membership_basic
            MembershipStatus.PREMIUM -> R.color.membership_premium
            MembershipStatus.ENTERPRISE -> R.color.membership_enterprise
        }
        
        binding.cardCurrentPlan.setCardBackgroundColor(getColor(colorRes))
        
        // Actualizar botones de upgrade
        binding.btnUpgradePremium.visibility = if (status.level < MembershipStatus.PREMIUM.level) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        
        binding.btnUpgradeEnterprise.visibility = if (status.level < MembershipStatus.ENTERPRISE.level) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        
        // Mostrar botón de gestión si tiene suscripción activa
        binding.btnManageSubscription.visibility = if (status != MembershipStatus.BASIC) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        
        // Actualizar lista de características
        updateFeaturesList(status)
    }

    private fun updateFeaturesList(status: MembershipStatus) {
        val features = status.getFeatures()
        val featureTexts = mutableListOf<String>()
        
        features.forEach { feature ->
            val displayName = getFeatureDisplayName(feature)
            featureTexts.add("✅ $displayName")
        }
        
        binding.tvFeaturesList.text = featureTexts.joinToString("\n")
    }

    private fun getFeatureDisplayName(feature: String): String {
        return when (feature) {
            "basic_threat_detection" -> "Detección básica de amenazas"
            "advanced_threat_detection" -> "Detección avanzada de amenazas"
            "real_time_threat_detection" -> "Detección en tiempo real"
            "basic_optimization" -> "Optimización básica"
            "full_optimization" -> "Optimización completa"
            "predictive_optimization" -> "Optimización predictiva"
            "basic_chatbot" -> "Asistente IA básico"
            "advanced_chatbot" -> "Asistente IA avanzado"
            "personalized_chatbot" -> "Asistente IA personalizado"
            "basic_vpn" -> "VPN básica (5 ubicaciones)"
            "premium_vpn" -> "VPN premium (50+ ubicaciones)"
            "real_time_monitoring" -> "Monitoreo en tiempo real"
            "forensic_analysis" -> "Análisis forense"
            "priority_support" -> "Soporte prioritario"
            "advanced_analytics" -> "Análisis avanzado"
            else -> feature.replace("_", " ").capitalize()
        }
    }

    private fun updatePricing() {
        // Actualizar precios en los botones
        val premiumPrice = membershipManager.getProductPrice(MembershipManager.PREMIUM_MONTHLY)
        val enterprisePrice = membershipManager.getProductPrice(MembershipManager.ENTERPRISE_MONTHLY)
        
        if (premiumPrice != null) {
            binding.btnUpgradePremium.text = "Upgrade a Premium - $premiumPrice/mes"
        }
        
        if (enterprisePrice != null) {
            binding.btnUpgradeEnterprise.text = "Upgrade a Enterprise - $enterprisePrice/mes"
        }
    }

    private fun upgradeToPremium() {
        if (membershipManager.isConnected.value) {
            membershipManager.purchaseSubscription(this, MembershipManager.PREMIUM_MONTHLY)
        } else {
            Toast.makeText(this, "Conectando con la tienda...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun upgradeToEnterprise() {
        if (membershipManager.isConnected.value) {
            membershipManager.purchaseSubscription(this, MembershipManager.ENTERPRISE_MONTHLY)
        } else {
            Toast.makeText(this, "Conectando con la tienda...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSubscriptionManagement() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://play.google.com/store/account/subscriptions")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la gestión de suscripciones", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        // No destruir el membershipManager aquí ya que es singleton
    }
}

/**
 * Adapter para mostrar los planes de membresía disponibles
 */
class MembershipAdapter(
    private val onPurchaseClick: (String) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<MembershipAdapter.MembershipViewHolder>() {

    private var products = listOf<com.android.billingclient.api.ProductDetails>()

    fun updateProducts(newProducts: List<com.android.billingclient.api.ProductDetails>) {
        products = newProducts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): MembershipViewHolder {
        val binding = com.guardianai.databinding.ItemMembershipPlanBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return MembershipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MembershipViewHolder, position: Int) {
        holder.bind(products[position])
    }

    override fun getItemCount(): Int = products.size

    inner class MembershipViewHolder(
        private val binding: com.guardianai.databinding.ItemMembershipPlanBinding
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {

        fun bind(product: com.android.billingclient.api.ProductDetails) {
            val planName = when (product.productId) {
                MembershipManager.PREMIUM_MONTHLY -> "Premium Mensual"
                MembershipManager.PREMIUM_YEARLY -> "Premium Anual"
                MembershipManager.ENTERPRISE_MONTHLY -> "Enterprise Mensual"
                MembershipManager.ENTERPRISE_YEARLY -> "Enterprise Anual"
                else -> product.name
            }
            
            val price = product.subscriptionOfferDetails?.firstOrNull()
                ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                ?.formattedPrice ?: "N/A"
            
            binding.tvPlanName.text = planName
            binding.tvPlanPrice.text = price
            binding.tvPlanDescription.text = getDescriptionForProduct(product.productId)
            
            binding.btnSelectPlan.setOnClickListener {
                onPurchaseClick(product.productId)
            }
            
            // Configurar colores según el plan
            val colorRes = when {
                product.productId.contains("premium") -> R.color.membership_premium
                product.productId.contains("enterprise") -> R.color.membership_enterprise
                else -> R.color.membership_basic
            }
            
            binding.cardPlan.setCardBackgroundColor(
                androidx.core.content.ContextCompat.getColor(binding.root.context, colorRes)
            )
        }
        
        private fun getDescriptionForProduct(productId: String): String {
            return when {
                productId.contains("premium") -> "Funcionalidades avanzadas + VPN básica"
                productId.contains("enterprise") -> "Todas las funcionalidades + VPN premium + Soporte prioritario"
                else -> "Plan básico"
            }
        }
    }
}

