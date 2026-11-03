package co.guardiania.app.vpn;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.CopyOnWriteArrayList;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.service.ServicioTor;

/**
 * Tor INTEGRADO en GuardianIA: sin Orbot y sin el PC. Usa la biblioteca oficial «tor-android» del
 * Guardian Project (la misma que usa Orbot por dentro): el motor real de Tor, con sus 3 saltos.
 *
 * <p>Deja dos puertos SOLO en el teléfono (127.0.0.1):
 * <ul>
 *   <li>SOCKS 9050 — para GuardianIA y las apps que acepten SOCKS.</li>
 *   <li>HTTP 8118 (CONNECT) — para Chrome y la mayoría de apps: WiFi → Proxy → Manual → 127.0.0.1 : 8118.</li>
 * </ul>
 * El DNS lo resuelve el relevo de salida de Tor (sin DoH externo: evita fugas y correlación).
 * {@link ServicioTor} lo mantiene vivo aunque la persona salga de GuardianIA.
 */
public final class TorInterno {

    public interface Oyente { void cambio(String estado, int socks, int http); }

    private static volatile String estado = "OFF";
    private static volatile int puertoSocks = 0, puertoHttp = 0;
    private static final CopyOnWriteArrayList<Oyente> oyentes = new CopyOnWriteArrayList<>();

    private TorInterno() {}

    public static String estado() { return estado; }
    public static int puertoSocks() { return puertoSocks; }
    public static int puertoHttp() { return puertoHttp; }
    public static boolean activo() { return "ON".equals(estado); }

    public static void observar(Oyente o) { if (o != null && !oyentes.contains(o)) oyentes.add(o); }
    public static void dejarDeObservar(Oyente o) { oyentes.remove(o); }

    /** Lo llama {@link ServicioTor} con cada cambio que anuncia TorService. */
    public static void actualizar(String nuevo, int socks, int http) {
        estado = nuevo == null ? "OFF" : nuevo;
        puertoSocks = socks;
        puertoHttp = http;
        Hilos.ui(() -> { for (Oyente o : oyentes) o.cambio(estado, puertoSocks, puertoHttp); });
    }

    // ─────────────────────────── país de salida
    /** País elegido (lista cerrada) o null = el más rápido. */
    public static String pais(Context c) {
        String p = c.getSharedPreferences("guardian_tor", Context.MODE_PRIVATE).getString("pais", null);
        return p != null && java.util.Arrays.asList(Ubicaciones.PAISES_TOR).contains(p) ? p : null;
    }

    /**
     * Elige el país de salida. Se escribe en el torrc ANTES de arrancar Tor (ExitNodes + StrictNodes: si
     * no hay relevos de ese país, no sale por otro). Solo códigos de la lista cerrada: nada de texto libre
     * que pudiera inyectar opciones en la configuración de Tor. Si Tor corre, se reinicia.
     */
    public static void fijarPais(Context c, String pais) {
        if (pais != null && !java.util.Arrays.asList(Ubicaciones.PAISES_TOR).contains(pais)) {
            throw new IllegalArgumentException("País no disponible");
        }
        c.getSharedPreferences("guardian_tor", Context.MODE_PRIVATE).edit().putString("pais", pais).apply();
        escribirTorrc(c);
        if (activo() || "STARTING".equals(estado)) {
            detener(c);
            Hilos.uiLuego(() -> iniciar(c), 1500);
        }
    }

    static void escribirTorrc(Context c) {
        try {
            java.io.File torrc = org.torproject.jni.TorService.getTorrc(c);
            if (torrc == null) return;
            java.io.File dir = torrc.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return;
            String p = pais(c);
            StringBuilder texto = new StringBuilder();
            if (p != null) texto.append("ExitNodes {").append(p.toLowerCase(java.util.Locale.ROOT)).append("}\nStrictNodes 1\n");
            texto.append(textoTorrcPuentes(c));
            try (java.io.FileWriter w = new java.io.FileWriter(torrc, false)) { w.write(texto.toString()); }
        } catch (Exception ignored) {
            // Sin torrc, Tor arranca igual y sale por el país más rápido.
        }
    }

