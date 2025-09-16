
// ThreatScanResultsActivity.java
package com.guardianai.activities;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.ViewGroup;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class ThreatScanResultsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF1A1A1A);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        // Title and timestamp
        TextView title = new TextView(this);
        title.setText("🔍 Resultados del Escaneo");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 16);
        layout.addView(title);

        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());
        TextView timestamp = new TextView(this);
        timestamp.setText("Completado: " + sdf.format(new Date()));
        timestamp.setTextColor(0xFF4CAF50);
        timestamp.setTextSize(16);
        timestamp.setPadding(0, 0, 0, 32);
        layout.addView(timestamp);

        // Scan summary
        Random random = new Random();
        int threatsFound = random.nextInt(8);
        int hostsScanned = 150 + random.nextInt(100);
        int portsScanned = 1000 + random.nextInt(500);

        addSummaryCard(layout, "📊 Resumen del Escaneo",
                "• Hosts escaneados: " + hostsScanned + "\n" +
                        "• Puertos analizados: " + portsScanned + "\n" +
                        "• Amenazas detectadas: " + threatsFound + "\n" +
                        "• Tiempo de escaneo: 3.2 segundos");

        // Threats found
        if (threatsFound > 0) {
            TextView threatsTitle = new TextView(this);
            threatsTitle.setText("🚨 Amenazas Detectadas");
            threatsTitle.setTextColor(0xFFFF4444);
            threatsTitle.setTextSize(20);
            threatsTitle.setPadding(0, 24, 0, 16);
            layout.addView(threatsTitle);

            String[] threatTypes = {"Malware", "Phishing", "Botnet", "Vulnerability", "Suspicious Traffic"};
            String[] severities = {"Alta", "Media", "Baja"};
            String[] colors = {"🔴", "🟡", "🟢"};

            for (int i = 0; i < threatsFound; i++) {
                int severityIndex = random.nextInt(3);
                String threatType = threatTypes[random.nextInt(threatTypes.length)];
                String ip = "192.168." + random.nextInt(255) + "." + (1 + random.nextInt(254));

                addThreatCard(layout,
                        colors[severityIndex] + " " + threatType,
                        "IP: " + ip + "\nSeveridad: " + severities[severityIndex],
                        severityIndex == 0 ? 0xFFFF4444 : (severityIndex == 1 ? 0xFFFFD700 : 0xFF4CAF50));
            }
        } else {
            TextView noThreats = new TextView(this);
            noThreats.setText("✅ No se detectaron amenazas");
            noThreats.setTextColor(0xFF4CAF50);
            noThreats.setTextSize(18);
            noThreats.setPadding(0, 24, 0, 0);
            layout.addView(noThreats);
        }

        // Recommendations
        addSummaryCard(layout, "💡 Recomendaciones",
                "• Mantener actualizados los sistemas\n" +
                        "• Revisar configuraciones de firewall\n" +
                        "• Programar escaneos regulares\n" +
                        "• Monitorear tráfico de red");

        // Actions
        TextView actionsTitle = new TextView(this);
        actionsTitle.setText("⚡ Acciones Disponibles");
        actionsTitle.setTextColor(0xFFFFFFFF);
        actionsTitle.setTextSize(20);
        actionsTitle.setPadding(0, 24, 0, 16);
        layout.addView(actionsTitle);

        addActionButton(layout, "🔄 Ejecutar Nuevo Escaneo");
        addActionButton(layout, "📋 Exportar Resultados");
        addActionButton(layout, "🚨 Crear Alerta");

        // Back button
        TextView backButton = new TextView(this);
        backButton.setText("← Volver al Centro de Inteligencia");
        backButton.setTextColor(0xFF4CAF50);
        backButton.setTextSize(18);
        backButton.setPadding(0, 32, 0, 0);
        backButton.setOnClickListener(v -> finish());
        layout.addView(backButton);

        scrollView.addView(layout);
        setContentView(scrollView);
    }

    private void addSummaryCard(LinearLayout parent, String title, String content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF2C2C2C);
        card.setPadding(24, 24, 24, 24);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 16);
        card.setLayoutParams(params);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(18);
        titleView.setTextSize(Typeface.BOLD);
        titleView.setPadding(0, 0, 0, 12);

        TextView contentView = new TextView(this);
        contentView.setText(content);
        contentView.setTextColor(0xFFE0E0E0);
        contentView.setTextSize(14);
        contentView.setLineSpacing(6, 1);

        card.addView(titleView);
        card.addView(contentView);
        parent.addView(card);
    }

    private void addThreatCard(LinearLayout parent, String title, String details, int borderColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF2C2C2C);
        card.setPadding(20, 20, 20, 20);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(8, 0, 8, 12);
        card.setLayoutParams(params);

        // Left border simulation
        TextView border = new TextView(this);
        border.setBackgroundColor(borderColor);
        border.setLayoutParams(new LinearLayout.LayoutParams(6, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16);
        titleView.setTextSize(Typeface.BOLD);
        titleView.setPadding(0, 0, 0, 8);

        TextView detailsView = new TextView(this);
        detailsView.setText(details);
        detailsView.setTextColor(0xFFB0B0B0);
        detailsView.setTextSize(14);

        card.addView(titleView);
        card.addView(detailsView);
        parent.addView(card);
    }

    private void addActionButton(LinearLayout parent, String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(16);
        button.setBackgroundColor(0xFF4CAF50);
        button.setPadding(32, 16, 32, 16);
        button.setGravity(android.view.Gravity.CENTER);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 12);
        button.setLayoutParams(params);

        button.setOnClickListener(v ->
                android.widget.Toast.makeText(this, "Ejecutando: " + text, android.widget.Toast.LENGTH_SHORT).show());

        parent.addView(button);
    }
}