package co.guardiania.app.service;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

/**
 * Lo que impide que el teléfono cierre GuardianIA en segundo plano. Xiaomi/Redmi/POCO (HyperOS/MIUI)
 * cierran las apps con su propio ahorro de batería aunque tengan servicio en primer plano: por eso el
 * PC a veces veía el teléfono «Desconectado». Hay que permitir el «Inicio automático» y dejar la
 * batería «Sin restricciones».
 */
public final class Energia {

    private Energia() {}

    public static boolean esXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    public static boolean bateriaSinRestriccion(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** Abre la pantalla de «Inicio automático» de Xiaomi; en otras marcas, los ajustes de la app. */
    public static void abrirInicioAutomatico(Activity a) {
        Intent[] intentos = {
                new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                new Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))};
        for (Intent i : intentos) {
            try {
                a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {}
        }
    }

    /** Ahorro de batería de la app → «Sin restricciones». */
    public static void abrirBateria(Activity a) {
        try {
            if (!bateriaSinRestriccion(a)) {
                a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + a.getPackageName())));
                return;
            }
        } catch (Exception ignored) {}
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
        } catch (Exception ignored) {}
    }
}
