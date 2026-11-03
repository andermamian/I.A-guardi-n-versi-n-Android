package co.guardiania.app.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import co.guardiania.app.R;
import co.guardiania.app.data.Vinculo;
import co.guardiania.app.net.ApiClient;
import co.guardiania.app.ui.MainActivity;

/**
 * El componente que hace de GuardianIA una app antirrobo real y no una página web: un servicio en
 * primer plano que reporta la ubicación 24/7, aunque la app no esté en pantalla, la pantalla esté
 * apagada o el teléfono se haya reiniciado.
 *
 * <p>Por qué en primer plano: Android mata en segundos cualquier proceso que corra "por detrás" sin
 * una notificación visible. La notificación fija ("GuardianIA está protegiendo este teléfono") es
 * el precio que exige el sistema para dejar viva a la app. Es la misma técnica de Life360 o de
 * "Encontrar mi dispositivo".
 *
 * <p>Por qué la ubicación no la pide el ladrón: el permiso "Ubicación todo el tiempo" se concede una
 * sola vez, al instalar. Después el servicio la lee solo, sin que nadie toque nada. Cuando el dueño
 * marca el equipo como robado desde el PC, el servidor responde a cada latido con un intervalo más
 * corto y el servicio reporta más seguido.
 */
public class TrackingService extends Service {

    private static final String TAG = "GuardianIA/Track";
    private static final String CANAL = "guardiania_proteccion";
    private static final int NOTIF_ID = 1001;

    public static final String ACCION_INICIAR = "co.guardiania.app.INICIAR";
    public static final String ACCION_DETENER = "co.guardiania.app.DETENER";
    public static final String ACCION_RECONECTAR = "co.guardiania.app.RECONECTAR";

    /** Estado del canal en vivo, para mostrarlo en la app («En vivo con el PC»). */
    private static volatile boolean canalAbierto = false;
    public static boolean enVivoConPc() { return canalAbierto; }
    public static final String ACCION_MOTIVO = "co.guardiania.app.MOTIVO";  // "boot", "power", …

    // La pantalla del mapa se suscribe para pintar la posición en vivo sin volver a pedir el GPS.
    public interface Escucha { void onUbicacion(Location loc, int bateria, boolean seguimiento); }
    private static volatile Escucha escucha;
    private static Location ultimaGlobal;
    /** Hora del último reporte enviado: el temporizador reporta si pasó el intervalo (aunque no haya GPS). */
    private volatile long ultimoLatidoMs = 0;
    private java.util.concurrent.ScheduledExecutorService reloj;
    private android.net.ConnectivityManager.NetworkCallback redCallback;

    public static void observar(Escucha e) { escucha = e; }
    public static void dejarDeObservar(Escucha e) { if (escucha == e) escucha = null; }
    public static Location ultimaConocida() { return ultimaGlobal; }

