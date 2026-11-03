package co.guardiania.app.seguridad;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android confirma aquí si cada parte del SMS salió a la red del operador. */
public class SmsEnviadoReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!SmsSos.ACCION_ENVIADO.equals(i.getAction())) return;
        int codigo = getResultCode();
        PendingResult pendiente = goAsync();
        new Thread(() -> {
            try {
                SmsSos.resultado(c, i.getStringExtra("clave"), i.getStringExtra("envio"), i.getIntExtra("contacto", -1),
                        i.getIntExtra("partes", 1), codigo == Activity.RESULT_OK, codigo);
            } finally {
                pendiente.finish();
            }
        }).start();
    }
}
