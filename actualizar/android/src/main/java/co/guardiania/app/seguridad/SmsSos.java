package co.guardiania.app.seguridad;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import co.guardiania.app.data.Vinculo;
import co.guardiania.app.net.Sesion;

/**
 * SOS por SMS desde la SIM del propio teléfono. Llega a CUALQUIER celular (no necesita la app), no
 * usa datos ni WiFi —usa la señal celular— y no depende de un servicio pagado.
 *
 * <p>Honesto por diseño: cada contacto queda en «enviando», «enviado» (Android confirmó que el SMS
 * salió a la red del operador), «reintentando» (sin señal: se reintenta solo cada ~30 s durante 30 min)
 * o «no se pudo». La app nunca dice «enviado» sin la confirmación del sistema.
 *
 * <p>Además, apenas haya internet (datos o WiFi), el SOS se registra en el servidor, que avisa a los
 * que la SIM no alcanzó y guarda el incidente; el servidor no repite los SMS que ya salieron.
 */
public final class SmsSos {

    private static final String TAG = "GuardianIA/SmsSos";
    static final String ACCION_ENVIADO = "co.guardiania.app.SMS_SOS_ENVIADO";
    private static final String K_ENVIO = "envio", K_BIEN = "bien";
    private static final int MAX_INTENTOS = 60;                 // ~30 min a un intento cada 30 s
    private static final long ENTRE_INTENTOS_MS = 25_000;

    public interface Oyente { void cambio(JSONObject envio); }
    private static volatile Oyente oyente;

    private SmsSos() {}

    public static void observar(Oyente o) { oyente = o; }

