// ThreatAnalysisActivity.java
package com.guardianai.activities;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.ViewGroup;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class ThreatAnalysisActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF1A1A1A);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        // Title
        TextView title = new TextView(this);
        title.setText("📊 Análisis de Amenazas");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 32);
        layout.addView(title);

        // Current time
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        TextView timeView = new TextView(this);
        timeView.setText("🕒 Último análisis: " + sdf.format(new Date()));
        timeView.setTextColor(0xFF4CAF50);
        timeView.setTextSize(16);
        timeView.setPadding(0, 0, 0, 24);
        layout.addView(timeView);

        // Analysis results
        Random random = new Random();
        addAnalysisSection(layout, "🔍 Escaneo de Red",
                "• " + (50 + random.nextInt(100)) + " dispositivos escaneados\n" +
                        "• " + random.nextInt(5) + " vulnerabilidades encontradas\n" +
                        "• " + (95 + random.nextInt(5)) + "% de cobertura de red");

        addAnalysisSection(layout, "🚨 Detección de Amenazas",
                "• " + random.nextInt(10) + " amenazas activas\n" +
                        "• " + (100 + random.nextInt(200)) + " eventos sospechosos\n" +
                        "• " + random.nextInt(50) + " intentos de intrusión bloqueados");

        addAnalysisSection(layout, "📈 Tendencias de Seguridad",
                "• Incremento del " + random.nextInt(20) + "% en actividad maliciosa\n" +
                        "• " + random.nextInt(10) + " nuevos IOCs identificados\n" +
                        "• Efectividad de bloqueo: " + (95 + random.nextInt(5)) + "%");

        addAnalysisSection(layout, "🎯 Recomendaciones",
                "• Actualizar reglas de firewall\n" +
                        "• Incrementar monitoreo en zona crítica\n" +
                        "• Revisar logs de autenticación");

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

    private void addAnalysisSection(LinearLayout parent, String title, String content) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(0xFF2C2C2C);
        section.setPadding(24, 24, 24, 24);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 16);
        section.setLayoutParams(params);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(18);
        titleView.setPadding(0, 0, 0, 12);

        TextView contentView = new TextView(this);
        contentView.setText(content);
        contentView.setTextColor(0xFFE0E0E0);
        contentView.setTextSize(14);
        contentView.setLineSpacing(6, 1);

        section.addView(titleView);
        section.addView(contentView);
        parent.addView(section);
    }
}
