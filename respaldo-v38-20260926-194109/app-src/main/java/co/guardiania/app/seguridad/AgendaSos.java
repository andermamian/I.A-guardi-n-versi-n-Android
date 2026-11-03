package co.guardiania.app.seguridad;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Copia en el teléfono de los contactos de emergencia. El SOS por SMS debe funcionar SIN internet,
 * así que no puede depender de pedirle la lista al servidor en ese momento. Se actualiza cada vez que
 * la app ve los contactos (pantalla Contactos, al abrir la app con sesión).
 */
public final class AgendaSos {

    private static final String PREFS = "guardian_sos", K_CONTACTOS = "agenda";

    private AgendaSos() {}

    /** Guarda los contactos tal como los devuelve /emergency/contacts (campo «contactos»). */
    public static void guardar(Context c, JSONArray contactos) {
        if (contactos == null) return;
        JSONArray limpia = new JSONArray();
        for (int i = 0; i < contactos.length(); i++) {
            JSONObject x = contactos.optJSONObject(i);
            if (x == null || x.optString("phone_e164").isEmpty()) continue;
            try {
                limpia.put(new JSONObject().put("id", x.optString("id")).put("nombre", x.optString("name"))
                        .put("tel", x.optString("phone_e164")).put("prioridad", x.optInt("priority", 1))
                        .put("sms", x.optBoolean("notify_sms", true)));
            } catch (Exception ignored) {}
        }
        prefs(c).edit().putString(K_CONTACTOS, limpia.toString()).apply();
    }

    public static JSONArray contactos(Context c) {
        try { return new JSONArray(prefs(c).getString(K_CONTACTOS, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    public static int cuantos(Context c) { return contactos(c).length(); }

    /** SIM elegida para el SOS (id de suscripción) o -1 = automática (la de SMS predeterminada). */
    public static int simSos(Context c) { return prefs(c).getInt("sim_sos", -1); }

    public static void setSimSos(Context c, int subId) { prefs(c).edit().putInt("sim_sos", subId).apply(); }

    /** Si la SIM elegida falla, ¿probar con la otra? (recomendado: sí). */
    public static boolean respaldoOtraSim(Context c) { return prefs(c).getBoolean("sim_respaldo", true); }

    public static void setRespaldoOtraSim(Context c, boolean v) { prefs(c).edit().putBoolean("sim_respaldo", v).apply(); }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