    // ─────────────────────────── puentes (bridges): para redes que bloquean Tor
    private static final int MAX_PUENTES = 6;

    /** Puentes guardados en este teléfono (vacío = ninguno, Tor sale directo a la red pública). */
    public static java.util.List<String> puentes(Context c) {
        String guardado = c.getSharedPreferences("guardian_tor", Context.MODE_PRIVATE).getString("puentes", "");
        java.util.List<String> lista = new java.util.ArrayList<>();
        if (guardado != null) {
            for (String linea : guardado.split("\n")) {
                String l = linea.trim();
                if (!l.isEmpty()) lista.add(l);
            }
        }
        return lista;
    }

    /**
     * Guarda las líneas de puente (tal cual las entrega bridges.torproject.org, obfs4 o sin
     * transporte) y reinicia el Tor integrado si estaba encendido. Lista vacía = quitar los puentes.
     * Cada persona pide los suyos: un puente igual para todos los que instalen GuardianIA se
     * bloquearía apenas alguien lo reportara.
     */
    public static void fijarPuentes(Context c, java.util.List<String> lineas) {
        if (lineas.size() > MAX_PUENTES) throw new IllegalArgumentException("Como mucho " + MAX_PUENTES + " puentes.");
        StringBuilder limpio = new StringBuilder();
        for (String l : lineas) {
            String linea = l == null ? "" : l.trim();
            if (linea.isEmpty()) continue;
            if (linea.length() > 300 || linea.indexOf('\n') >= 0 || linea.indexOf('"') >= 0) {
                throw new IllegalArgumentException("Esa línea de puente no tiene el formato esperado.");
            }
            if (limpio.length() > 0) limpio.append('\n');
            limpio.append(linea);
        }
        c.getSharedPreferences("guardian_tor", Context.MODE_PRIVATE).edit().putString("puentes", limpio.toString()).apply();
        escribirTorrc(c);
        if (activo() || "STARTING".equals(estado)) {
            detener(c);
            Hilos.uiLuego(() -> iniciar(c), 1500);
        }
    }

    private static String textoTorrcPuentes(Context c) {
        java.util.List<String> lineas = puentes(c);
        if (lineas.isEmpty()) return "";
        // La biblioteca tor-android trae su propio obfs4proxy e inyecta sola el ClientTransportPlugin
        // cuando ve líneas «Bridge obfs4 …» en el torrc (igual que hace Orbot, que usa la misma
        // biblioteca por dentro): no hay que apuntar a ningún binario a mano como en el PC.
        StringBuilder t = new StringBuilder("UseBridges 1\n");
        for (String l : lineas) t.append("Bridge ").append(l).append('\n');
        return t.toString();
    }

    /** País que ven los sitios al salir por el Tor integrado (Cloudflare «loc=»). Bloquea. */
    public static String paisReal() {
        if (puertoSocks <= 0) return null;
        java.net.HttpURLConnection con = null;
        try {
            con = (java.net.HttpURLConnection) new java.net.URL("https://www.cloudflare.com/cdn-cgi/trace")
                    .openConnection(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", puertoSocks)));
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(con.getInputStream()))) {
                for (String l; (l = r.readLine()) != null; ) if (l.startsWith("loc=")) return l.substring(4).trim();
            }
        } catch (Exception ignored) {
        } finally {
            if (con != null) con.disconnect();
        }
        return null;
    }

    public static void iniciar(Context c) {
        escribirTorrc(c);
        Intent i = new Intent(c, ServicioTor.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void detener(Context c) {
        c.stopService(new Intent(c, ServicioTor.class));
    }

    /** Pregunta al Tor Project, a través del Tor integrado, si la salida es Tor. Bloquea. */
    public static TorVerificacion.Verificacion verificar() {
        if (puertoSocks <= 0) return new TorVerificacion.Verificacion(false, null, "Tor integrado apagado");
        TorVerificacion.Verificacion v = TorVerificacion.consultar(new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress("127.0.0.1", puertoSocks)), "Tor integrado en GuardianIA");
        return v != null ? v : new TorVerificacion.Verificacion(false, null, "sin respuesta de la red Tor");
    }
}
