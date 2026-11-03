package co.guardiania.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import co.guardiania.app.ai.RedIntencion;
import co.guardiania.app.core.Hilos;

/**
 * Punto de arranque del proceso. Crea los canales de notificación una sola vez (Android los
 * recuerda) y precarga en segundo plano la red neuronal de intención, para que el primer mensaje
 * del asistente no espere a leer 300 kB de pesos.
 */
public class GuardianApp extends Application {

    public static final String CANAL_PROTECCION = "guardiania_proteccion";
    public static final String CANAL_ALERTAS = "guardiania_alertas";
    /** Mensajes y bloqueo remotos: suenan con el aviso propio de GuardianIA (res/raw/guardian_aviso). */
    /** La IA escribiendo en segundo plano (silencioso) y «respuesta lista» (normal). */
    public static final String CANAL_IA = "guardiania_ia_escribiendo", CANAL_IA_LISTA = "guardiania_ia_lista";

    /** ¿Hay alguna pantalla de la app visible? (para avisar «respuesta lista» solo si no se está mirando). */
    private static int visibles = 0;
    public static boolean enPrimerPlano() { return visibles > 0; }

    public static final String CANAL_ACCION_REMOTA = "guardiania_accion_remota_v1";
    /** Alarma «¡Aquí estoy!»: el canal no suena; la sirena la reproduce el servicio. */
    public static final String CANAL_ALARMA_REMOTA = "guardiania_alarma_remota_v1";

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(android.app.Activity a) { visibles++; }
            @Override public void onActivityStopped(android.app.Activity a) { visibles = Math.max(0, visibles - 1); }
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityResumed(android.app.Activity a) {}
            @Override public void onActivityPaused(android.app.Activity a) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(android.app.Activity a) {}
        });
        crearCanales();
        Hilos.fondo(() -> RedIntencion.de(this));
        // v3.8: solo su servidor GuardianIA VPN y Tor. Lo que quedara de ProtonVPN/WARP se borra una vez.
        Hilos.fondo(() -> { try { co.guardiania.app.vpn.MotorVpn.de(this).purgarTerceros(); } catch (Exception ignored) {} });
        // «VPN siempre activa» de Android: si el sistema arranca el túnel (al encender el teléfono o
        // tras un corte), se levanta con el último perfil elegido.
        com.wireguard.android.backend.GoBackend.setAlwaysOnCallback(() -> Hilos.fondo(() -> {
            try {
                co.guardiania.app.vpn.MotorVpn m = co.guardiania.app.vpn.MotorVpn.de(this);
                if (m.almacen().perfil() != null && !m.activo()) m.conectar();
            } catch (Exception e) {
                android.util.Log.w("GuardianApp", "VPN siempre activa: no se pudo conectar", e);
            }
        }));
    }

    private void crearCanales() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel proteccion = new NotificationChannel(CANAL_PROTECCION, "Protección de GuardianIA",
                NotificationManager.IMPORTANCE_LOW);    // fija y silenciosa: solo indica que el rastreo vive
        proteccion.setDescription("Mantiene el teléfono localizable si se pierde o lo roban.");
        proteccion.setShowBadge(false);

        NotificationChannel alertas = new NotificationChannel(CANAL_ALERTAS, "Alertas de seguridad",
                NotificationManager.IMPORTANCE_HIGH);   // lo que el servidor considera urgente
        alertas.setDescription("Avisos de tu cuenta: dispositivos, emergencias, apps peligrosas.");


        nm.createNotificationChannel(proteccion);
        nm.createNotificationChannel(alertas);
        nm.deleteNotificationChannel("guardiania_salud");   // la función de salud se retiró

        // Las acciones remotas tienen su PROPIO sonido para distinguirlas de cualquier otra notificación.
        NotificationChannel accion = new NotificationChannel(CANAL_ACCION_REMOTA, "Acciones remotas del dueño",
                NotificationManager.IMPORTANCE_HIGH);
        accion.setDescription("Mensajes y bloqueo enviados desde tu PC.");
        accion.setSound(android.net.Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.guardian_aviso),
                new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        accion.enableVibration(true);
        accion.setVibrationPattern(new long[]{0, 250, 150, 250, 150, 500});
        accion.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(accion);

        NotificationChannel alarma = new NotificationChannel(CANAL_ALARMA_REMOTA, "Alarma remota «¡Aquí estoy!»",
                NotificationManager.IMPORTANCE_HIGH);
        alarma.setDescription("Muestra la pantalla de la alarma cuando la haces sonar desde tu PC.");
        alarma.setSound(null, null);                  // la sirena ya suena; sin doble sonido
        alarma.enableVibration(false);                // la vibración la maneja la sirena (y se detiene)
        alarma.setBypassDnd(true);
        alarma.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alarma);

        NotificationChannel ia = new NotificationChannel(CANAL_IA, "Asistente escribiendo", NotificationManager.IMPORTANCE_LOW);
        ia.setDescription("Mantiene la respuesta de la IA mientras usas otras apps.");
        ia.setShowBadge(false);
        nm.createNotificationChannel(ia);
        NotificationChannel lista = new NotificationChannel(CANAL_IA_LISTA, "Respuestas del asistente",
                NotificationManager.IMPORTANCE_DEFAULT);
        lista.setDescription("Aviso cuando la IA termina de responder y no estás en la app.");
        nm.createNotificationChannel(lista);
    }
}
