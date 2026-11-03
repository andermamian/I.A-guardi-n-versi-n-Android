package co.guardiania.app.vpn;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cerebro de la VPN: decide cómo proteger la conexión con solo dos vías — su servidor GuardianIA VPN
 * (Google Cloud) y Tor — y aprende de lo que funciona en ESTE teléfono.
 *
 * <p>Es el mismo modelo que app/ai/cerebro_vpn.py en el PC (mismos pesos): un modelo de puntuación con
 * rasgos explicables + aprendizaje en línea (promedios móviles de éxito y tiempo por modo) + histéresis
 * para no saltar de un modo a otro por una medición aislada. CerebroVpnTest comprueba los MISMOS casos
 * (tests/fixtures/cerebro_vpn_casos.json) para que el PC y el celular decidan igual.
 *
 * <p>Por qué no un modelo de lenguaje: la decisión debe ser instantánea, funcionar sin internet y poder
 * explicarse en una frase.
 */
public final class CerebroVpn {

    public static final String SERVIDOR = "servidor", TOR = "tor", SERVIDOR_TOR = "servidor_tor";
    public static final String[] CANDIDATOS = {SERVIDOR, TOR, SERVIDOR_TOR};
    public static final String EQUILIBRIO = "equilibrio", VELOCIDAD = "velocidad", ANONIMATO = "anonimato";

    static final int LATENCIA_ALTA_MS = 250;
    static final double CONSUMO_ALERTA = 0.85, CONSUMO_CORTE = 0.97, MARGEN_CAMBIO = 0.12, ALFA = 0.3;
    static final double MIN_ENTRE_CAMBIOS_S = 600, RECIENTE_S = 300;

    private CerebroVpn() {}

    /** Lo que la IA mira. null = no se sabe (se decide con prudencia). */
    public static final class Senales {
        public boolean servidorConfigurado;
        public Boolean servidorEnLinea;
        public Integer latenciaServidorMs;
        public Double consumoFraccion;
        public boolean torDisponible = true;
        public Boolean redInsegura;
        public boolean tunelDisponible = true;
    }

    public static final class Decision {
        public final String modo;
        public final Map<String, Double> puntajes;
        public final List<String> razones;
        public final double confianza;
        public final Map<String, String> descartados;

        Decision(String modo, Map<String, Double> puntajes, List<String> razones, double confianza,
                 Map<String, String> descartados) {
            this.modo = modo; this.puntajes = puntajes; this.razones = razones; this.confianza = confianza;
            this.descartados = descartados;
        }
    }

    public static boolean usaServidor(String m) { return SERVIDOR.equals(m) || SERVIDOR_TOR.equals(m); }
    public static boolean usaTor(String m) { return TOR.equals(m) || SERVIDOR_TOR.equals(m); }
    static boolean comparten(String a, String b) {
        return (usaServidor(a) && usaServidor(b)) || (usaTor(a) && usaTor(b));
    }

    static double base(String objetivo, String modo) {
        switch (objetivo) {
            case VELOCIDAD: return SERVIDOR.equals(modo) ? 0.92 : TOR.equals(modo) ? 0.30 : 0.35;
            case ANONIMATO: return SERVIDOR.equals(modo) ? 0.42 : TOR.equals(modo) ? 0.80 : 0.95;
            default: return SERVIDOR.equals(modo) ? 0.78 : TOR.equals(modo) ? 0.52 : 0.70;
        }
    }

    // ─────────────────────────── memoria (aprendizaje en línea)
    public static JSONObject memoriaVacia() {
        JSONObject m = new JSONObject();
        try {
            for (String c : CANDIDATOS) {
                m.put(c, new JSONObject().put("intentos", 0).put("fiabilidad", 0.8).put("segundos", JSONObject.NULL)
                        .put("ultimo_fallo", 0.0));
            }
        } catch (Exception ignored) {}
        return m;
    }

    /** Actualiza la memoria con el resultado de una conexión real. {@code ahora} en segundos. */
    public static JSONObject aprender(JSONObject memoria, String modo, boolean exito, Double segundos, double ahora) {
        boolean valido = false;
        for (String c : CANDIDATOS) valido |= c.equals(modo);
        if (!valido) return memoria;
        try {
            JSONObject m = memoria.optJSONObject(modo);
            if (m == null) { m = memoriaVacia().getJSONObject(modo); memoria.put(modo, m); }
            m.put("intentos", m.optInt("intentos", 0) + 1);
            m.put("fiabilidad", (1 - ALFA) * m.optDouble("fiabilidad", 0.8) + ALFA * (exito ? 1.0 : 0.0));
            if (exito && segundos != null) {
                m.put("segundos", m.isNull("segundos") || !m.has("segundos") ? segundos
                        : (1 - ALFA) * m.optDouble("segundos") + ALFA * segundos);
            }
            if (!exito) m.put("ultimo_fallo", ahora);
        } catch (Exception ignored) {}
        return memoria;
    }

