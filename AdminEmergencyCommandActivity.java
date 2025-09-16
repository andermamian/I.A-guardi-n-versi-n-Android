package com.guardianai.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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

    // UI Components
    private LinearLayout btnAlertaMasiva;
    private LinearLayout btnEvacuacionInmediata;
    private LinearLayout btnLockdownTotal;
    private LinearLayout btnAprobarSugerencia;
    private LinearLayout btnRechazarSugerencia;
    private LinearLayout btnConsultarMas;

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
    }

    @SuppressLint("WrongViewCast")
    private void initializeComponents() {
        // Emergency Control Buttons
        btnAlertaMasiva = findViewById(R.id.btn_alerta_masiva);
        btnEvacuacionInmediata = findViewById(R.id.btn_evacuacion_inmediata);
        btnLockdownTotal = findViewById(R.id.btn_lockdown_total);

        // AI Response Buttons
        btnAprobarSugerencia = findViewById(R.id.btn_aprobar_sugerencia);
        btnRechazarSugerencia = findViewById(R.id.btn_rechazar_sugerencia);
        btnConsultarMas = findViewById(R.id.btn_consultar_mas);

        // Initialize emergency queue
        emergencyQueue = new ArrayList<>();

        // Setup UI update handler
        uiUpdateHandler = new Handler(Looper.getMainLooper());
    }

    private void setupClickListeners() {
        // Critical Emergency Controls
        btnAlertaMasiva.setOnClickListener(v -> handleMassAlert());
        btnEvacuacionInmediata.setOnClickListener(v -> handleImmediateEvacuation());
        btnLockdownTotal.setOnClickListener(v -> handleTotalLockdown());

        // AI Communication Controls
        btnAprobarSugerencia.setOnClickListener(v -> handleApproveSuggestion());
        btnRechazarSugerencia.setOnClickListener(v -> handleRejectSuggestion());
        btnConsultarMas.setOnClickListener(v -> handleConsultMore());

        // Add visual feedback for buttons
        setupButtonFeedback();
    }

    private void setupButtonFeedback() {
        View.OnClickListener feedbackListener = v -> v.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction(() ->
                        v.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(100)
                                .start())
                .start();

        btnAlertaMasiva.setOnClickListener(feedbackListener);
        btnEvacuacionInmediata.setOnClickListener(feedbackListener);
        btnLockdownTotal.setOnClickListener(feedbackListener);
    }

    private void handleMassAlert() {
        showConfirmationDialog(
                "Alerta Masiva",
                "¿Confirma activar ALERTA MASIVA? Esta acción notificará a todos los usuarios registrados.",
                () -> {
                    activateMassAlert();
                    logSecurityEvent("MASS_ALERT_ACTIVATED", "Admin activated mass alert system");
                }
        );
    }

    private void handleImmediateEvacuation() {
        showConfirmationDialog(
                "Evacuación Inmediata",
                "¿Confirma activar EVACUACIÓN INMEDIATA? Esto iniciará protocolos de evacuación de emergencia.",
                () -> {
                    activateEvacuation();
                    logSecurityEvent("EVACUATION_ACTIVATED", "Admin activated immediate evacuation protocol");
                }
        );
    }

    private void handleTotalLockdown() {
        showConfirmationDialog(
                "Lockdown Total",
                "¿Confirma activar LOCKDOWN TOTAL? Esto bloqueará accesos y activará medidas de máxima seguridad.",
                () -> {
                    activateLockdown();
                    logSecurityEvent("LOCKDOWN_ACTIVATED", "Admin activated total lockdown protocol");
                }
        );
    }

    private void handleApproveSuggestion() {
        Toast.makeText(this, "Sugerencia de Guardian IA aprobada", Toast.LENGTH_SHORT).show();
        processAISuggestion(true);
    }

    private void handleRejectSuggestion() {
        Toast.makeText(this, "Sugerencia de Guardian IA rechazada", Toast.LENGTH_SHORT).show();
        processAISuggestion(false);
    }

    private void handleConsultMore() {
        Intent intent = new Intent(this, AICommunicationActivity.class);
        intent.putExtra("mode", "emergency_consultation");
        startActivity(intent);
    }

    private void showConfirmationDialog(String title, String message, Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("CONFIRMAR", (dialog, which) -> {
                    onConfirm.run();
                    dialog.dismiss();
                })
                .setNegativeButton("CANCELAR", (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }

    private void activateMassAlert() {
        setEmergencyMode(true);
        Toast.makeText(this, "🚨 ALERTA MASIVA ACTIVADA", Toast.LENGTH_LONG).show();
        sendEmergencyNotifications();
        updateEmergencyStatus("MASS_ALERT_ACTIVE");
    }

    private void activateEvacuation() {
        setEmergencyMode(true);
        Toast.makeText(this, "🏃 EVACUACIÓN INMEDIATA ACTIVADA", Toast.LENGTH_LONG).show();
        initiateEvacuationProtocols();
        updateEmergencyStatus("EVACUATION_ACTIVE");
    }

    private void activateLockdown() {
        setEmergencyMode(true);
        Toast.makeText(this, "🔒 LOCKDOWN TOTAL ACTIVADO", Toast.LENGTH_LONG).show();
        initiateLockdownProcedures();
        updateEmergencyStatus("LOCKDOWN_ACTIVE");
    }

    private void sendEmergencyNotifications() {
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                runOnUiThread(() -> Toast.makeText(this, "Notificaciones enviadas a 4,086 usuarios", Toast.LENGTH_SHORT).show());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void initiateEvacuationProtocols() {
        new Thread(() -> {
            try {
                Thread.sleep(1500);
                runOnUiThread(() -> Toast.makeText(this, "Protocolos de evacuación iniciados en 3 zonas", Toast.LENGTH_SHORT).show());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void initiateLockdownProcedures() {
        new Thread(() -> {
            try {
                Thread.sleep(2500);
                runOnUiThread(() -> Toast.makeText(this, "Lockdown activado - Accesos bloqueados", Toast.LENGTH_SHORT).show());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void processAISuggestion(boolean approved) {
        String action = approved ? "approved" : "rejected";
        logSecurityEvent("AI_SUGGESTION_" + action.toUpperCase(),
                "Admin " + action + " AI evacuation suggestion for Zone B");

        if (approved) {
            implementZoneBEvacuation();
        }
    }

    private void implementZoneBEvacuation() {
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Evacuación preventiva Zona B iniciada", Toast.LENGTH_LONG).show();
                    setActiveEmergencies(getActiveEmergencies() + 1);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
    }

    private void updateTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        sdf.format(new Date());
    }

    private void simulateEmergencyChanges() {
        Random random = new Random();
        if (random.nextInt(10) == 0) {
            EmergencyIncident newIncident = generateRandomIncident();
            addEmergencyIncident(newIncident);
            Toast.makeText(this, "Nueva emergencia detectada: " + newIncident.getTitle(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void loadEmergencyQueue() {
        emergencyQueue.add(new EmergencyIncident(
                1, "ASALTO ARMADO EN PROGRESO", "Av. Principal 1247",
                "María González", "CRÍTICO", "23:45:12"
        ));

        emergencyQueue.add(new EmergencyIncident(
                2, "EMERGENCIA MÉDICA - INFARTO", "Centro Comercial Plaza",
                "Carlos Ruiz", "ALTA", "23:43:45"
        ));

        emergencyQueue.add(new EmergencyIncident(
                3, "ACCIDENTE DE TRÁNSITO MENOR", "Intersección 5ta y 12",
                "Ana López", "MEDIA", "23:41:23"
        ));
    }

    private EmergencyIncident generateRandomIncident() {
        String[] incidents = {
                "Robo en progreso", "Accidente vehicular", "Incendio reportado",
                "Persona sospechosa", "Emergencia médica", "Vandalismo"
        };
        String[] locations = {
                "Av. Central 456", "Plaza Mayor", "Calle 8va", "Centro Comercial",
                "Parque Nacional", "Zona Industrial"
        };
        String[] priorities = {"CRÍTICO", "ALTA", "MEDIA", "BAJA"};

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
        updateMetrics();
    }

    public int getMonitoredZones() {
        return monitoredZones;
    }

    public void setMonitoredZones(int monitoredZones) {
        this.monitoredZones = Math.max(0, monitoredZones);
        updateMetrics();
    }

    public double getGpsCoverage() {
        return gpsCoverage;
    }

    public void setGpsCoverage(double gpsCoverage) {
        this.gpsCoverage = Math.max(0.0, Math.min(100.0, gpsCoverage));
        updateMetrics();
    }

    public int getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(int responseTime) {
        this.responseTime = Math.max(1, responseTime);
        updateMetrics();
    }

    public int getActiveEmergencies() {
        return activeEmergencies;
    }

    public void setActiveEmergencies(int activeEmergencies) {
        this.activeEmergencies = Math.max(0, activeEmergencies);
        updateMetrics();
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
        return emergencyQueue.stream()
                .filter(incident -> incident.getId() == incidentId)
                .findFirst()
                .orElse(null);
    }

    public int getHighPriorityEmergencies() {
        return (int) emergencyQueue.stream()
                .filter(incident -> "CRÍTICO".equals(incident.getPriority()) || "ALTA".equals(incident.getPriority()))
                .count();
    }

    public void updateEmergencyPriority(int incidentId, String newPriority) {
        EmergencyIncident incident = getEmergencyIncidentById(incidentId);
        if (incident != null) {
            String oldPriority = incident.getPriority();
            incident.setPriority(newPriority);
            logSecurityEvent("PRIORITY_CHANGED",
                    "Emergency " + incidentId + " priority changed from " + oldPriority + " to " + newPriority);
            updateMetrics();
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
                Toast.makeText(this, "Algunos permisos son necesarios para el funcionamiento completo",
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

    private class AICommunicationActivity {
    }
}