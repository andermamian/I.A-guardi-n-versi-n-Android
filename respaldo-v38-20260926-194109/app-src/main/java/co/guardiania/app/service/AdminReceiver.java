package co.guardiania.app.service;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Permiso de "Administrador del dispositivo": lo unico que en Android permite a una app bloquear la
 * pantalla de verdad. Es un permiso especial que el usuario concede en una pantalla del sistema (no
 * basta con instalar la app). Se pide una vez, al configurar la proteccion.
 *
 * <p>Con esto, el comando "Bloquear" del PC bloquea la pantalla real del telefono, no solo una capa
 * dentro de la app.
 *
 * <p>Limite honesto: el usuario puede quitar este permiso desde Ajustes, y al hacerlo Android exige
 * confirmacion. Es el maximo que Android concede a una app que no sea la de fabrica del telefono.
 */
public class AdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "GuardianIA/Admin";

    public static ComponentName componente(Context ctx) {
        return new ComponentName(ctx.getApplicationContext(), AdminReceiver.class);
    }

    public static boolean estaActivo(Context ctx) {
        DevicePolicyManager dpm = dpm(ctx);
        return dpm != null && dpm.isAdminActive(componente(ctx));
    }

    public static Intent intentActivar(Context ctx) {
        return new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componente(ctx))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Con este permiso, GuardianIA puede bloquear la pantalla del telefono a "
                                + "distancia si se pierde o te lo roban.");
    }

    public static boolean bloquearAhora(Context ctx) {
        DevicePolicyManager dpm = dpm(ctx);
        if (dpm == null || !dpm.isAdminActive(componente(ctx))) return false;
        try {
            dpm.lockNow();
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "lockNow denegado: " + e.getMessage());
            return false;
        }
    }

    private static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getApplicationContext()
                .getSystemService(Context.DEVICE_POLICY_SERVICE);
    }
}
