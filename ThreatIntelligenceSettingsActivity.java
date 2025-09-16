// ThreatIntelligenceSettingsActivity.java
package com.guardianai.activities;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import android.widget.Toast;

public class ThreatIntelligenceSettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Create simple layout programmatically
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        layout.setBackgroundColor(0xFF1A1A1A);

        // Title
        TextView title = new TextView(this);
        title.setText("Configuración de Threat Intelligence");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 32);
        layout.addView(title);

        // Settings options
        addSettingOption(layout, "🔄 Frecuencia de actualización", "30 segundos");
        addSettingOption(layout, "📡 Fuentes de datos", "5 feeds activos");
        addSettingOption(layout, "🚨 Nivel de alertas", "Alto");
        addSettingOption(layout, "🗺️ Configuración de mapa", "Tiempo real");
        addSettingOption(layout, "🔍 Filtros de escaneo", "Automático");
        addSettingOption(layout, "📊 Retención de datos", "30 días");

        // Back button
        TextView backButton = new TextView(this);
        backButton.setText("← Volver");
        backButton.setTextColor(0xFF4CAF50);
        backButton.setTextSize(18);
        backButton.setPadding(0, 32, 0, 0);
        backButton.setOnClickListener(v -> finish());
        layout.addView(backButton);

        setContentView(layout);
    }

    private void addSettingOption(LinearLayout parent, String title, String value) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setPadding(0, 16, 0, 16);
        item.setBackgroundColor(0xFF2C2C2C);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 8, 0, 8);
        item.setLayoutParams(params);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(16);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(0xFFB0B0B0);
        valueView.setTextSize(14);

        item.addView(titleView);
        item.addView(valueView);
        item.setOnClickListener(v ->
                Toast.makeText(this, "Configurando: " + title, Toast.LENGTH_SHORT).show());

        parent.addView(item);
    }
}
