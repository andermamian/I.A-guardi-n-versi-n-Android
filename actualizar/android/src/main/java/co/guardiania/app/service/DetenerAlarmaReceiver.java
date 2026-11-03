package co.guardiania.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Botón «Detener» de la notificación de la alarma remota. */
public class DetenerAlarmaReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Sirena.ACCION_DETENER.equals(i.getAction())) Sirena.detener(c);
    }
}
