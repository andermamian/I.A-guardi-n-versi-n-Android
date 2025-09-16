package com.guardianai.adapters;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.guardianai.R;
import java.util.Random;

public class ThreatIntelligencePagerAdapter extends RecyclerView.Adapter<ThreatIntelligencePagerAdapter.PageViewHolder> {

    private Context context;
    private static final int TAB_COUNT = 5;
    private Random random;

    // Tab constants
    private static final int TAB_THREATS = 0;
    private static final int TAB_IOCS = 1;
    private static final int TAB_TTPS = 2;
    private static final int TAB_FEEDS = 3;
    private static final int TAB_ANALYSIS = 4;

    public ThreatIntelligencePagerAdapter(Context context) {
        this.context = context;
        this.random = new Random();
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Create layout programmatically
        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(0xFF1E1E1E);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        return new PageViewHolder(scrollView);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        switch (position) {
            case TAB_THREATS:
                setupThreatsTab(holder);
                break;
            case TAB_IOCS:
                setupIOCsTab(holder);
                break;
            case TAB_TTPS:
                setupTTPsTab(holder);
                break;
            case TAB_FEEDS:
                setupFeedsTab(holder);
                break;
            case TAB_ANALYSIS:
                setupAnalysisTab(holder);
                break;
        }
    }

    @Override
    public int getItemCount() {
        return TAB_COUNT;
    }

    private void setupThreatsTab(PageViewHolder holder) {
        LinearLayout content = createTabContent("🚨 Amenazas Detectadas");

        // Add threat items
        addThreatItem(content, "🔴 Malware APT-29", "IP: 192.168.1.100", "Crítico", 0xFFFF4444);
        addThreatItem(content, "🟡 Phishing Email", "Domain: fake-bank.com", "Medio", 0xFFFFD700);
        addThreatItem(content, "🟠 Botnet Activity", "IP: 10.0.0.50", "Alto", 0xFFFF6B35);
        addThreatItem(content, "🟢 False Positive", "IP: 172.16.0.25", "Bajo", 0xFF4CAF50);

        // Add summary
        addSummaryCard(content, "📊 Resumen de Amenazas",
                "• Total detectadas: " + (15 + random.nextInt(20)) + "\n" +
                        "• Críticas: " + random.nextInt(5) + "\n" +
                        "• En proceso: " + random.nextInt(10) + "\n" +
                        "• Resueltas hoy: " + (20 + random.nextInt(30)));

        ((ScrollView) holder.itemView).removeAllViews();
        ((ScrollView) holder.itemView).addView(content);
    }

    private void setupIOCsTab(PageViewHolder holder) {
        LinearLayout content = createTabContent("🔍 Indicadores de Compromiso");

        // Add IOC items
        addIOCItem(content, "IP Address", "185.220.101.42", "High Confidence", "Malware C2");
        addIOCItem(content, "Domain", "evil-domain.com", "Medium Confidence", "Phishing Site");
        addIOCItem(content, "File Hash", "d41d8cd98f00b204...", "High Confidence", "Trojan");
        addIOCItem(content, "URL", "http://suspicious-url.net", "Low Confidence", "Suspicious");

        // Add IOC statistics
        addSummaryCard(content, "📈 Estadísticas IOC",
                "• IPs maliciosas: " + (500 + random.nextInt(200)) + "\n" +
                        "• Dominios sospechosos: " + (150 + random.nextInt(50)) + "\n" +
                        "• Hashes conocidos: " + (1000 + random.nextInt(500)) + "\n" +
                        "• URLs bloqueadas: " + (300 + random.nextInt(100)));

        ((ScrollView) holder.itemView).removeAllViews();
        ((ScrollView) holder.itemView).addView(content);
    }

