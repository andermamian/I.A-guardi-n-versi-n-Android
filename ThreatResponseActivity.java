
// ThreatResponseActivity.java
package com.guardianai.activities;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.ViewGroup;
import android.widget.Toast;

public class ThreatResponseActivity extends Activity {

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
        title.setText("🛡️ Centro de Respuesta a Amenazas");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 32);
        layout.addView(title);

        // Emergency actions
        addActionButton(layout, "🚨 BLOQUEO DE EMERGENCIA", "Bloquear toda actividad sospechosa", 0xFFFF4444);
        addActionButton(layout, "🔒 AISLAMIENTO DE RED", "Aislar dispositivos comprometidos", 0xFFFF6B35);
        addActionButton(layout, "📞 CONTACTAR SIRT", "Notificar al equipo de respuesta", 0xFFFFD700);
        addActionButton(layout, "🔄 RESTAURAR DESDE BACKUP", "Restaurar sistemas críticos", 0xFF4CAF50);
        addActionButton(layout, "📋 GENERAR REPORTE", "Crear reporte de incidente", 0xFF2196F3);

        // Current incidents
        TextView incidentsTitle = new TextView(this);
        incidentsTitle.setText("📋 Incidentes Activos");
        incidentsTitle.setTextColor(0xFFFFFFFF);
        incidentsTitle.setTextSize(20);
        incidentsTitle.setPadding(0, 32, 0, 16);
        layout.addView(incidentsTitle);

        addIncident(layout, "🔴 CRÍTICO", "Malware APT detectado", "IP: 192.168.1.100", "Hace 5 min");
        addIncident(layout, "🟡 MEDIO", "Intento de phishing", "Domain: fake-site.com", "Hace 12 min");
        addIncident(layout, "🟢 RESUELTO", "Tráfico anómalo", "IP: 10.0.0.50", "Hace 1 hora");

        // Back button
        TextView backButton = new TextView(this);
        backButton.setText("← Volver");
        backButton.setTextColor(0xFF4CAF50);
        backButton.setTextSize(18);
        backButton.setPadding(0, 32, 0, 0);
        backButton.setOnClickListener(v -> finish());
        layout.addView(backButton);

        scrollView.addView(layout);
        setContentView(scrollView);
    }

    private void addActionButton(LinearLayout parent, String title, String description, int color) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setBackgroundColor(color);
        button.setPadding(24, 24, 24, 24);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 16);
        button.setLayoutParams(params);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(18);
        titleView.setTextSize(Typeface.BOLD);

        TextView descView = new TextView(this);
        descView.setText(description);
        descView.setTextColor(0xFFFFFFFF);
        descView.setTextSize(14);
        descView.setPadding(0, 8, 0, 0);

        button.addView(titleView);
        button.addView(descView);
        button.setOnClickListener(v ->
                Toast.makeText(this, "Ejecutando: " + title, Toast.LENGTH_SHORT).show());

        parent.addView(button);
    }

    private void addIncident(LinearLayout parent, String level, String type, String details, String time) {
        LinearLayout incident = new LinearLayout(this);
        incident.setOrientation(LinearLayout.VERTICAL);
        incident.setBackgroundColor(0xFF2C2C2C);
        incident.setPadding(20, 20, 20, 20);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 12);
        incident.setLayoutParams(params);

        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);

        TextView levelView = new TextView(this);
        levelView.setText(level);
        levelView.setTextSize(14);
        levelView.setTextSize(Typeface.BOLD);
        levelView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView timeView = new TextView(this);
        timeView.setText(time);
        timeView.setTextColor(0xFF888888);
        timeView.setTextSize(12);

        headerLayout.addView(levelView);
        headerLayout.addView(timeView);

        TextView typeView = new TextView(this);
        typeView.setText(type);
        typeView.setTextColor(0xFFFFFFFF);
        typeView.setTextSize(16);
        typeView.setPadding(0, 8, 0, 4);

        TextView detailsView = new TextView(this);
        detailsView.setText(details);
        detailsView.setTextColor(0xFFB0B0B0);
        detailsView.setTextSize(14);

        incident.addView(headerLayout);
        incident.addView(typeView);
        incident.addView(detailsView);

        parent.addView(incident);
    }
}