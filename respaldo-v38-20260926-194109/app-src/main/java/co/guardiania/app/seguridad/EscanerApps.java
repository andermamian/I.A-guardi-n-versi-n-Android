package co.guardiania.app.seguridad;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.List;

/**
 * Vigilancia de aplicaciones (pantalla «Vigilancia de Aplicaciones» del proyecto original): reúne
 * de cada app instalada por el usuario sus permisos, quién la instaló (Play Store o un APK suelto),
 * su huella de firma y su targetSdk, y los envía a /v1/apps/scan. El motor app_risk del servidor
 * decide si es segura, sospechosa o peligrosa (p. ej. una linterna que lee SMS y no vino de la tienda).
 *
 * <p>Privacidad: se envían metadatos (nombre del paquete y permisos), nunca datos de las apps.
 * Las apps del sistema se omiten: no se pueden desinstalar y solo llenarían la lista de ruido.
 */
public final class EscanerApps {

    private EscanerApps() {}

    @SuppressWarnings("deprecation")
    public static JSONArray recolectar(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        int flags = PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                | (Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        // Primero la lista liviana y luego el detalle app por app: pedir todo de una vez con tantos
        // flags supera el límite de Binder (TransactionTooLargeException) en teléfonos con muchas apps.
        List<PackageInfo> paquetes = pm.getInstalledPackages(0);
        JSONArray apps = new JSONArray();
        for (PackageInfo liviano : paquetes) {
            PackageInfo p;
            try { p = pm.getPackageInfo(liviano.packageName, flags); } catch (Exception e) { continue; }
            ApplicationInfo ai = p.applicationInfo;
            if (ai == null) continue;
            boolean sistema = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (sistema || p.packageName.equals(ctx.getPackageName())) continue;
            JSONObject a = new JSONObject();
            a.put("package", p.packageName);
            String etiqueta = String.valueOf(pm.getApplicationLabel(ai));
            a.put("label", etiqueta.isEmpty() ? p.packageName : recortar(etiqueta, 120));
            if (p.versionName != null) a.put("version", recortar(p.versionName, 40));
            String instalador = instalador(pm, p.packageName);
            if (instalador != null) a.put("installer", recortar(instalador, 120));
            String cert = huellaFirma(p);
            if (cert != null) a.put("cert_sha256", cert);
            a.put("target_sdk", Math.min(40, Math.max(1, ai.targetSdkVersion)));
            if (Build.VERSION.SDK_INT >= 26) a.put("category", categoria(ai.category));
            JSONArray permisos = new JSONArray();
            java.util.Set<String> vistos = new java.util.LinkedHashSet<>();
            if (p.requestedPermissions != null) java.util.Collections.addAll(vistos, p.requestedPermissions);
            // Lo más peligroso NO aparece como permiso pedido: un servicio de ACCESIBILIDAD, un lector de
            // NOTIFICACIONES o un ADMINISTRADOR del dispositivo se declaran como componentes protegidos
            // por un permiso BIND_*. Es justo lo que usan los troyanos bancarios, así que se incluye.
            if (p.services != null) for (android.content.pm.ServiceInfo si : p.services) {
                if (si.permission != null && si.permission.startsWith("android.permission.BIND_")) vistos.add(si.permission);
            }
            if (p.receivers != null) for (android.content.pm.ActivityInfo ri : p.receivers) {
                if ("android.permission.BIND_DEVICE_ADMIN".equals(ri.permission)) vistos.add(ri.permission);
            }
            for (String perm : vistos) { if (permisos.length() >= 200) break; permisos.put(perm); }
            a.put("permissions", permisos);
            apps.put(a);
            if (apps.length() >= 500) break;   // límite del servidor por lote
        }
        return apps;
    }

    @SuppressWarnings("deprecation")
    private static String instalador(PackageManager pm, String paquete) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return pm.getInstallSourceInfo(paquete).getInstallingPackageName();
            return pm.getInstallerPackageName(paquete);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static String huellaFirma(PackageInfo p) {
        try {
            Signature[] firmas;
            if (Build.VERSION.SDK_INT >= 28) {
                if (p.signingInfo == null) return null;
                firmas = p.signingInfo.hasMultipleSigners() ? p.signingInfo.getApkContentsSigners()
                        : p.signingInfo.getSigningCertificateHistory();
            } else {
                firmas = p.signatures;
            }
            if (firmas == null || firmas.length == 0) return null;
            byte[] h = MessageDigest.getInstance("SHA-256").digest(firmas[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Categorías con los nombres que usa app_risk.py (EXPECTED_BY_CATEGORY), para no dar falsos positivos:
     *  que WhatsApp lea SMS o que Maps use la ubicación en segundo plano es lo esperado. */
    private static String categoria(int c) {
        switch (c) {
            case ApplicationInfo.CATEGORY_SOCIAL: return "messaging";
            case ApplicationInfo.CATEGORY_MAPS: return "navigation";
            case ApplicationInfo.CATEGORY_AUDIO: case ApplicationInfo.CATEGORY_VIDEO: case ApplicationInfo.CATEGORY_IMAGE: return "media";
            case 8 /* CATEGORY_ACCESSIBILITY, API 31 */: return "accessibility";
            case ApplicationInfo.CATEGORY_GAME: return "game";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "productivity";
            case ApplicationInfo.CATEGORY_NEWS: return "news";
            default: return "other";
        }
    }

    private static String recortar(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
}
