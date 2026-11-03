package co.guardiania.app.seguridad;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import co.guardiania.app.GuardianApp;
import co.guardiania.app.R;
import co.guardiania.app.ui.MainActivity;

/**
 * Aviso en el teléfono cuando el SOS se envió sin que la persona lo tocara (desde el PC o desde el
 * panel rápido con la pantalla bloqueada). Trae «Estoy bien» para avisar a los contactos que fue una
 * falsa alarma, con el mismo camino de SIM y confirmación que el SOS.
 */
public final class AvisoSos {

    static final int NOTIF_ID = 7401;
    static final String ACCION_BIEN = "co.guardiania.app.SOS_ESTOY_BIEN";

    private AvisoSos() {}

    public static void mostrar(Context c) {
        PendingIntent abrir = PendingIntent.getActivity(c, 10, new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent bien = PendingIntent.getBroadcast(c, 11, new Intent(c, Receptor.class).setAction(ACCION_BIEN),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, GuardianApp.CANAL_ACCION_REMOTA)
                .setSmallIcon(R.drawable.ic_sos)
                .setContentTitle("SOS enviado a tus contactos")
                .setContentText("Tus contactos recibieron un SMS con tu ubicación. Si estás bien, avísales.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText("Se envió un SOS con tu ubicación a tus contactos "
                        + "de emergencia. Si fue una falsa alarma, toca «Estoy bien» para avisarles."))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .addAction(R.drawable.ic_sos, "Estoy bien", bien);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try { if (nm != null) nm.notify(NOTIF_ID, b.build()); } catch (SecurityException ignored) {}
    }

    /** Botón «Estoy bien» de la notificación. */
    public static class Receptor extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!ACCION_BIEN.equals(i.getAction())) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
            PendingResult p = goAsync();
            Context app = c.getApplicationContext();
            new Thread(() -> {
                try {
                    SmsSos.estoyBien(app);
                    String id = SmsSos.envio(app).optString("incidente_id");
                    if (!id.isEmpty()) {
                        try { co.guardiania.app.net.Sesion.de(app).obj("POST", "/emergency/incidents/" + id + "/cancel",
                                new org.json.JSONObject()); } catch (Exception ignored) {}
                    }
                } finally {
                    p.finish();
                }
            }).start();
        }
    }
}
