package co.guardiania.app.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import co.guardiania.app.net.Sesion;

/**
 * UN solo botón para la privacidad del teléfono, con SOLO dos vías: su servidor GuardianIA VPN (Google
 * Cloud) y Tor integrado. ProtonVPN, WARP y los .conf de terceros se retiraron en v3.8 (varias VPN a la
 * vez generaban conflictos; Android admite una sola).
 *
 * <ul>
 *   <li>{@link #AUTO} — la IA ({@link CerebroVpn}) elige la vía y cambia sola si algo empeora.</li>
 *   <li>{@link #SERVIDOR} — túnel WireGuard a su servidor: cifra todo el teléfono.</li>
 *   <li>{@link #TOR} — Tor integrado (3 saltos), proxy 127.0.0.1:8118.</li>
 *   <li>{@link #SERVIDOR_TOR} — Tor dentro del túnel: el operador solo ve su servidor, el servidor solo ve
 *       Tor cifrado y el sitio solo ve un relevo Tor.</li>
 * </ul>
 * Cada cambio APAGA lo que sobra antes de encender lo nuevo. Si su servidor falla, nunca le deja
 * desprotegido: pasa a Tor y vuelve a su servidor cuando responde ({@link #revisar}).
 */
public final class ModoPrivacidad {

    public static final String APAGADO = "apagado", AUTO = "auto", SERVIDOR = "servidor", TOR = "tor",
            SERVIDOR_TOR = "servidor_tor";
    private static final String PREFS = "guardian_privacidad";

    private ModoPrivacidad() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Nombres de v3.5–3.7 («proton») = su servidor. */
    static String normalizar(String m) {
        if ("proton".equals(m)) return SERVIDOR;
        if ("proton_tor".equals(m)) return SERVIDOR_TOR;
        if (AUTO.equals(m) || SERVIDOR.equals(m) || TOR.equals(m) || SERVIDOR_TOR.equals(m)) return m;
        return APAGADO;
    }

    /** Lo que eligió la persona (puede ser «auto»). */
    public static String actual(Context c) { return normalizar(prefs(c).getString("modo", APAGADO)); }
    /** Lo que está funcionando de verdad (en «auto», lo que eligió la IA; con respaldo, Tor). */
    public static String efectivo(Context c) {
        String m = actual(c);
        if (APAGADO.equals(m)) return APAGADO;
        String e = normalizar(prefs(c).getString("efectivo", m));
        return AUTO.equals(e) ? APAGADO : e;
    }
    public static String objetivo(Context c) {
        String o = prefs(c).getString("objetivo", CerebroVpn.EQUILIBRIO);
        return CerebroVpn.VELOCIDAD.equals(o) || CerebroVpn.ANONIMATO.equals(o) ? o : CerebroVpn.EQUILIBRIO;
    }
    public static boolean respaldo(Context c) { return prefs(c).getBoolean("respaldo", false); }

    public static boolean usaServidor(String m) { return CerebroVpn.usaServidor(m); }
    public static boolean usaTor(String m) { return CerebroVpn.usaTor(m); }

    public static String nombre(String m) {
        switch (normalizar(m)) {
            case AUTO: return "Automático (IA)";
            case SERVIDOR: return "Mi servidor";
            case TOR: return "Tor";
            case SERVIDOR_TOR: return "Servidor + Tor";
            default: return "Apagado";
        }
    }

