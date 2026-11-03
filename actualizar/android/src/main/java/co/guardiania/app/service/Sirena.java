package co.guardiania.app.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import co.guardiania.app.GuardianApp;
import co.guardiania.app.R;

/**
 * La alarma remota «¡Aquí estoy!» con sonido PROPIO de GuardianIA (res/raw/guardian_sirena), distinto
 * del despertador y de cualquier tono del teléfono, para que se reconozca al instante.
 *
 * <p>Suena desde el servicio, no desde una pantalla: Android 10+ impide abrir pantallas desde segundo
 * plano, así que antes, con la app cerrada, la alarma podía no sonar. La pantalla completa se muestra
 * con una notificación de pantalla completa (permitida) y trae el botón «Detener».
 */
public final class Sirena {

    private static final String TAG = "GuardianIA/Sirena";
    static final int NOTIF_ID = 7301;
    public static final String ACCION_DETENER = "co.guardiania.app.DETENER_ALARMA";

    private static MediaPlayer reproductor;
    private static int volumenPrevio = -1;
    private static final Handler PRINCIPAL = new Handler(Looper.getMainLooper());
    private static final Runnable FIN = () -> detenerInterno(null);
    private static Context app;

    private Sirena() {}

    public static synchronized boolean sonando() { return reproductor != null; }

    /** Suena al máximo volumen de alarma durante {@code segundos} (o hasta «Detener»). */
    public static synchronized boolean sonar(Context ctx, int segundos) {
        app = ctx.getApplicationContext();
        detenerInterno(null);
        boolean ok = true;
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                volumenPrevio = am.getStreamVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            }
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)                 // suena aunque esté en silencio
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(app, Uri.parse("android.resource://" + app.getPackageName() + "/" + R.raw.guardian_sirena));
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            reproductor = mp;
        } catch (Exception e) {
            Log.w(TAG, "No se pudo reproducir la sirena: " + e.getMessage());
            ok = false;
        }
        vibrar(true);
        mostrarPantalla("¡Aquí estoy!", "Este teléfono está sonando porque su dueño lo está buscando.", true);
        PRINCIPAL.removeCallbacks(FIN);
        PRINCIPAL.postDelayed(FIN, Math.max(5, Math.min(segundos, 300)) * 1000L);
        return ok;
    }

    public static synchronized void detener(Context ctx) { detenerInterno(ctx); }

    private static synchronized void detenerInterno(Context ctx) {
        Context c = ctx != null ? ctx.getApplicationContext() : app;
        PRINCIPAL.removeCallbacks(FIN);
        if (reproductor != null) {
            try { reproductor.stop(); } catch (Exception ignored) {}
            reproductor.release();
            reproductor = null;
        }
        if (c == null) return;
        vibrar(false);
        if (volumenPrevio >= 0) {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) try { am.setStreamVolume(AudioManager.STREAM_ALARM, volumenPrevio, 0); } catch (Exception ignored) {}
            volumenPrevio = -1;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    private static void vibrar(boolean si) {
        if (app == null) return;
        Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (!si) { v.cancel(); return; }                             // antes la vibración nunca se detenía
        long[] patron = {0, 700, 300, 700, 300, 1200, 600};
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(patron, 0));
        else v.vibrate(patron, 0);
    }

    /**
     * Pantalla sobre el bloqueo con una notificación de pantalla completa. La alarma usa su canal sin
     * sonido (la sirena ya suena); mensajes y bloqueo usan el canal con el aviso propio de GuardianIA.
     */
    static void mostrarPantalla(String titulo, String texto, boolean alarma) {
        mostrarPantalla(app, titulo, texto, alarma, true);
    }

    static void mostrarPantalla(Context c, String titulo, String texto, boolean alarma, boolean cerrable) {
        if (c == null) return;
        Intent abrir = AlertActivity.intent(c, titulo, texto, cerrable, alarma);
        PendingIntent pantalla = PendingIntent.getActivity(c, alarma ? 1 : 2, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c,
                alarma ? GuardianApp.CANAL_ALARMA_REMOTA : GuardianApp.CANAL_ACCION_REMOTA)
                .setSmallIcon(R.drawable.ic_escudo)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(alarma ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pantalla, true)
                .setContentIntent(pantalla)
                .setOngoing(alarma || !cerrable)
                .setAutoCancel(!alarma && cerrable);
        if (alarma) {
            PendingIntent parar = PendingIntent.getBroadcast(c, 3,
                    new Intent(c, DetenerAlarmaReceiver.class).setAction(ACCION_DETENER),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(R.drawable.ic_detener, "Detener", parar);
        }
        Notification n = b.build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            if (nm != null) nm.notify(alarma ? NOTIF_ID : NOTIF_ID + 1, n);
        } catch (SecurityException e) {
            Log.w(TAG, "Sin permiso de notificaciones: " + e.getMessage());
        }
        // Si Android lo permite (app visible o permiso especial), se abre directo.
        try { c.startActivity(abrir); } catch (Exception ignored) {}
    }
}
