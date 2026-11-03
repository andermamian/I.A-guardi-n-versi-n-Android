package co.guardiania.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import co.guardiania.app.data.Vinculo;

/**
 * Reacciona a dos momentos que una página web nunca conoce:
 *
 * <ul>
 *   <li><b>BOOT_COMPLETED</b> — el teléfono terminó de encender. Si estaba vinculado, el servicio de
 *       rastreo vuelve solo, sin que nadie abra la app. Así, si el ladrón reinicia el equipo, el
 *       rastreo se reanuda. El primer latido lleva el evento "encendido".</li>
 *   <li><b>ACTION_SHUTDOWN</b> — el usuario mandó apagar. Hay pocos segundos antes de que el sistema
 *       corte todo, así que se dispara un latido "apagando" para que el PC registre la última señal
 *       de vida y su ubicación. No siempre llega (un apagado por batería agotada o un reinicio
 *       forzado no avisan), pero cuando llega, el dueño sabe que el equipo se apagó y dónde estaba.</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "GuardianIA/Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String accion = intent != null ? intent.getAction() : null;
        if (accion == null) return;

        if (!new Vinculo(context).estaVinculado()) return;   // nada que hacer si no está vinculado

        if (Intent.ACTION_BOOT_COMPLETED.equals(accion)) {
            Log.i(TAG, "Teléfono encendido: reanudando rastreo");
            TrackingService.iniciar(context, "encendido");
        } else if (Intent.ACTION_SHUTDOWN.equals(accion)) {
            Log.i(TAG, "Teléfono apagándose: enviando última señal");
            // El servicio ya está vivo; se le pide un latido inmediato con el motivo.
            TrackingService.iniciar(context, "apagando");
        }
    }
}
