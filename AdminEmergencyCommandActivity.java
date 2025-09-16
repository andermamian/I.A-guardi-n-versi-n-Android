package com.guardianai.activities;

import static com.guardianai.R.string.notifications_sent;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.guardianai.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Actividad del Centro de Comando de Emergencias
 * NOTA: Esta es una implementación educativa/demo para mostrar conceptos de UI.
 * No debe usarse para sistemas de emergencia reales sin validación profesional.
 */
public class AdminEmergencyCommandActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
    };

    // UI Components - Botones principales
    private CardView btnMassAlert;
    private CardView btnEvacuation;
    private CardView btnEmergencyComm;

    // UI Components - Comunicación con IA
    private CardView btnVoiceAssistant;
    private CardView btnChatAssistant;

    // UI Components - Textos de métricas
    private TextView tvResponseTime;
    private TextView tvActiveUnits;
    private TextView tvMonitoredZones;
    private TextView tvGpsCoverage;
    private TextView tvEmergencyCount;
    private TextView tvSystemTime;
    private TextView tvAssistantStatus;
    private TextView tvAssistantName;

    // Data Management
    private Handler uiUpdateHandler;
    private Runnable updateMetricsRunnable;
    private List<EmergencyIncident> emergencyQueue;
    private boolean isEmergencyMode = false;

    // Metrics variables
    private int activeUnits = 12;
    private int monitoredZones = 47;
    private int responseTime = 23;
    private int activeEmergencies = 3;
    private double gpsCoverage = 99.8;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_emergency_command);

        initializeComponents();
        setupClickListeners();
        checkPermissions();
        initializeRealTimeUpdates();
        loadEmergencyQueue();
        updateMetricsUI();
    }

    private void initializeComponents() {
        // Emergency Control Buttons
        btnMassAlert = findViewById(R.id.btn_mass_alert);
        btnEvacuation = findViewById(R.id.btn_evacuation);
        btnEmergencyComm = findViewById(R.id.btn_emergency_comm);

        // AI Assistant Communication Buttons
        btnVoiceAssistant = findViewById(R.id.btn_voice_assistant);
        btnChatAssistant = findViewById(R.id.btn_chat_assistant);

        // Metric TextViews - Buscar en el header
        View headerView = findViewById(R.id.header_emergency_center);
        if (headerView != null) {
            tvResponseTime = headerView.findViewById(R.id.tv_response_time);
            tvActiveUnits = headerView.findViewById(R.id.tv_active_units);
            tvMonitoredZones = headerView.findViewById(R.id.tv_monitored_zones);
            tvGpsCoverage = headerView.findViewById(R.id.tv_gps_coverage);
            tvEmergencyCount = headerView.findViewById(R.id.tv_emergency_count);
            tvSystemTime = headerView.findViewById(R.id.tv_system_time);
        }

        // Assistant status views
        View assistantStatusView = findViewById(R.id.assistant_status_section);
        if (assistantStatusView != null) {
            tvAssistantStatus = assistantStatusView.findViewById(R.id.tv_assistant_status);
            tvAssistantName = assistantStatusView.findViewById(R.id.tv_assistant_name);
        }

        // Initialize emergency queue
        emergencyQueue = new ArrayList<>();

        // Setup UI update handler
        uiUpdateHandler = new Handler(Looper.getMainLooper());
    }

    private void setupClickListeners() {
        // Critical Emergency Controls
        if (btnMassAlert != null) {
            btnMassAlert.setOnClickListener(v -> handleMassAlert());
        }

        if (btnEvacuation != null) {
            btnEvacuation.setOnClickListener(v -> handleImmediateEvacuation());
        }

        if (btnEmergencyComm != null) {
            btnEmergencyComm.setOnClickListener(v -> handleEmergencyCall());
        }

        // AI Assistant Communication Controls
        if (btnVoiceAssistant != null) {
            btnVoiceAssistant.setOnClickListener(v -> handleVoiceAssistant());
        }

        if (btnChatAssistant != null) {
            btnChatAssistant.setOnClickListener(v -> handleChatAssistant());
        }

        // Add visual feedback for buttons
        setupButtonFeedback();
    }

    private void setupButtonFeedback() {
        View.OnClickListener feedbackListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(100)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                v.animate()
                                        .scaleX(1.0f)
                                        .scaleY(1.0f)
                                        .setDuration(100)
                                        .start();
                            }
                        })
                        .start();
            }
        };

        if (btnMassAlert != null) {
            addClickFeedback(btnMassAlert);
        }
        if (btnEvacuation != null) {
            addClickFeedback(btnEvacuation);
        }
        if (btnEmergencyComm != null) {
            addClickFeedback(btnEmergencyComm);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addClickFeedback(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
                    break;
            }
            return false;
        });
    }

    private void handleMassAlert() {
        showConfirmationDialog(
                getString(R.string.mass_alert_title),
                getString(R.string.mass_alert_confirmation),
                new Runnable() {
                    @Override
                    public void run() {
                        activateMassAlert();
                        logSecurityEvent("MASS_ALERT_ACTIVATED", "Admin activated mass alert system");
                    }
                }
        );
    }

    private void handleImmediateEvacuation() {
        showConfirmationDialog(
                getString(R.string.evacuation_title),
                getString(R.string.evacuation_confirmation),
                new Runnable() {
                    @Override
                    public void run() {
                        activateEvacuation();
                        logSecurityEvent("EVACUATION_ACTIVATED", "Admin activated immediate evacuation protocol");
                    }
                }
        );
    }

    private void handleEmergencyCall() {
        showConfirmationDialog(
                getString(R.string.emergency_call_title),
                getString(R.string.emergency_call_confirmation),
                new Runnable() {
                    @Override
                    public void run() {
                        initiateEmergencyCall();
                        logSecurityEvent("EMERGENCY_CALL_INITIATED", "Admin initiated emergency call to 911");
                    }
                }
        );
    }

    private void handleVoiceAssistant() {
        Toast.makeText(this, getString(R.string.connecting_voice_assistant), Toast.LENGTH_SHORT).show();
        // Iniciar comunicación por voz con Guardian AI Assistant
        Intent intent = new Intent(this, AICommunicationActivity.class);
        intent.putExtra("mode", "voice_emergency");
        startActivity(intent);
    }

    private void handleChatAssistant() {
        Toast.makeText(this, getString(R.string.opening_chat_assistant), Toast.LENGTH_SHORT).show();
        // Abrir chat con Guardian AI Assistant
        Intent intent = new Intent(this, AICommunicationActivity.class);
        intent.putExtra("mode", "chat_emergency");
        startActivity(intent);
    }

    private void showConfirmationDialog(String title, String message, Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.btn_confirm), (dialog, which) -> {
                    onConfirm.run();
                    dialog.dismiss();
                })
                .setNegativeButton(getString(R.string.btn_cancel), (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }

    private void activateMassAlert() {
        setEmergencyMode(true);
        Toast.makeText(this, getString(R.string.mass_alert_activated), Toast.LENGTH_LONG).show();
        sendEmergencyNotifications();
        updateEmergencyStatus("MASS_ALERT_ACTIVE");
        updateMetricsUI();
    }

    private void activateEvacuation() {
        setEmergencyMode(true);
        Toast.makeText(this, getString(R.string.evacuation_activated), Toast.LENGTH_LONG).show();
        initiateEvacuationProtocols();
        updateEmergencyStatus("EVACUATION_ACTIVE");
        updateMetricsUI();
    }

    private void initiateEmergencyCall() {
        Toast.makeText(this, getString(R.string.calling_911), Toast.LENGTH_LONG).show();
        // Aquí iría la lógica real para llamar al 911
        updateEmergencyStatus("EMERGENCY_CALL_ACTIVE");
    }

    private void sendEmergencyNotifications() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2000);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(AdminEmergencyCommandActivity.this,
                                    getString(07766, 1900227),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    private void initiateEvacuationProtocols() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1500);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(AdminEmergencyCommandActivity.this,
                                    getString(R.string.evacuation_protocols_initiated),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    private void initializeRealTimeUpdates() {
        updateMetricsRunnable = new Runnable() {
            @Override
            public void run() {
                updateMetrics();
                updateTimestamp();
                simulateEmergencyChanges();
                updateMetricsUI();
                uiUpdateHandler.postDelayed(this, 5000);
            }
        };
        uiUpdateHandler.post(updateMetricsRunnable);
    }

    private void updateMetrics() {
        Random random = new Random();
        if (isEmergencyMode()) {
            setResponseTime(Math.max(15, getResponseTime() + random.nextInt(10) - 3));
            setActiveUnits(Math.min(25, getActiveUnits() + random.nextInt(3)));
        } else {
            setResponseTime(Math.max(20, getResponseTime() + random.nextInt(6) - 3));
        }

        // Actualizar cobertura GPS con pequeñas variaciones
        setGpsCoverage(Math.min(100.0, Math.max(95.0, getGpsCoverage() + (random.nextDouble() - 0.5))));
    }

    private void updateTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String currentTime = sdf.format(new Date());
        if (tvSystemTime != null) {
            tvSystemTime.setText(currentTime);
        }
    }

    private void updateMetricsUI() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (tvResponseTime != null) {
                    tvResponseTime.setText(String.format(Locale.getDefault(), "%ds", responseTime));
                }
                if (tvActiveUnits != null) {
                    tvActiveUnits.setText(String.valueOf(activeUnits));
                }
                if (tvMonitoredZones != null) {
                    tvMonitoredZones.setText(String.valueOf(monitoredZones));
                }
                if (tvGpsCoverage != null) {
                    tvGpsCoverage.setText(String.format(Locale.getDefault(), "%.1f%%", gpsCoverage));
                }
                if (tvEmergencyCount != null) {
                    tvEmergencyCount.setText(String.format(Locale.getDefault(),
                            "%d %s", activeEmergencies, getString(R.string.emergencies)));
                }

                // Actualizar estado del asistente
                if (tvAssistantStatus != null) {
                    tvAssistantStatus.setText(getString(R.string.assistant_connected));
                }
                if (tvAssistantName != null) {
                    tvAssistantName.setText(getString(R.string.guardian_ai_assistant));
                }
            }
        });
    }

    @SuppressLint("StringFormatInvalid")
    private void simulateEmergencyChanges() {
        Random random = new Random();
        if (random.nextInt(10) == 0) {
            EmergencyIncident newIncident = generateRandomIncident();
            addEmergencyIncident(newIncident);
            Toast.makeText(this, getString(R.string.new_emergency_detected, newIncident.getTitle()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void loadEmergencyQueue() {
        emergencyQueue.add(new EmergencyIncident(
                1, getString(R.string.emergency_armed_assault), "Av. Principal 1247",
                "María González", getString(R.string.priority_critical), "23:45:12"
        ));

        emergencyQueue.add(new EmergencyIncident(
                2, getString(R.string.emergency_medical), "Centro Comercial Plaza",
                "Carlos Ruiz", getString(R.string.priority_high), "23:43:45"
        ));

        emergencyQueue.add(new EmergencyIncident(
                3, getString(R.string.emergency_traffic_accident), "Intersección 5ta y 12",
                "Ana López", getString(R.string.priority_medium), "23:41:23"
        ));
    }

    private EmergencyIncident generateRandomIncident() {
        String[] incidents = {
                getString(R.string.incident_robbery),
                getString(R.string.incident_vehicle_accident),
                getString(R.string.incident_fire),
                getString(R.string.incident_suspicious_person),
                getString(R.string.incident_medical_emergency),
                getString(R.string.incident_vandalism)
        };
        String[] locations = {
                "Av. Central 456", "Plaza Mayor", "Calle 8va", "Centro Comercial",
                "Parque Nacional", "Zona Industrial"
        };
        String[] priorities = {
                getString(R.string.priority_critical),
                getString(R.string.priority_high),
                getString(R.string.priority_medium),
                getString(R.string.priority_low)
        };

        Random random = new Random();
        return new EmergencyIncident(
                emergencyQueue.size() + 1,
                incidents[random.nextInt(incidents.length)],
                locations[random.nextInt(locations.length)],
                "Usuario " + (random.nextInt(999) + 1),
                priorities[random.nextInt(priorities.length)],
                new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())
        );
    }

    private void checkPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void updateEmergencyStatus(String status) {
        logSecurityEvent("EMERGENCY_STATUS_CHANGE", "Status changed to: " + status);
    }

    private void logSecurityEvent(String eventType, String description) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        System.out.println("[SECURITY_LOG] " + timestamp + " - " + eventType + ": " + description);
    }

    // Getter and Setter methods for metrics management
    public int getActiveUnits() {
        return activeUnits;
    }

    public void setActiveUnits(int activeUnits) {
        this.activeUnits = Math.max(0, activeUnits);
    }

    public int getMonitoredZones() {
        return monitoredZones;
    }

    public void setMonitoredZones(int monitoredZones) {
        this.monitoredZones = Math.max(0, monitoredZones);
    }

    public double getGpsCoverage() {
        return gpsCoverage;
    }

    public void setGpsCoverage(double gpsCoverage) {
        this.gpsCoverage = Math.max(0.0, Math.min(100.0, gpsCoverage));
    }

    public int getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(int responseTime) {
        this.responseTime = Math.max(1, responseTime);
    }

    public int getActiveEmergencies() {
        return activeEmergencies;
    }

    public void setActiveEmergencies(int activeEmergencies) {
        this.activeEmergencies = Math.max(0, activeEmergencies);
    }

    public boolean isEmergencyMode() {
        return isEmergencyMode;
    }

    public void setEmergencyMode(boolean emergencyMode) {
        this.isEmergencyMode = emergencyMode;
        if (emergencyMode) {
            logSecurityEvent("EMERGENCY_MODE_ENABLED", "Emergency mode activated");
        } else {
            logSecurityEvent("EMERGENCY_MODE_DISABLED", "Emergency mode deactivated");
        }
    }

    public List<EmergencyIncident> getEmergencyQueue() {
        return new ArrayList<>(emergencyQueue);
    }

    public void addEmergencyIncident(EmergencyIncident incident) {
        if (incident != null) {
            emergencyQueue.add(0, incident);
            setActiveEmergencies(getActiveEmergencies() + 1);
            logSecurityEvent("EMERGENCY_ADDED", "New emergency incident: " + incident.getTitle());
        }
    }

    public void removeEmergencyIncident(int incidentId) {
        boolean removed = emergencyQueue.removeIf(incident -> incident.getId() == incidentId);
        if (removed) {
            setActiveEmergencies(Math.max(0, getActiveEmergencies() - 1));
            logSecurityEvent("EMERGENCY_RESOLVED", "Emergency incident resolved: ID " + incidentId);
        }
    }

    public void clearEmergencyQueue() {
        emergencyQueue.clear();
        setActiveEmergencies(0);
        logSecurityEvent("EMERGENCY_QUEUE_CLEARED", "All emergency incidents cleared");
    }

    public EmergencyIncident getEmergencyIncidentById(int incidentId) {
        for (EmergencyIncident incident : emergencyQueue) {
            if (incident.getId() == incidentId) {
                return incident;
            }
        }
        return null;
    }

    public int getHighPriorityEmergencies() {
        int count = 0;
        for (EmergencyIncident incident : emergencyQueue) {
            String priority = incident.getPriority();
            if (getString(R.string.priority_critical).equals(priority) ||
                    getString(R.string.priority_high).equals(priority)) {
                count++;
            }
        }
        return count;
    }

    public void updateEmergencyPriority(int incidentId, String newPriority) {
        EmergencyIncident incident = getEmergencyIncidentById(incidentId);
        if (incident != null) {
            String oldPriority = incident.getPriority();
            incident.setPriority(newPriority);
            logSecurityEvent("PRIORITY_CHANGED",
                    "Emergency " + incidentId + " priority changed from " + oldPriority + " to " + newPriority);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (uiUpdateHandler != null && updateMetricsRunnable != null) {
            uiUpdateHandler.removeCallbacks(updateMetricsRunnable);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allPermissionsGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (!allPermissionsGranted) {
                Toast.makeText(this, getString(R.string.permissions_needed),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // Inner class for Emergency Incident data structure
    public static class EmergencyIncident {
        private int id;
        private String title;
        private String location;
        private String reporter;
        private String priority;
        private String timestamp;

        public EmergencyIncident(int id, String title, String location, String reporter, String priority, String timestamp) {
            this.id = id;
            this.title = title;
            this.location = location;
            this.reporter = reporter;
            this.priority = priority;
            this.timestamp = timestamp;
        }

        public int getId() { return id; }
        public String getTitle() { return title; }
        public String getLocation() { return location; }
        public String getReporter() { return reporter; }
        public String getPriority() { return priority; }
        public String getTimestamp() { return timestamp; }

        public void setPriority(String priority) { this.priority = priority; }
    }
}