    public static boolean tienePermiso(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    /** El envío en curso (o el último), para mostrar su estado. */
    public static JSONObject envio(Context c) { return leer(c, K_ENVIO); }

    /** El «estoy bien» en curso (mismo formato que el SOS). */
    public static JSONObject bien(Context c) { return leer(c, K_BIEN); }

    private static JSONObject leer(Context c, String clave) {
        try { return new JSONObject(AgendaSos.prefs(c).getString(clave, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /**
     * Manda el SOS a todos los contactos por SMS, ya. Devuelve el envío con el estado inicial de cada
     * contacto; las confirmaciones llegan después por {@link SmsEnviadoReceiver}.
     */
    public static synchronized JSONObject enviar(Context ctx, Double lat, Double lon, Integer precision, String nota) {
        return enviar(ctx, lat, lon, precision, nota, false);
    }

    /** @param remoto true si lo pidió el PC: el texto dice «podria necesitar ayuda (aviso desde su cuenta)». */
    public static synchronized JSONObject enviar(Context ctx, Double lat, Double lon, Integer precision, String nota,
                                                 boolean remoto) {
        Context c = ctx.getApplicationContext();
        ctxResumen = c;
        JSONObject e = new JSONObject();
        try {
            String hora = MensajeSos.hora(new Date());
            String nombre = new Vinculo(c).nombreUsuario();
            String texto = remoto ? MensajeSos.sosRemoto(nombre, lat, lon, precision, hora)
                    : MensajeSos.sos(nombre, lat, lon, precision, hora);
            e.put("id", Long.toString(System.currentTimeMillis(), 36));
            e.put("creado", System.currentTimeMillis());
            e.put("texto", texto);
            if (lat != null) { e.put("lat", lat); e.put("lon", lon); }
            if (nota != null) e.put("nota", nota);
            e.put("remoto", remoto);
            e.put("servidor", "pendiente");
            JSONArray lista = new JSONArray();
            JSONArray agenda = AgendaSos.contactos(c);
            for (int i = 0; i < agenda.length(); i++) {
                JSONObject a = agenda.getJSONObject(i);
                if (!a.optBoolean("sms", true)) continue;
                lista.put(new JSONObject().put("id", a.optString("id")).put("nombre", a.optString("nombre"))
                        .put("tel", a.optString("tel")).put("estado", "enviando").put("intentos", 0));
            }
            e.put("contactos", lista);
        } catch (Exception ex) {
            Log.w(TAG, "No se pudo preparar el SOS: " + ex.getMessage());
        }
        guardar(c, e);
        if (!tienePermiso(c)) {
            marcarTodos(c, "sin_permiso");
        } else {
            JSONArray cs = e.optJSONArray("contactos");
            for (int i = 0; cs != null && i < cs.length(); i++) mandar(c, K_ENVIO, e, i);
        }
        return envio(c);
    }

    /**
     * «Estoy bien» por SMS a quienes recibieron el SOS por la SIM. Va por el MISMO camino que el SOS:
     * primero la SIM que ya le llegó a esa persona, luego las demás, con confirmación de Android y
     * reintentos (antes se mandaba «a ciegas» por la SIM predeterminada y podía fallar sin aviso).
     * Devuelve a cuántos contactos se les está enviando.
     */
    public static synchronized int estoyBien(Context ctx) {
        Context c = ctx.getApplicationContext();
        JSONObject e = envio(c);
        JSONArray cs = e.optJSONArray("contactos");
        JSONArray destino = new JSONArray();
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject x = cs.optJSONObject(i);
            if (x == null) continue;
            String st = x.optString("estado");
            // Si el SOS seguía pendiente de enviarse, se cancela: ya no hace falta alarmar a nadie.
            if ("reintentando".equals(st) || "enviando".equals(st) || "sin_permiso".equals(st)) {
                poner(x, "estado", "cancelado");
            } else if ("enviado".equals(st)) {
                try {
                    JSONObject d = new JSONObject().put("id", x.optString("id")).put("nombre", x.optString("nombre"))
                            .put("tel", x.optString("tel")).put("estado", "enviando").put("intentos", 0);
                    if (x.has("sim_ok")) d.put("sim_ok", x.optInt("sim_ok"));   // la SIM que ya le llegó
                    destino.put(d);
                } catch (Exception ignored) {}
            }
        }
        poner(e, "cerrado", true);
        guardar(c, K_ENVIO, e);
        if (destino.length() == 0) return 0;
        JSONObject b = new JSONObject();
        poner(b, "id", "b" + Long.toString(System.currentTimeMillis(), 36));
        poner(b, "creado", System.currentTimeMillis());
        poner(b, "texto", MensajeSos.estoyBien(new Vinculo(c).nombreUsuario()));
        poner(b, "contactos", destino);
        guardar(c, K_BIEN, b);
        if (tienePermiso(c)) for (int i = 0; i < destino.length(); i++) mandar(c, K_BIEN, b, i);
        return destino.length();
    }

    /**
     * Reintenta lo pendiente: SMS sin señal y el registro en el servidor. Lo llama el servicio de
     * rastreo en cada latido (y la app al abrirse). Bloquea: llamar en segundo plano.
     */
    public static synchronized void reintentar(Context ctx) {
        Context c = ctx.getApplicationContext();
        JSONObject e = envio(c);
        if (e.optBoolean("cerrado") || !e.has("id")) return;
        long ahora = System.currentTimeMillis();
        if (ahora - e.optLong("creado") > 6 * 3_600_000L) return;          // un SOS de hace horas ya no se reintenta
        JSONArray cs = e.optJSONArray("contactos");
        if (tienePermiso(c) && ahora - e.optLong("ultimo_intento", 0) >= ENTRE_INTENTOS_MS) {
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject x = cs.optJSONObject(i);
                if (x != null && ("reintentando".equals(x.optString("estado")) || "sin_permiso".equals(x.optString("estado")))) {
                    mandar(c, K_ENVIO, e, i);
                }
            }
        }
        registrarEnServidor(c);
        reintentarBien(c);
    }

    private static void reintentarBien(Context c) {
        JSONObject b = bien(c);
        if (!b.has("id") || !tienePermiso(c)) return;
        long ahora = System.currentTimeMillis();
        if (ahora - b.optLong("creado") > 6 * 3_600_000L || ahora - b.optLong("ultimo_intento", 0) < ENTRE_INTENTOS_MS) return;
        JSONArray cs = b.optJSONArray("contactos");
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject x = cs.optJSONObject(i);
            if (x != null && "reintentando".equals(x.optString("estado"))) mandar(c, K_BIEN, b, i);
        }
    }

    /** Registra el SOS en la cuenta cuando hay internet. Solo una vez. */
    public static synchronized void registrarEnServidor(Context c) {
        JSONObject e = envio(c);
        if (!"pendiente".equals(e.optString("servidor")) || e.optBoolean("cerrado") || !Sesion.de(c).iniciada()) return;
        // Se espera a saber qué SMS salieron (máx. 15 s) para que el servidor no repita ninguno.
        if (hayEnviando(e) && System.currentTimeMillis() - e.optLong("creado") < 15_000) return;
        try {
            JSONObject cuerpo = new JSONObject().put("kind", "sos");
            if (e.has("nota")) cuerpo.put("note", e.optString("nota"));
            if (e.has("lat")) { cuerpo.put("lat", e.optDouble("lat")); cuerpo.put("lon", e.optDouble("lon")); }
            JSONArray ya = new JSONArray();
            JSONArray cs = e.optJSONArray("contactos");
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject x = cs.optJSONObject(i);
                if (x != null && "enviado".equals(x.optString("estado")) && !x.optString("id").isEmpty()) ya.put(x.optString("id"));
            }
            cuerpo.put("sms_desde_telefono", ya);
            JSONObject inc = Sesion.de(c).obj("POST", "/emergency/sos", cuerpo);
            JSONObject actual = envio(c);
            if (actual.optString("id").equals(e.optString("id"))) {
                poner(actual, "servidor", "ok");
                poner(actual, "incidente_id", inc.optString("id"));
                guardar(c, actual);
            }
        } catch (Exception ex) {
            Log.i(TAG, "El servidor aún no está disponible; se reintenta: " + ex.getMessage());
        }
    }

    // ─────────────────────────── interno
    private static void mandar(Context c, String clave, JSONObject e, int indice) {
        JSONArray cs = e.optJSONArray("contactos");
        JSONObject x = cs.optJSONObject(indice);
        try {
            int[] sims = sims(c, x.has("sim_ok") ? x.optInt("sim_ok") : -1);
            int subId = sims.length > 0 ? sims[x.optInt("intentos") % sims.length] : -1;   // si una SIM falla, la siguiente prueba otra
            poner(x, "sim", subId);
            SmsManager sm = manager(c, subId);
            ArrayList<String> partes = sm.divideMessage(e.optString("texto"));
            ArrayList<PendingIntent> avisos = new ArrayList<>();
            for (int p = 0; p < partes.size(); p++) {
                Intent i = new Intent(c, SmsEnviadoReceiver.class).setAction(ACCION_ENVIADO)
                        .putExtra("clave", clave).putExtra("envio", e.optString("id")).putExtra("contacto", indice)
                        .putExtra("parte", p).putExtra("partes", partes.size());
                int codigo = (int) ((System.nanoTime() & 0x7ffffff) + indice * 31 + p);
                avisos.add(PendingIntent.getBroadcast(c, codigo, i, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE));
            }
            poner(x, "estado", "enviando");
            poner(x, "intentos", x.optInt("intentos") + 1);
            poner(x, "partes_ok", 0);
            poner(e, "ultimo_intento", System.currentTimeMillis());
            guardar(c, clave, e);
            sm.sendMultipartTextMessage(x.optString("tel"), null, partes, avisos, null);
        } catch (Exception ex) {
            Log.w(TAG, "Falló el envío a " + x.optString("nombre") + ": " + ex.getMessage());
            poner(x, "estado", x.optInt("intentos") >= MAX_INTENTOS ? "fallo" : "reintentando");
            guardar(c, clave, e);
        }
    }

    /** Resultado de cada parte (lo llama el receptor con el código de Android). */
    static synchronized void resultado(Context c, String clave, String envioId, int indice, int partes, boolean ok, int codigo) {
        if (clave == null) clave = K_ENVIO;
        final String k = clave;
        JSONObject e = leer(c, k);
        if (!envioId.equals(e.optString("id"))) return;
        JSONArray cs = e.optJSONArray("contactos");
        JSONObject x = cs != null ? cs.optJSONObject(indice) : null;
        if (x == null || "cancelado".equals(x.optString("estado"))) return;
        if (ok) {
            int n = x.optInt("partes_ok") + 1;
            poner(x, "partes_ok", n);
            if (n >= partes) {
                poner(x, "estado", "enviado");
                if (x.has("sim")) poner(x, "sim_ok", x.optInt("sim"));   // esta SIM le llega: el «estoy bien» irá por aquí
            }
        } else {
            boolean fin = permanente(codigo) || x.optInt("intentos") >= MAX_INTENTOS;
            poner(x, "estado", fin ? "fallo" : "reintentando");
            poner(x, "error", motivo(codigo));
            poner(x, "codigo", codigo);
            // Con doble SIM, se prueba de inmediato con la otra antes de esperar el siguiente ciclo.
            int[] sims = sims(c);
            if (!fin && sims.length > 1 && x.optInt("intentos") < sims.length) {
                guardar(c, k, e);
                final int idx = indice;
                new Thread(() -> { synchronized (SmsSos.class) { mandar(c, k, leer(c, k), idx); } }).start();
                return;
            }
        }
        guardar(c, k, e);
        // Cuando ya se sabe el resultado de todos, se registra en el servidor (que no repetirá esos SMS).
        if (K_ENVIO.equals(k) && !hayEnviando(e)) new Thread(() -> registrarEnServidor(c)).start();
    }

    private static boolean hayEnviando(JSONObject e) {
        JSONArray cs = e.optJSONArray("contactos");
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject x = cs.optJSONObject(i);
            if (x != null && "enviando".equals(x.optString("estado"))) return true;
        }
        return false;
    }

