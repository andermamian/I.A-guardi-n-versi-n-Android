package co.guardiania.app.vpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.Build;

/**
 * ¿Está el teléfono en una WiFi ABIERTA (sin contraseña: café, aeropuerto)? En esas redes cualquiera
 * cerca puede ver el tráfico sin cifrar, así que la IA prefiere un túnel que cifre TODO el teléfono.
 * Solo Android 12+ expone el tipo de seguridad sin permisos de ubicación; antes devuelve null.
 */
final class RedInsegura {

    private RedInsegura() {}

    static Boolean detectar(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) return null;
            if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false;   // datos móviles
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
            if (!(nc.getTransportInfo() instanceof WifiInfo)) return null;
            int tipo = ((WifiInfo) nc.getTransportInfo()).getCurrentSecurityType();
            if (tipo == WifiInfo.SECURITY_TYPE_UNKNOWN) return null;
            return tipo == WifiInfo.SECURITY_TYPE_OPEN || tipo == WifiInfo.SECURITY_TYPE_WEP;
        } catch (Exception e) {
            return null;
        }
    }
}