    private void setupTTPsTab(PageViewHolder holder) {
        LinearLayout content = createTabContent("⚔️ Tácticas, Técnicas y Procedimientos");

        // Add TTP items
        addTTPItem(content, "T1566.001", "Spearphishing Attachment", "Initial Access", "Alta");
        addTTPItem(content, "T1055", "Process Injection", "Defense Evasion", "Media");
        addTTPItem(content, "T1071.001", "Web Protocols", "Command and Control", "Alta");
        addTTPItem(content, "T1005", "Data from Local System", "Collection", "Baja");

        // Add MITRE ATT&CK info
        addSummaryCard(content, "🎯 Análisis MITRE ATT&CK",
                "• Técnicas detectadas: " + (25 + random.nextInt(15)) + "\n" +
                        "• Tácticas identificadas: " + (8 + random.nextInt(4)) + "\n" +
                        "• Grupos APT relacionados: " + (3 + random.nextInt(3)) + "\n" +
                        "• Última actualización: Hace 2 horas");

        ((ScrollView) holder.itemView).removeAllViews();
        ((ScrollView) holder.itemView).addView(content);
    }

    private void setupFeedsTab(PageViewHolder holder) {
        LinearLayout content = createTabContent("📡 Feeds de Inteligencia");

        // Add feed sources
        addFeedItem(content, "AlienVault OTX", "Conectado", "✅", "Última actualización: Hace 15 min");
        addFeedItem(content, "VirusTotal", "Conectado", "✅", "Última actualización: Hace 5 min");
        addFeedItem(content, "Abuse.ch", "Conectado", "✅", "Última actualización: Hace 30 min");
        addFeedItem(content, "Spamhaus", "Error", "❌", "Error de conexión - Reintentando");
        addFeedItem(content, "ThreatConnect", "Conectado", "✅", "Última actualización: Hace 1 hora");

        // Add feed statistics
        addSummaryCard(content, "📊 Estadísticas de Feeds",
                "• Feeds activos: 4/5\n" +
                        "• Datos recibidos hoy: " + (5000 + random.nextInt(2000)) + " entradas\n" +
                        "• Promedio de actualización: 15 minutos\n" +
                        "• Calidad de datos: 94.5%");

        ((ScrollView) holder.itemView).removeAllViews();
        ((ScrollView) holder.itemView).addView(content);
    }

    private void setupAnalysisTab(PageViewHolder holder) {
        LinearLayout content = createTabContent("📊 Análisis y Correlación");

        // Add analysis items
        addAnalysisItem(content, "Correlación de Eventos",
                "Se detectaron " + (25 + random.nextInt(20)) + " eventos correlacionados en las últimas 24 horas");
        addAnalysisItem(content, "Análisis de Patrones",
                "Identificados " + (3 + random.nextInt(5)) + " patrones de ataque emergentes");
        addAnalysisItem(content, "Geolocalización",
                "Mayor actividad maliciosa desde: Rusia (35%), China (28%), Irán (15%)");
        addAnalysisItem(content, "Tendencias Temporales",
                "Pico de actividad entre 02:00-04:00 UTC (+127% sobre promedio)");

        // Add predictive analysis
        addSummaryCard(content, "🔮 Análisis Predictivo",
                "• Probabilidad de ataque en 24h: " + (15 + random.nextInt(20)) + "%\n" +
                        "• Vectores más probables: Email (45%), Web (32%)\n" +
                        "• Sectores en riesgo: Financiero, Salud\n" +
                        "• Recomendación: Incrementar monitoreo");

        ((ScrollView) holder.itemView).removeAllViews();
        ((ScrollView) holder.itemView).addView(content);
    }

    private LinearLayout createTabContent(String title) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        layout.setBackgroundColor(0xFF1E1E1E);

        // Title
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(22);
        titleView.setTextSize(Typeface.BOLD);
        titleView.setPadding(0, 0, 0, 24);
        layout.addView(titleView);