    /** Traduce el código de Android a algo que la persona entienda (antes salía «error 111»). */
    static String motivo(int codigo) {
        switch (codigo) {
            case SmsManager.RESULT_ERROR_NO_SERVICE:
            case SmsManager.RESULT_RIL_NO_NETWORK_FOUND:
            case SmsManager.RESULT_RIL_NETWORK_NOT_READY: return "sin señal celular";
            case SmsManager.RESULT_ERROR_RADIO_OFF:
            case SmsManager.RESULT_RADIO_NOT_AVAILABLE:
            case SmsManager.RESULT_RIL_RADIO_NOT_AVAILABLE: return "modo avión o radio apagado";
            case SmsManager.RESULT_RIL_MODEM_ERR:
            case SmsManager.RESULT_MODEM_ERROR:
            case SmsManager.RESULT_RIL_INVALID_MODEM_STATE: return "la SIM no pudo enviarlo (¿sin plan de SMS o SIM equivocada?)";
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
            case SmsManager.RESULT_RIL_GENERIC_ERROR:
            case SmsManager.RESULT_NETWORK_REJECT:
            case SmsManager.RESULT_RIL_NETWORK_REJECT: return "el operador no lo aceptó (¿sin saldo?)";
            case SmsManager.RESULT_RIL_SIM_ABSENT:
            case SmsManager.RESULT_RIL_NO_SUBSCRIPTION:
            case SmsManager.RESULT_RIL_SUBSCRIPTION_NOT_AVAILABLE: return "no hay SIM activa";
            case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED:
            case SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED: return "límite de SMS del teléfono";
            case SmsManager.RESULT_RIL_BLOCKED_DUE_TO_CALL:
            case SmsManager.RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED: return "hay una llamada en curso";
            case SmsManager.RESULT_INVALID_ARGUMENTS:
            case SmsManager.RESULT_RIL_INVALID_ARGUMENTS: return "número inválido";
            case SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE: return "números fijos (FDN) activados en la SIM";
            case SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED:
            case SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED: return "número corto no permitido";
            case SmsManager.RESULT_RIL_ACCESS_BARRED:
            case SmsManager.RESULT_OPERATION_NOT_ALLOWED:
            case SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED:
            case SmsManager.RESULT_USER_NOT_ALLOWED: return "el operador o el teléfono bloquean el envío";
            default: return "error del teléfono (" + codigo + ")";
        }
    }

