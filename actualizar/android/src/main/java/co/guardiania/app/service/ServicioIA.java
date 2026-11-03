package co.guardiania.app.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import co.guardiania.app.GuardianApp;
import co.guardiania.app.R;
import co.guardiania.app.ai.ChatServicio;
import co.guardiania.app.ui.MainActivity;

/**
 * Mantiene viva la app mientras la IA escribe. Sin esto, si la persona se iba a otra app, Android
 * podía cerrar GuardianIA y la respuesta se quedaba a medias en el teléfono (el servidor la terminaba,
 * pero la pantalla no se enteraba). Muestra «GuardianIA está escribiendo…» y se apaga solo al terminar.
 */
public class ServicioIA extends Service {

    private static final int NOTIF_ESCRIBIENDO = 7501, NOTIF_LISTA = 7502;

    public static void iniciar(Context c) {
        Intent i = new Intent(c, ServicioIA.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {
            // Android 12+ no deja iniciarlo si la app ya está en segundo plano: la respuesta sigue en el
            // servidor y se retoma al volver.
        }
    }

    /** La respuesta terminó: se apaga el servicio y, si la persona no está mirando, se le avisa. */
    public static void respuestaLista(Context c, String vistaPrevia) {
        c.stopService(new Intent(c, ServicioIA.class));
        if (GuardianApp.enPrimerPlano()) return;
        PendingIntent abrir = PendingIntent.getActivity(c, 30, new Intent(c, MainActivity.class)
                .putExtra("abrir", "asistente").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String texto = vistaPrevia == null ? "" : vistaPrevia.trim();
        if (texto.length() > 140) texto = texto.substring(0, 140) + "…";
        Notification n = new NotificationCompat.Builder(c, GuardianApp.CANAL_IA_LISTA)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle("Tu respuesta está lista")
                .setContentText(texto.isEmpty() ? "Toca para verla" : texto)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        try { if (nm != null) nm.notify(NOTIF_LISTA, n); } catch (SecurityException ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PendingIntent abrir = PendingIntent.getActivity(this, 31, new Intent(this, MainActivity.class)
                .putExtra("abrir", "asistente").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, GuardianApp.CANAL_IA)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle("GuardianIA está escribiendo…")
                .setContentText("Puedes usar otras apps: la respuesta seguirá.")
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(abrir)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ESCRIBIENDO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_ESCRIBIENDO, n);
        // Si por algún motivo ya no hay respuesta en curso, no se queda encendido.
        if (!ChatServicio.de(this).escribiendo()) stopSelf();
        return START_NOT_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
