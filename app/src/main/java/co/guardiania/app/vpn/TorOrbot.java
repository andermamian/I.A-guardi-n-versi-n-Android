package co.guardiania.app.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Modo cebolla (Tor) en Android, gratis, con <b>Orbot</b>: la app oficial del Tor Project.
 *
 * <p>No se reimplementa Tor (un Tor casero sería inseguro). GuardianIA le pide a Orbot que arranque
 * con los mensajes oficiales (los mismos de la biblioteca NetCipher del Guardian Project) y verifica la
 * salida con el Tor Project. Con el «modo VPN» de Orbot, TODAS las apps del teléfono pasan por Tor.
 *
 * <p>Android permite una sola VPN a la vez: si la VPN WireGuard de GuardianIA está conectada, hay que
 * desconectarla para usar el modo VPN de Orbot.
 */
public final class TorOrbot {

    public static final String PAQUETE = "org.torproject.android";
    static final String ACCION_INICIAR = "org.torproject.android.intent.action.START";
    static final String ACCION_ESTADO = "org.torproject.android.intent.action.STATUS";
    static final String EXTRA_PAQUETE = "org.torproject.android.intent.extra.PACKAGE_NAME";
    static final String EXTRA_ESTADO = "org.torproject.android.intent.extra.STATUS";
    static final String EXTRA_PUERTO_SOCKS = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT";
    public static final int PUERTO_SOCKS = 9050;

    public interface Oyente { void estado(String estado, int puertoSocks); }

    private TorOrbot() {}

    public static boolean instalado(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PAQUETE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Abre la tienda para instalar Orbot (gratis). */
    public static Intent instalar() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PAQUETE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public static Intent instalarWeb() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + PAQUETE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Abre Orbot para activar su «modo VPN» (todas las apps por Tor). */
    public static Intent abrir(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(PAQUETE);
        return i != null ? i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : instalar();
    }

    /**
     * Le pide a Orbot que arranque Tor y escucha su estado (ON, STARTING, OFF…). Devuelve el receptor
     * para dejar de escuchar con {@link #dejarDeEscuchar}.
     */
    public static BroadcastReceiver iniciar(Context c, Oyente o) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                if (!ACCION_ESTADO.equals(i.getAction())) return;
                o.estado(i.getStringExtra(EXTRA_ESTADO), i.getIntExtra(EXTRA_PUERTO_SOCKS, PUERTO_SOCKS));
            }
        };
        IntentFilter f = new IntentFilter(ACCION_ESTADO);
        // El estado lo envía OTRA app (Orbot): en Android 13+ el receptor debe declararse exportado.
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else c.registerReceiver(r, f);
        Intent pedir = new Intent(ACCION_INICIAR).setPackage(PAQUETE).putExtra(EXTRA_PAQUETE, c.getPackageName());
        c.sendBroadcast(pedir);
        return r;
    }

    public static void dejarDeEscuchar(Context c, BroadcastReceiver r) {
        if (r == null) return;
        try { c.unregisterReceiver(r); } catch (Exception ignored) {}
    }

    /** Resultado de preguntarle al Tor Project si la conexión sale por Tor. */
    public static final class Verificacion {
        public final boolean esTor;
        public final String ipSalida, via;
        public Verificacion(boolean esTor, String ipSalida, String via) { this.esTor = esTor; this.ipSalida = ipSalida; this.via = via; }
    }

    /**
     * Verifica con https://check.torproject.org/api/ip: primero directo (si el modo VPN de Orbot está
     * activo, todo el teléfono ya sale por Tor) y luego por el proxy SOCKS de Orbot.
     * Bloquea: llamar en segundo plano.
     */
    public static Verificacion verificar(int puertoSocks) {
        Verificacion directa = consultar(Proxy.NO_PROXY, "todo el teléfono (modo VPN de Orbot)");
        if (directa != null && directa.esTor) return directa;
        Verificacion socks = consultar(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", puertoSocks)),
                "proxy SOCKS de Orbot (127.0.0.1:" + puertoSocks + ")");
        if (socks != null) return socks;
        return directa != null ? directa : new Verificacion(false, null, "sin conexión");
    }

    /** Verifica por el proxy HTTP del Tor del PC (sin Orbot). Bloquea: llamar en segundo plano. */
    public static Verificacion verificarProxyPc(String host, int puerto) {
        Verificacion v = consultar(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, puerto)),
                "el Tor de tu PC (" + host + ":" + puerto + ")");
        return v != null ? v : new Verificacion(false, null, "sin respuesta del proxy del PC");
    }

    /** ¿El WiFi ya tiene configurado como proxy el Tor del PC? (Android lo expone como proxy por defecto). */
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