    // ─────────────────────────── memoria y última decisión de la IA
    public static JSONObject memoria(Context c) {
        try { return new JSONObject(prefs(c).getString("memoria", "")); } catch (Exception e) { return CerebroVpn.memoriaVacia(); }
    }
    private static void aprender(Context c, String modo, boolean exito, Double segundos) {
        JSONObject m = CerebroVpn.aprender(memoria(c), modo, exito, segundos, ahora());
        prefs(c).edit().putString("memoria", m.toString()).apply();
    }
    /** Última decisión (modo, razones, confianza, puntajes) para mostrarla en pantalla. */
    public static JSONObject ultimaDecision(Context c) {
        try { return new JSONObject(prefs(c).getString("decision", "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    private static void anotar(Context c, CerebroVpn.Decision d) {
        try {
            JSONObject pj = new JSONObject();
            for (java.util.Map.Entry<String, Double> e : d.puntajes.entrySet()) pj.put(e.getKey(), e.getValue());
            prefs(c).edit().putString("decision", new JSONObject().put("modo", d.modo == null ? JSONObject.NULL : d.modo)
                    .put("razones", new JSONArray(d.razones)).put("confianza", d.confianza).put("puntajes", pj)
                    .put("cuando", ahora()).toString()).apply();
        } catch (Exception ignored) {}
    }
    static double ahora() { return System.currentTimeMillis() / 1000.0; }

    // ─────────────────────────── señales (bloquea: segundo plano)
    public static CerebroVpn.Senales senales(Context ctx) {
        Context c = ctx.getApplicationContext();
        CerebroVpn.Senales s = new CerebroVpn.Senales();
        s.servidorConfigurado = MotorVpn.de(c).tienePropio();
        s.torDisponible = true;                       // Tor viene integrado en la app
        s.redInsegura = RedInsegura.detectar(c);
        if (s.servidorConfigurado && Sesion.de(c).iniciada()) {
            // Lo pregunta al PC (que sí puede hablar con el servidor). Lejos de casa no responde: null.
            try {
                JSONObject r = Sesion.de(c).obj("GET", "/vpn/propio/salud", null);
                if (r.optBoolean("configurado")) {
                    if (!r.isNull("en_linea") && r.has("en_linea")) s.servidorEnLinea = r.optBoolean("en_linea");
                    if (!r.isNull("latencia_ms") && r.has("latencia_ms")) s.latenciaServidorMs = r.optInt("latencia_ms");
                    if (!r.isNull("consumo_fraccion") && r.has("consumo_fraccion")) s.consumoFraccion = r.optDouble("consumo_fraccion");
                }
            } catch (Exception ignored) {}
        }
        return s;
    }

    // ─────────────────────────── el botón
    /** Compatibilidad con llamadas anteriores (panel rápido, etc.). */
    public static JSONObject cambiar(Context ctx, String nuevo) throws IOException {
        return cambiar(ctx, nuevo, objetivo(ctx));
    }

    /**
     * Deja el teléfono en {@code nuevo} (o lo que decida la IA en «auto»). Bloquea: segundo plano.
     * Devuelve {efectivo, razon, aviso, decision}.
     */
    public static JSONObject cambiar(Context ctx, String nuevo, String objetivo) throws IOException {
        Context c = ctx.getApplicationContext();
        nuevo = normalizar(nuevo);
        MotorVpn motor = MotorVpn.de(c);
        int purgadas = motor.purgarTerceros();
        prefs(c).edit().putString("modo", nuevo).putString("objetivo", objetivo).putBoolean("respaldo", false).apply();
        JSONObject r = new JSONObject();
        String aviso = purgadas > 0 ? "Se retiraron las VPN de terceros que quedaban (ProtonVPN/WARP)." : "";
        try {
            if (APAGADO.equals(nuevo)) {
                aplicar(c, APAGADO);
                return r.put("efectivo", APAGADO).put("razon", "Protección apagada.").put("aviso", aviso);
            }
            CerebroVpn.Senales s = senales(c);
            if (AUTO.equals(nuevo)) {
                java.util.List<String> probados = new java.util.ArrayList<>();
                String ultimoError = "";
                for (int i = 0; i < 3; i++) {
                    CerebroVpn.Decision d = CerebroVpn.decidir(s, objetivo, memoria(c), ahora(), probados.toArray(new String[0]));
                    anotar(c, d);
                    if (d.modo == null) break;
                    try {
                        aplicar(c, d.modo);
                        return r.put("efectivo", d.modo).put("razon", d.razones.get(0) + " " + d.razones.get(1)).put("aviso", aviso);
                    } catch (IOException e) {
                        probados.add(d.modo);
                        ultimoError = e.getMessage();
                    }
                }
                throw new IOException("No pude protegerte con ninguna vía: " + ultimoError);
            }
            anotar(c, CerebroVpn.decidir(s, objetivo, memoria(c), ahora()));   // la IA opina aunque elijas a mano
            try {
                aplicar(c, nuevo);
                return r.put("efectivo", nuevo).put("razon", razonManual(nuevo)).put("aviso", aviso);
            } catch (IOException e) {
                if (!usaServidor(nuevo)) throw e;
                aplicar(c, TOR);                       // nunca desprotegido: Tor mientras vuelve tu servidor
                prefs(c).edit().putBoolean("respaldo", true).apply();
                String a = "Tu servidor no conectó (" + e.getMessage() + "). Te protegí con Tor y volveré a tu servidor en cuanto responda.";
                return r.put("efectivo", TOR).put("razon", a).put("aviso", (aviso.isEmpty() ? "" : aviso + " ") + a);
            }
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    static String razonManual(String m) {
        switch (m) {
            case SERVIDOR: return "Conectado a tu servidor GuardianIA VPN: todo el teléfono va cifrado.";
            case TOR: return "Tor activo: tres relevos entre tú y cada sitio.";
            default: return "Tor dentro de tu servidor: máxima privacidad.";
        }
    }

    /** Deja el teléfono EXACTAMENTE en {@code modo}. Servidor primero: así Tor arranca dentro del túnel. */
    static void aplicar(Context c, String modo) throws IOException {
        MotorVpn motor = MotorVpn.de(c);
        if (!usaTor(modo)) TorInterno.detener(c);
        if (!usaServidor(modo) && motor.activo()) motor.desconectar();
        if (usaServidor(modo) && !motor.activo()) {
            long t0 = System.currentTimeMillis();
            try {
                motor.conectarPropio();
            } catch (IOException e) {
                aprender(c, modo, false, null);
                throw e;
            }
            aprender(c, modo, true, (System.currentTimeMillis() - t0) / 1000.0);
        }
        if (usaTor(modo) && !TorInterno.activo() && !"STARTING".equals(TorInterno.estado())) {
            TorInterno.iniciar(c);
            prefs(c).edit().putString("tor_pendiente", modo).putLong("tor_desde", System.currentTimeMillis()).apply();
        }
        prefs(c).edit().putString("efectivo", modo).putLong("cambio", System.currentTimeMillis()).apply();
    }

    /**
     * Vigilante: revisa y repara. Lo llaman la pantalla VPN (cada 30 s mientras está abierta) y el servicio
     * de Tor. Devuelve qué hizo o null. Bloquea: segundo plano.
     */
    public static String revisar(Context ctx) {
        Context c = ctx.getApplicationContext();
        String modo = actual(c);
        if (APAGADO.equals(modo)) return null;
        MotorVpn motor = MotorVpn.de(c);
        // Aprender cuánto tardó Tor en quedar listo.
        String pendiente = prefs(c).getString("tor_pendiente", null);
        if (pendiente != null && TorInterno.activo()) {
            aprender(c, pendiente, true, (System.currentTimeMillis() - prefs(c).getLong("tor_desde", 0)) / 1000.0);
            prefs(c).edit().remove("tor_pendiente").apply();
        }
        String ef = efectivo(c);
        boolean sano = (!usaServidor(ef) || motor.activo())
                && (!usaTor(ef) || TorInterno.activo() || "STARTING".equals(TorInterno.estado()));
        try {
            if (AUTO.equals(modo)) {
                CerebroVpn.Decision d = CerebroVpn.decidir(senales(c), objetivo(c), memoria(c), ahora());
                anotar(c, d);
                if (CerebroVpn.convieneCambiar(ef, d, prefs(c).getLong("cambio", 0) / 1000.0, sano, ahora())) {
                    aplicar(c, d.modo);
                    return "cambio_ia";
                }
                if (!sano) { aplicar(c, ef); return "reparado"; }
                return null;
            }
            if (respaldo(c)) {
                if (Boolean.TRUE.equals(senales(c).servidorEnLinea)) {
                    aplicar(c, modo);
                    prefs(c).edit().putBoolean("respaldo", false).apply();
                    return "restaurado";
                }
                if (!sano) { aplicar(c, TOR); return "reparado"; }
                return null;
            }
            if (!sano) {
                try {
                    aplicar(c, ef);
                    return "reparado";
                } catch (IOException e) {
                    if (!usaServidor(ef)) return "fallo";
                    aplicar(c, TOR);
                    prefs(c).edit().putBoolean("respaldo", true).apply();
                    return "respaldo_tor";
                }
            }
        } catch (IOException e) {
            return "fallo";
        }
        return null;
    }
}