    private Vinculo vinculo;
    private ApiClient api;
    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private ExecutorService red;                 // la red nunca va en el hilo principal
    private Location ultima;
    private volatile boolean enviando = false;
    private int intervaloActualS = 30;
    /** Canal en vivo: las órdenes del PC llegan en menos de un segundo (antes: hasta 30 s). */
    private co.guardiania.app.net.CanalEquipo canalVivo;
    /** Hilo propio para comandos: una alarma no espera a que termine un latido lento. */
    private ExecutorService comandos;
    /** Ids ya ejecutados: el aviso en vivo y el latido pueden traer el mismo comando a la vez. */
    private final java.util.Set<String> ejecutados = java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>() {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> e) { return size() > 100; }
            });

    @Override
    public void onCreate() {
        super.onCreate();
        vinculo = new Vinculo(this);
        red = Executors.newSingleThreadExecutor();
        comandos = Executors.newSingleThreadExecutor();
        fused = LocationServices.getFusedLocationProviderClient(this);
        crearCanal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent != null ? intent.getAction() : ACCION_INICIAR;
        if (ACCION_RECONECTAR.equals(accion) && canalVivo != null && api != null) {
            // Android exige startForeground en cada inicio con startForegroundService.
            startForeground(NOTIF_ID, construirNotificacion("Protección activa", "Vigilando la ubicación de este teléfono."));
            canalVivo.reconectarYa();                                  // botón «Conectar» de la app
            red.execute(() -> latido("reconectar", ultima));
            return START_STICKY;
        }
        if (ACCION_DETENER.equals(accion)) {
            detenerTodo();
            return START_NOT_STICKY;
        }

        // Debe llamarse en los primeros segundos o el sistema lanza una excepción y mata el servicio.
        startForeground(NOTIF_ID, construirNotificacion("Protección activa", "Vigilando la ubicación de este teléfono."));

        if (!vinculo.estaVinculado()) {
            actualizarNotificacion("Sin vincular", "Abre GuardianIA y vincula este teléfono con un código.");
            return START_STICKY;
        }
        api = new ApiClient(vinculo.servidor(), vinculo.ca());
        intervaloActualS = vinculo.intervaloS();
        iniciarCanalVivo();
        iniciarTemporizador();
        vigilarRed();

        // Motivo especial (encendido / cambio de energía): se manda un latido inmediato.
        String motivo = intent != null ? intent.getStringExtra(ACCION_MOTIVO) : null;
        if (motivo != null) {
            red.execute(() -> latido(motivo, null));
        }

        pedirUbicaciones();
        // START_STICKY: si el sistema mata el servicio por memoria, Android lo vuelve a crear.
        return START_STICKY;
    }

    private void pedirUbicaciones() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            actualizarNotificacion("Falta permiso de ubicación",
                    "Abre GuardianIA y concede 'Ubicación todo el tiempo'.");
            return;
        }
        if (callback != null) {
            fused.removeLocationUpdates(callback);
        }
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervaloActualS * 1000L)
                .setMinUpdateIntervalMillis(Math.max(10, intervaloActualS / 2) * 1000L)
                .setWaitForAccurateLocation(false)
                .build();
        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;
                ultima = loc;
                ultimaGlobal = loc;
                Escucha e = escucha;
                if (e != null) e.onUbicacion(loc, nivelBateria(), vinculo.seguimiento());
                red.execute(() -> latido(null, loc));
            }
        };
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
        Log.i(TAG, "Reportando cada " + intervaloActualS + " s");
    }

    /** Un latido al servidor: batería, red y (si hay) posición. Devuelve el ritmo que ordene el PC. */
    private void latido(String motivo, @Nullable Location loc) {
        // SOS pendiente: reintenta los SMS sin señal y el registro en la cuenta (cada ~30 s).
        try { co.guardiania.app.seguridad.SmsSos.reintentar(this); } catch (Exception ignored) {}
        if (enviando || api == null) return;
        enviando = true;
        ultimoLatidoMs = System.currentTimeMillis();
        try {
            JSONObject cuerpo = new JSONObject();
            cuerpo.put("device_token", vinculo.token());
            int bateria = nivelBateria();
            if (bateria >= 0) cuerpo.put("battery", bateria);
            cuerpo.put("charging", cargando());
            if (loc != null) {
                cuerpo.put("lat", loc.getLatitude());
                cuerpo.put("lon", loc.getLongitude());
                cuerpo.put("accuracy_m", loc.getAccuracy());
                if (loc.hasSpeed()) cuerpo.put("speed_ms", loc.getSpeed());
                cuerpo.put("gps", "ok");
            } else {
                cuerpo.put("gps", "waiting");
            }
            JSONObject info = new JSONObject();
            info.put("modelo", Build.MODEL);
            info.put("sistema", "Android");
            info.put("version_sistema", Build.VERSION.RELEASE);
            info.put("app", "nativa");
            if (motivo != null) info.put("evento", motivo);
            cuerpo.put("info", info);

            ApiClient.Resp r = api.post("/devices/telemetry", cuerpo);
            if (r.code == 401) {
                perderVinculo();
                return;
            }
            if (r.ok()) {
                JSONObject j = r.json();
                aplicarRespuesta(j);
                if (j.optInt("comandos_pendientes", 0) > 0) comandos.execute(this::procesarComandos);
            }
        } catch (Exception e) {
            Log.w(TAG, "Latido falló (se reintenta en el próximo): " + e.getMessage());
        } finally {
            enviando = false;
        }
    }

    /** El servidor decide el ritmo: al marcar el equipo como robado, el intervalo baja a 15 s. */
    private void aplicarRespuesta(JSONObject j) {
        // El servidor no ve nuestro canal en vivo (se cortó sin que lo notáramos): se reabre ya.
        if (j.has("canal_vivo") && !j.optBoolean("canal_vivo") && canalVivo != null) canalVivo.reconectarYa();
        boolean seguimiento = j.optBoolean("seguimiento", true);
        int nuevo = j.optInt("siguiente_reporte_s", intervaloActualS);
        String estado = j.optString("estado", "ok");
        vinculo.guardarRitmo(nuevo, seguimiento);

        boolean reportado = "lost".equals(estado) || "stolen".equals(estado);
        actualizarNotificacion(
                reportado ? "Equipo reportado" : "Protección activa",
                reportado ? "Reportando la ubicación a tu cuenta." :
                        (seguimiento ? "Vigilando la ubicación de este teléfono." : "Seguimiento pausado desde el PC."));

        if (nuevo != intervaloActualS && nuevo > 0) {
            intervaloActualS = nuevo;
            // Reprograma en el hilo principal: FusedLocation lo exige.
            new android.os.Handler(Looper.getMainLooper()).post(this::pedirUbicaciones);
        }
    }

    /**
     * Abre (una vez) el canal en vivo con el PC. Cada orden del dueño despierta al teléfono al
     * instante; al (re)conectar se revisa la cola por si algo llegó mientras no había señal.
     */
    private void iniciarCanalVivo() {
        if (canalVivo != null) return;                 // ya abierto: se reconecta solo si se cae
        canalVivo = new co.guardiania.app.net.CanalEquipo(api, vinculo.token(), new co.guardiania.app.net.CanalEquipo.Oyente() {
            @Override public void evento(JSONObject e) {
                // El ping del PC (cada 30 s) despierta al teléfono: si toca reportar, se reporta.
                if ("ping".equals(e.optString("tipo")) || "conectado".equals(e.optString("tipo"))) {
                    reportarSiToca("canal");
                    return;
                }
                if (!"orden".equals(e.optString("tipo"))) return;
                if (e.optBoolean("comandos")) comandos.execute(TrackingService.this::procesarComandos);
                // Cambió el estado (reportado/pausado): un latido ya trae el ritmo y la notificación nuevos.
                red.execute(() -> latido("orden_pc", ultima));
            }

            @Override public void conectado(boolean si) {
                canalAbierto = si;
                if (si) comandos.execute(TrackingService.this::procesarComandos);
            }
        });
        canalVivo.iniciar();
    }

    /** Reporta si ya pasó el intervalo desde el último latido (con la última posición conocida). */
    private void reportarSiToca(String motivo) {
        if (api == null || red == null || red.isShutdown()) return;
        long margen = Math.max(10, intervaloActualS) * 1000L - 3000;
        if (System.currentTimeMillis() - ultimoLatidoMs >= margen) red.execute(() -> latido(motivo, ultima));
    }

    /**
     * Temporizador propio: antes solo se reportaba cuando el GPS entregaba una posición nueva; bajo
     * techo («Buscando señal») no llegaba ninguna y el PC marcaba el teléfono como desconectado.
     */
    private void iniciarTemporizador() {
        if (reloj != null) return;
        reloj = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        reloj.scheduleWithFixedDelay(() -> reportarSiToca("temporizador"), 15, 15, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Cuando vuelve el WiFi o los datos, el canal se reabre al instante y se reporta. */
    private void vigilarRed() {
        if (redCallback != null) return;
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        redCallback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network n) {
                if (canalVivo != null) canalVivo.reconectarYa();
                if (red != null && !red.isShutdown()) red.execute(() -> latido("red_disponible", ultima));
            }
        };
        try { cm.registerDefaultNetworkCallback(redCallback); } catch (Exception e) { redCallback = null; }
    }

    private void dejarDeVigilar() {
        if (reloj != null) { reloj.shutdownNow(); reloj = null; }
        if (redCallback != null) {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            try { if (cm != null) cm.unregisterNetworkCallback(redCallback); } catch (Exception ignored) {}
            redCallback = null;
        }
        canalAbierto = false;
    }

    /** Consulta la cola de comandos del PC y ejecuta cada uno (una vez por id). Corre en el hilo de comandos. */
    private synchronized void procesarComandos() {
        try {
            JSONObject auth = new JSONObject();
            auth.put("device_token", vinculo.token());
            ApiClient.Resp r = api.post("/devices/commands/pending", auth);
            if (!r.ok()) return;
            org.json.JSONArray lista = new org.json.JSONArray(r.body);
            if (lista.length() == 0) return;
            CommandRunner runner = new CommandRunner(this, vinculo);
            for (int i = 0; i < lista.length(); i++) {
                JSONObject cmd = lista.optJSONObject(i);
                if (cmd == null || !ejecutados.add(cmd.optString("id"))) continue;
                if ("photo".equals(cmd.optString("kind"))) {
                    ejecutarFoto(cmd);
                } else {
                    runner.ejecutar(cmd, this::localizarAhora);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudieron procesar comandos: " + e.getMessage());
        }
    }

    /** Toma una foto del intruso y la sube; luego confirma el comando. */
    private void ejecutarFoto(JSONObject cmd) {
        String id = cmd.optString("id");
        String firma = cmd.optString("signature");
        double lat = ultima != null ? ultima.getLatitude() : Double.NaN;
        double lon = ultima != null ? ultima.getLongitude() : Double.NaN;
        new IntruderCamera(this, vinculo).capturar(lat, lon, (ok, motivo) -> red.execute(() -> {
            try {
                JSONObject res = new JSONObject();
                res.put("capturada", ok);
                if (motivo != null) res.put("motivo", motivo);
                JSONObject ack = new JSONObject();
                ack.put("device_token", vinculo.token());
                ack.put("signature", firma);
                ack.put("status", ok ? "completed" : "failed");
                ack.put("result", res);
                api.post("/devices/commands/" + id + "/ack", ack);
            } catch (Exception ignored) {}
        }));
    }

    /** Fuerza un latido inmediato con la última posición (respuesta al comando "locate"). */
    private void localizarAhora() {
        if (ultima != null) latido("locate", ultima);
    }

    private int nivelBateria() {
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
    }

    private boolean cargando() {
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        return bm != null && bm.isCharging();
    }

    private void detenerTodo() {
        if (canalVivo != null) { canalVivo.detener(); canalVivo = null; }
        dejarDeVigilar();
        if (callback != null) fused.removeLocationUpdates(callback);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Lo borraron desde el PC (401 en el latido): se desvincula solo, en el teléfono también. */
    private void perderVinculo() {
        vinculo.borrar();
        detenerTodo();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        android.app.PendingIntent abrir = android.app.PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        Notification aviso = new NotificationCompat.Builder(this, CANAL)
                .setContentTitle("Teléfono desvinculado")
                .setContentText("Se eliminó este teléfono desde el panel del PC.")
                .setSmallIcon(R.drawable.ic_shield)
                .setAutoCancel(true)
                .setContentIntent(abrir)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        nm.notify(NOTIF_ID + 1, aviso);
    }

    // ─────────────────────────── notificación fija
    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel canal = new NotificationChannel(CANAL, "Protección de GuardianIA",
                    NotificationManager.IMPORTANCE_LOW);   // LOW: sin sonido, pero visible y persistente
            canal.setDescription("Mantiene el teléfono localizable si se pierde o lo roban.");
            canal.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(canal);
        }
    }

    private Notification construirNotificacion(String titulo, String texto) {
        PendingIntent abrir = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CANAL)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setSmallIcon(R.drawable.ic_shield)
                .setOngoing(true)               // el usuario no la puede deslizar para quitarla
                .setContentIntent(abrir)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void actualizarNotificacion(String titulo, String texto) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, construirNotificacion(titulo, texto));
    }

    @Override
    public void onDestroy() {
        if (callback != null) fused.removeLocationUpdates(callback);
        if (canalVivo != null) canalVivo.detener();
        dejarDeVigilar();
        if (red != null) red.shutdown();
        if (comandos != null) comandos.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;   // servicio "iniciado", no "ligado": no expone binder a otras apps
    }

    /** Arranca el servicio desde cualquier parte (UI, arranque del sistema, cambio de energía). */
    public static void iniciar(Context ctx, @Nullable String motivo) {
        Intent i = new Intent(ctx, TrackingService.class).setAction(ACCION_INICIAR);
        if (motivo != null) i.putExtra(ACCION_MOTIVO, motivo);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /** Botón «Conectar» de la app: reabre el canal con el PC y reporta ya. */
    public static void reconectar(Context ctx) {
        Intent i = new Intent(ctx, TrackingService.class).setAction(ACCION_RECONECTAR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void detener(Context ctx) {
        ctx.startService(new Intent(ctx, TrackingService.class).setAction(ACCION_DETENER));
    }
}
