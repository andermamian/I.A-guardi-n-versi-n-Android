package co.guardiania.app.vpn;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Verificación de Tor con el propio Tor Project (https://check.torproject.org/api/ip) y detección de si
 * el WiFi ya usa el proxy local. (Antes esta clase también integraba Orbot; se retiró: GuardianIA trae
 * su propio Tor y así no hay dos Tor ni dos VPN compitiendo.)
 */
public final class TorVerificacion {

    private TorVerificacion() {}

    /** Resultado de preguntarle al Tor Project si la conexión sale por Tor. */
    public static final class Verificacion {
        public final boolean esTor;
        public final String ipSalida, via;
        public Verificacion(boolean esTor, String ipSalida, String via) { this.esTor = esTor; this.ipSalida = ipSalida; this.via = via; }
    }

    /** ¿El WiFi ya tiene configurado como proxy el Tor de GuardianIA? (Android lo expone como proxy por defecto). */
    public static boolean wifiUsaProxy(Context c, String host, int puerto) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.ProxyInfo pi = cm != null ? cm.getDefaultProxy() : null;
            return pi != null && host.equals(pi.getHost()) && pi.getPort() == puerto;
        } catch (Exception e) {
            return false;
        }
    }

    static Verificacion consultar(Proxy proxy, String via) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://check.torproject.org/api/ip").openConnection(proxy);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = c.getInputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = is.read(buf)) != -1 && bos.size() < 4096) bos.write(buf, 0, n);
            }
            JSONObject d = new JSONObject(bos.toString(StandardCharsets.UTF_8.name()));
            return new Verificacion(d.optBoolean("IsTor"), d.optString("IP", null), via);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
