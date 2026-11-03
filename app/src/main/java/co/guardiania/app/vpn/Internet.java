package co.guardiania.app.vpn;

import org.json.JSONObject;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Lo que la VPN necesita de internet (no del PC): registrar Cloudflare WARP y comprobar la IP pública
 * con /cdn-cgi/trace. Mismas URLs, cabeceras y reglas que app/local/vpn.py.
 */
public final class Internet {

    static final String WARP_API = "https://api.cloudflareclient.com/v0a2158/reg";
    static final String WARP_PEER = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";
    static final String WARP_ENDPOINT = "engage.cloudflareclient.com:2408";
    static final String TRACE = "https://www.cloudflare.com/cdn-cgi/trace";

    private Internet() {}

    /** IP pública y país vistos desde internet; Cloudflare además dice si va por WARP. */
    public static final class Traza {
        public final String ip, pais, warp, colo;

        Traza(String ip, String pais, String warp, String colo) {
            this.ip = ip; this.pais = pais; this.warp = warp; this.colo = colo;
        }

        public boolean porWarp() { return "on".equals(warp) || "plus".equals(warp); }
    }

    public static Traza traza(int timeoutMs) {
        try {
            String t = pedir("GET", TRACE, null, null, timeoutMs);
            Map<String, String> d = new HashMap<>();
            for (String linea : t.split("\n")) {
                int i = linea.indexOf('=');
                if (i > 0) d.put(linea.substring(0, i), linea.substring(i + 1).trim());
            }
            return new Traza(d.get("ip"), d.get("loc"), d.get("warp"), d.get("colo"));
        } catch (IOException ignored) {}
        try {
            return new Traza(pedir("GET", "https://api.ipify.org", null, null, timeoutMs).trim(), null, null, null);
        } catch (IOException e) {
            return new Traza(null, null, null, null);
        }
    }