    // ─────────────────────────── decisión
    public static Decision decidir(Senales s, String objetivo, JSONObject memoria, double ahora, String... excluir) {
        if (!EQUILIBRIO.equals(objetivo) && !VELOCIDAD.equals(objetivo) && !ANONIMATO.equals(objetivo)) objetivo = EQUILIBRIO;
        if (memoria == null) memoria = memoriaVacia();
        Map<String, Double> puntajes = new LinkedHashMap<>();
        Map<String, String> descartados = new LinkedHashMap<>();
        Map<String, List<String>> notas = new LinkedHashMap<>();
        List<String> recientes = new ArrayList<>();
        for (String m : CANDIDATOS) {
            notas.put(m, new ArrayList<>());
            JSONObject mm = memoria.optJSONObject(m);
            if (ahora - (mm == null ? 0.0 : mm.optDouble("ultimo_fallo", 0.0)) < RECIENTE_S) recientes.add(m);
        }
        List<String> excluidos = java.util.Arrays.asList(excluir == null ? new String[0] : excluir);

        for (String modo : CANDIDATOS) {
            if (excluidos.contains(modo)) { descartados.put(modo, "acaba de fallar"); continue; }
            if (usaServidor(modo)) {
                if (!s.servidorConfigurado) { descartados.put(modo, "tu servidor GuardianIA VPN no está en este teléfono"); continue; }
                if (!s.tunelDisponible) { descartados.put(modo, "falta el programa que crea el túnel"); continue; }
                if (Boolean.FALSE.equals(s.servidorEnLinea)) { descartados.put(modo, "tu servidor no responde (¿máquina apagada o firewall?)"); continue; }
                if (s.consumoFraccion != null && s.consumoFraccion >= CONSUMO_CORTE) { descartados.put(modo, "tu servidor llegó al límite gratis del mes"); continue; }
            }
            if (usaTor(modo) && !s.torDisponible) { descartados.put(modo, "Tor no está disponible"); continue; }

            double p = base(objetivo, modo);
            if (usaServidor(modo)) {
                if (s.latenciaServidorMs != null && s.latenciaServidorMs > LATENCIA_ALTA_MS) {
                    p -= SERVIDOR.equals(modo) ? 0.12 : 0.06;
                    notas.get(modo).add("tu servidor responde lento (" + s.latenciaServidorMs + " ms)");
                }
                if (s.consumoFraccion != null && s.consumoFraccion >= CONSUMO_ALERTA) {
                    p -= 0.30;
                    notas.get(modo).add("ya usaste el " + Math.round(s.consumoFraccion * 100) + " % del cupo gratis del mes");
                }
                if (s.servidorEnLinea == null) p -= 0.05;
                if (Boolean.TRUE.equals(s.redInsegura)) {
                    p += 0.10;
                    notas.get(modo).add("red WiFi pública: conviene cifrar todo el teléfono");
                }
            } else if (Boolean.TRUE.equals(s.redInsegura)) {
                p -= 0.08;
            }
            JSONObject mem = memoria.optJSONObject(modo);
            if (mem != null && mem.optInt("intentos", 0) >= 2) {
                p += 0.25 * (mem.optDouble("fiabilidad", 0.8) - 0.8);
                if (!mem.isNull("segundos") && mem.has("segundos") && mem.optDouble("segundos") > 60 && usaTor(modo)) p -= 0.05;
            }
            boolean falloReciente = false;
            for (String r : recientes) falloReciente |= comparten(modo, r);
            if (falloReciente) {
                p -= 0.30;
                notas.get(modo).add("hubo un fallo con esta vía hace menos de 5 minutos");
            }
            puntajes.put(modo, p);
        }

        if (puntajes.isEmpty()) {
            List<String> r = new ArrayList<>();
            r.add("Ningún modo es posible ahora: " + String.join("; ", descartados.values()));
            return new Decision(null, puntajes, r, 0.0, descartados);
        }
        List<String> orden = new ArrayList<>(puntajes.keySet());
        orden.sort((a, b) -> Double.compare(puntajes.get(b), puntajes.get(a)));   // estable: empates en orden fijo
        String mejor = orden.get(0);
        double segundo = orden.size() > 1 ? puntajes.get(orden.get(1)) : puntajes.get(mejor) - 0.5;
        double confianza = 1 / (1 + Math.exp(-8 * (puntajes.get(mejor) - segundo)));
        return new Decision(mejor, puntajes, explicar(mejor, objetivo, notas.get(mejor), descartados), confianza, descartados);
    }

    static List<String> explicar(String modo, String objetivo, List<String> notas, Map<String, String> descartados) {
        List<String> r = new ArrayList<>();
        r.add(SERVIDOR.equals(modo) ? "Tu servidor GuardianIA VPN: rápido y cifra todo el teléfono."
                : TOR.equals(modo) ? "Tor: tres relevos; nadie puede unir quién eres con lo que visitas."
                : "Servidor + Tor: tu operador solo ve tu servidor y el sitio solo ve un relevo Tor.");
        r.add(VELOCIDAD.equals(objetivo) ? "Prioriza la velocidad." : ANONIMATO.equals(objetivo) ? "Prioriza el anonimato."
                : "Buen equilibrio entre privacidad y velocidad.");
        for (int i = 0; i < Math.min(2, notas.size()); i++) r.add(notas.get(i));
        for (Map.Entry<String, String> d : descartados.entrySet()) {
            r.add("Descarté " + nombre(d.getKey()) + ": " + d.getValue() + ".");
            break;
        }
        return r;
    }

    public static String nombre(String modo) {
        if (modo == null) return "—";
        switch (modo) {
            case SERVIDOR: return "tu servidor";
            case TOR: return "Tor";
            case SERVIDOR_TOR: return "servidor + Tor";
            case "auto": return "automático (IA)";
            case "apagado": return "apagado";
            default: return modo;
        }
    }

    /** Histéresis: caído → cambiar ya; funcionando → solo si el nuevo gana con margen y pasó el tiempo mínimo. */
    public static boolean convieneCambiar(String actual, Decision d, double ultimoCambio, boolean actualSano, double ahora) {
        if (d.modo == null || d.modo.equals(actual)) return false;
        if (!actualSano || actual == null || !d.puntajes.containsKey(actual)) return true;
        return d.puntajes.get(d.modo) - d.puntajes.get(actual) >= MARGEN_CAMBIO && ahora - ultimoCambio >= MIN_ENTRE_CAMBIOS_S;
    }
}