        return layout;
    }

    private void addThreatItem(LinearLayout parent, String name, String details, String severity, int color) {
        LinearLayout item = createCard();

        // Threat name
        TextView nameView = new TextView(context);
        nameView.setText(name);
        nameView.setTextColor(0xFFFFFFFF);
        nameView.setTextSize(16);
        nameView.setTextSize(Typeface.BOLD);

        // Details
        TextView detailsView = new TextView(context);
        detailsView.setText(details + " • " + severity);
        detailsView.setTextColor(0xFFB0B0B0);
        detailsView.setTextSize(14);
        detailsView.setPadding(0, 8, 0, 0);

        // Action button
        TextView actionButton = new TextView(context);
        actionButton.setText("INVESTIGAR");
        actionButton.setTextColor(0xFFFFFFFF);
        actionButton.setTextSize(12);
        actionButton.setBackgroundColor(color);
        actionButton.setPadding(16, 8, 16, 8);
        actionButton.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = 12;
        actionButton.setLayoutParams(buttonParams);

        item.addView(nameView);
        item.addView(detailsView);
        item.addView(actionButton);
        parent.addView(item);
    }

    private void addIOCItem(LinearLayout parent, String type, String value, String confidence, String category) {
        LinearLayout item = createCard();

        TextView typeView = new TextView(context);
        typeView.setText(type + ": " + value);
        typeView.setTextColor(0xFFFFFFFF);
        typeView.setTextSize(16);
        typeView.setTextSize(Typeface.BOLD);

        TextView infoView = new TextView(context);
        infoView.setText(confidence + " • " + category);
        infoView.setTextColor(0xFFB0B0B0);
        infoView.setTextSize(14);
        infoView.setPadding(0, 8, 0, 0);

        item.addView(typeView);
        item.addView(infoView);
        parent.addView(item);
    }

    private void addTTPItem(LinearLayout parent, String id, String technique, String tactic, String frequency) {
        LinearLayout item = createCard();

        TextView idView = new TextView(context);
        idView.setText(id + " - " + technique);
        idView.setTextColor(0xFFFFFFFF);
        idView.setTextSize(16);
        idView.setTextSize(Typeface.BOLD);

        TextView tacticView = new TextView(context);
        tacticView.setText("Táctica: " + tactic + " • Frecuencia: " + frequency);
        tacticView.setTextColor(0xFFB0B0B0);
        tacticView.setTextSize(14);
        tacticView.setPadding(0, 8, 0, 0);

        item.addView(idView);
        item.addView(tacticView);
        parent.addView(item);
    }

    private void addFeedItem(LinearLayout parent, String name, String status, String icon, String lastUpdate) {
        LinearLayout item = createCard();

        LinearLayout headerLayout = new LinearLayout(context);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);

        TextView nameView = new TextView(context);
        nameView.setText(name);
        nameView.setTextColor(0xFFFFFFFF);
        nameView.setTextSize(16);
        nameView.setTextSize(Typeface.BOLD);
        nameView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView iconView = new TextView(context);
        iconView.setText(icon);
        iconView.setTextSize(18);

        headerLayout.addView(nameView);
        headerLayout.addView(iconView);

        TextView statusView = new TextView(context);
        statusView.setText(status + " • " + lastUpdate);
        statusView.setTextColor(0xFFB0B0B0);
        statusView.setTextSize(14);
        statusView.setPadding(0, 8, 0, 0);

        item.addView(headerLayout);
        item.addView(statusView);
        parent.addView(item);
    }

    private void addAnalysisItem(LinearLayout parent, String title, String description) {
        LinearLayout item = createCard();

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16);
        titleView.setTextSize(Typeface.BOLD);

        TextView descView = new TextView(context);
        descView.setText(description);
        descView.setTextColor(0xFFE0E0E0);
        descView.setTextSize(14);
        descView.setPadding(0, 8, 0, 0);
        descView.setLineSpacing(4, 1);

        item.addView(titleView);
        item.addView(descView);
        parent.addView(item);
    }

    private void addSummaryCard(LinearLayout parent, String title, String content) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF2C2C2C);
        card.setPadding(24, 24, 24, 24);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 16, 0, 16);
        card.setLayoutParams(params);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(18);
        titleView.setTextSize(Typeface.BOLD);
        titleView.setPadding(0, 0, 0, 12);

        TextView contentView = new TextView(context);
        contentView.setText(content);
        contentView.setTextColor(0xFFE0E0E0);
        contentView.setTextSize(14);
        contentView.setLineSpacing(6, 1);

        card.addView(titleView);
        card.addView(contentView);
        parent.addView(card);
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF3C3C3C);
        card.setPadding(20, 20, 20, 20);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 12);
        card.setLayoutParams(params);

        return card;
    }

    public static class PageViewHolder extends RecyclerView.ViewHolder {
        public PageViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}