    /** Errores que NO se arreglan reintentando: se marcan como fallo y se ofrecen las alternativas. */
    static boolean permanente(int codigo) {
        switch (codigo) {
            case SmsManager.RESULT_INVALID_ARGUMENTS:
            case SmsManager.RESULT_RIL_INVALID_ARGUMENTS:
            case SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE:
            case SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED:
            case SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED:
            case SmsManager.RESULT_INVALID_SMS_FORMAT:
            case SmsManager.RESULT_RIL_INVALID_SMS_FORMAT:
            case SmsManager.RESULT_OPERATION_NOT_ALLOWED:
            case SmsManager.RESULT_RIL_OPERATION_NOT_ALLOWED:
            case SmsManager.RESULT_USER_NOT_ALLOWED:
            case SmsManager.RESULT_RIL_ACCESS_BARRED: return true;
            default: return false;
        }
    }

    // ─────────────────────────── doble SIM
    /**
     * SIM disponibles para SMS: primero la predeterminada, luego las demás. Con permiso de estado del
     * teléfono se ven todas; sin él, solo la predeterminada.
     */
    static int[] sims(Context c) { return sims(c, -1); }

    /** Igual, pero poniendo primero {@code primero} (la SIM que ya le llegó a ese contacto). */
    static int[] sims(Context c, int primero) {
        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
        if (primero >= 0) ids.add(primero);
        java.util.List<Integer> activas = new ArrayList<>();
        for (SimInfo si : simsActivas(c)) activas.add(si.id);
        // 1) La SIM que la persona eligió para el SOS (si sigue activa).
        int elegida = AgendaSos.simSos(c);
        if (elegida >= 0 && (activas.isEmpty() || activas.contains(elegida))) {
            ids.add(elegida);
            if (!AgendaSos.respaldoOtraSim(c)) return new int[]{elegida};
        }
        int pred = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
        if (pred != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) ids.add(pred);
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                android.telephony.SubscriptionManager sm = c.getSystemService(android.telephony.SubscriptionManager.class);
                java.util.List<android.telephony.SubscriptionInfo> l = sm != null ? sm.getActiveSubscriptionInfoList() : null;
                if (l != null) for (android.telephony.SubscriptionInfo si : l) ids.add(si.getSubscriptionId());
            } catch (SecurityException ignored) {}
        }
        int[] r = new int[ids.size()];
        int k = 0;
        for (int id : ids) r[k++] = id;
        return r;
    }

    /** Una SIM activa para mostrarla en la lista de elección. */
    public static final class SimInfo {
        public final int id;
        public final String nombre;
        SimInfo(int id, String nombre) { this.id = id; this.nombre = nombre; }
    }

    /** SIM activas del teléfono («SIM 1 · Claro», «SIM 2 · Tigo»). Requiere el permiso «Teléfono». */
    public static java.util.List<SimInfo> simsActivas(Context c) {
        java.util.List<SimInfo> r = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return r;
        try {
            android.telephony.SubscriptionManager sm = c.getSystemService(android.telephony.SubscriptionManager.class);
            java.util.List<android.telephony.SubscriptionInfo> l = sm != null ? sm.getActiveSubscriptionInfoList() : null;
            if (l != null) for (android.telephony.SubscriptionInfo si : l) {
                CharSequence op = si.getCarrierName() != null ? si.getCarrierName() : si.getDisplayName();
                r.add(new SimInfo(si.getSubscriptionId(), "SIM " + (si.getSimSlotIndex() + 1) + (op != null ? " · " + op : "")));
            }
        } catch (SecurityException ignored) {}
        return r;
    }

    /** Nombre de la SIM para mostrar («SIM 1 · Claro»). */
    static String nombreSim(Context c, int subId) {
        try {
            android.telephony.SubscriptionManager sm = c.getSystemService(android.telephony.SubscriptionManager.class);
            android.telephony.SubscriptionInfo si = sm != null ? sm.getActiveSubscriptionInfo(subId) : null;
            if (si != null) return "SIM " + (si.getSimSlotIndex() + 1) + (si.getCarrierName() != null ? " · " + si.getCarrierName() : "");
        } catch (Exception ignored) {}
        return "SIM";
    }

    @SuppressWarnings("deprecation")
    private static SmsManager manager(Context c, int subId) {
        if (subId >= 0) {
            if (Build.VERSION.SDK_INT >= 31) {
                SmsManager base = c.getSystemService(SmsManager.class);
                if (base != null) return base.createForSubscriptionId(subId);
            }
            return SmsManager.getSmsManagerForSubscriptionId(subId);
        }
        return manager(c);
    }

    @SuppressWarnings("deprecation")
    private static SmsManager manager(Context c) {
        if (Build.VERSION.SDK_INT >= 31) {
            SmsManager sm = c.getSystemService(SmsManager.class);    // SIM de SMS predeterminada (doble SIM)
            if (sm != null) return sm;
        }
        return SmsManager.getDefault();
    }

    private static void marcarTodos(Context c, String estado) {
        JSONObject e = envio(c);
        JSONArray cs = e.optJSONArray("contactos");
        for (int i = 0; cs != null && i < cs.length(); i++) poner(cs.optJSONObject(i), "estado", estado);
        guardar(c, e);
    }

    private static void guardar(Context c, JSONObject e) { guardar(c, K_ENVIO, e); }

    private static void guardar(Context c, String clave, JSONObject e) {
        SharedPreferences.Editor ed = AgendaSos.prefs(c).edit().putString(clave, e.toString());
        ed.commit();                                              // commit: el receptor puede leer al instante
        Oyente o = oyente;
        if (o != null && K_ENVIO.equals(clave)) co.guardiania.app.core.Hilos.ui(() -> o.cambio(e));
    }

    private static void poner(JSONObject o, String k, Object v) {
        if (o == null) return;
        try { o.put(k, v); } catch (Exception ignored) {}
    }

    /** Contactos a los que el SMS aún no llegó (para ofrecer WhatsApp o la app de mensajes). */
    public static java.util.List<JSONObject> pendientes(JSONObject e) {
        java.util.List<JSONObject> r = new ArrayList<>();
        JSONArray cs = e.optJSONArray("contactos");
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject x = cs.optJSONObject(i);
            String st = x != null ? x.optString("estado") : "";
            if (x != null && !"enviado".equals(st) && !"cancelado".equals(st) && !"enviando".equals(st)) r.add(x);
        }
        return r;
    }

    private static Context ctxResumen;   // solo para nombrar la SIM en el resumen

    /** Resumen legible para la pantalla: «Ana: enviado ✓ · Luis: sin señal, reintentando». */
    public static String resumen(JSONObject e) {
        JSONArray cs = e.optJSONArray("contactos");
        if (cs == null || cs.length() == 0) return "No hay contactos de emergencia guardados en el teléfono.";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cs.length(); i++) {
            JSONObject x = cs.optJSONObject(i);
            if (x == null) continue;
            String est;
            switch (x.optString("estado")) {
                case "enviado": est = "SMS enviado ✓" + (x.has("sim") && x.optInt("sim") >= 0 ? " (" + nombreSim(ctxResumen, x.optInt("sim")) + ")" : ""); break;
                case "enviando": est = "enviando…"; break;
                case "reintentando": est = x.optString("error", "sin señal") + ", reintentando solo"; break;
                case "sin_permiso": est = "falta el permiso de SMS"; break;
                case "cancelado": est = "cancelado"; break;
                default: est = "no se pudo enviar: " + x.optString("error", "error del teléfono");
            }
            b.append("• ").append(x.optString("nombre")).append(": ").append(est).append('\n');
        }
        String srv = e.optString("servidor");
        b.append("ok".equals(srv) ? "• Registrado en tu cuenta ✓" : "• Tu cuenta: se registra cuando haya internet");
        return b.toString();
    }
}