    /** IPv6 pública vista desde internet, o null si no hay salida IPv6 (lo normal con el túnel endurecido). */
    public static String ipv6Publica() {
        try {
            String ip = pedir("GET", "https://api6.ipify.org", null, null, 4000).trim();
            return ip.contains(":") ? ip : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * IP del resolutor DNS que atiende al teléfono (whoami.akamai.net responde con esa IP). Si con la
     * VPN sigue siendo el mismo que sin ella, las consultas de nombres no van por el túnel.
     */
    public static String resolutorDns() {
        try {
            return java.net.InetAddress.getByName("whoami.akamai.net").getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /** Compara antes/después de conectar. null = no se pudo comprobar (igual que fugas() en el PC). */
    public static Boolean[] fugas(String ip6Antes, String dnsAntes, String ip6Despues, String dnsDespues) {
        Boolean ip6 = null;
        if (ip6Despues != null && ip6Antes != null) ip6 = ip6Despues.equals(ip6Antes);
        Boolean dns = null;
        if (dnsDespues != null && dnsAntes != null) dns = dnsDespues.equals(dnsAntes);
        return new Boolean[]{ip6, dns};
    }

    /** Latencia hasta Cloudflare (a través del túnel si está arriba). null si no respondió. */
    public static Long latenciaMs() {
        long t0 = System.nanoTime();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(TRACE).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.getResponseCode();
            c.disconnect();
            return (System.nanoTime() - t0) / 1_000_000;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Registra una cuenta anónima de WARP con la clave pública del teléfono (lo mismo que hace wgcf y
     * configurar_warp() en Python) y devuelve el perfil listo. La clave privada no viaja.
     */
    public static JSONObject registrarWarp(String publica) throws IOException {
        JSONObject cuerpo = new JSONObject();
        JSONObject datos;
        try {
            cuerpo.put("key", publica);
            cuerpo.put("install_id", "");
            cuerpo.put("fcm_token", "");
            cuerpo.put("model", "Android");
            cuerpo.put("serial_number", "");
            cuerpo.put("locale", "en_US");            // sin delatar idioma ni país a Cloudflare
            cuerpo.put("tos", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
            Log.e("GuardianWARP", "clave publica enviada (" + publica.length() + " chars): [" + publica + "]");
            Log.e("GuardianWARP", "cuerpo enviado: " + cuerpo.toString());
            String cuerpoTexto = cuerpo.toString().replace("\\/", "/");
            datos = new JSONObject(pedir("POST", WARP_API, cuerpoTexto, null, 20000));
        } catch (IOException e) {
            Log.e("GuardianWARP", "registrarWarp/pedir: " + e.getClass().getName() + ": " + e.getMessage(), e);
            throw new co.guardiania.app.net.ConexionPc.Problema("Cloudflare WARP no respondió. Revisa tu conexión e inténtalo de nuevo.", e);
        } catch (Exception e) {
            throw new co.guardiania.app.net.ConexionPc.Problema("La respuesta de Cloudflare no se pudo leer.", e);
        }
        JSONObject res = datos.optJSONObject("result") != null ? datos.optJSONObject("result") : datos;
        try {
            JSONObject cfg = res.getJSONObject("config");
            JSONObject par = cfg.getJSONArray("peers").getJSONObject(0);
            JSONObject dir = cfg.getJSONObject("interface").getJSONObject("addresses");
            String v4 = dir.getString("v4");
            String v6 = dir.optString("v6", "");
            // Activar WARP en la cuenta recién creada (algunas versiones de la API lo exigen).
            try {
                JSONObject activar = new JSONObject().put("warp_enabled", true);
                pedir("PATCH", WARP_API + "/" + res.getString("id"), activar.toString(), res.getString("token"), 20000);
            } catch (Exception ignored) {}

            JSONObject interfaz = new JSONObject();
            interfaz.put("Address", v4 + "/32" + (v6.isEmpty() ? "" : ", " + v6 + "/128"));
            interfaz.put("DNS", "1.1.1.1, 1.0.0.1, 2606:4700:4700::1111");
            interfaz.put("MTU", "1280");
            JSONObject peer = new JSONObject();
            String pk = par.optString("public_key", "");
            JSONObject ep = par.optJSONObject("endpoint");
            String host = ep != null ? ep.optString("host", "") : "";
            peer.put("PublicKey", pk.isEmpty() ? WARP_PEER : pk);
            peer.put("Endpoint", host.isEmpty() ? WARP_ENDPOINT : host);
            peer.put("AllowedIPs", "0.0.0.0/0, ::/0");
            peer.put("PersistentKeepalive", "25");
            JSONObject perfil = new JSONObject();
            perfil.put("proveedor", "warp");
            perfil.put("nombre", "Cloudflare WARP");
            perfil.put("creado", System.currentTimeMillis() / 1000.0);
            perfil.put("clave_publica_equipo", publica);
            perfil.put("privada_propia", true);
            perfil.put("interfaz", interfaz);
            perfil.put("peer", peer);
            return perfil;
        } catch (Exception e) {
            throw new co.guardiania.app.net.ConexionPc.Problema("La respuesta de Cloudflare cambió de formato; inténtalo de nuevo en unos minutos.", e);
        }
    }

    static String pedir(String metodo, String url, String json, String bearer, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            try {
                c.setRequestMethod(metodo);          // Android admite PATCH en HttpURLConnection
            } catch (java.net.ProtocolException e) {
                c.setRequestMethod("POST");          // respaldo (JVM de escritorio)
                c.setRequestProperty("X-HTTP-Method-Override", metodo);
            }
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "okhttp/3.12.1");
            c.setRequestProperty("CF-Client-Version", "a-6.30-3596");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (is != null) try (is) { is.transferTo(bos); }
            if (code >= 400) throw new IOException(url + " respondió " + code + ": " + bos.toString(StandardCharsets.UTF_8.name()));
            return bos.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
