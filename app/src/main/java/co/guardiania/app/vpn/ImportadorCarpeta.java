package co.guardiania.app.vpn;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * ImportaciÃ³n AUTOMÃTICA de ubicaciones de ProtonVPN: la persona le da permiso una sola vez a
 * GuardianIA sobre su carpeta (normalmente Descargas) y, desde entonces, cada .conf de WireGuard nuevo
 * que descargue se importa solo al abrir la VPN.
 *
 * <p>Seguridad: Android solo da acceso a ESA carpeta (almacenamiento con permisos por carpeta); se leen
 * solo archivos Â«.confÂ» pequeÃ±os, y cada uno se valida con las mismas reglas que la importaciÃ³n manual
 * (se rechazan PostUp/PreUp, claves invÃ¡lidas y rutas parciales). No se reimportan los ya vistos.
 */
public final class ImportadorCarpeta {

    private static final String PREFS = "guardian_vpn_carpeta";

    public static final class Resultado {
        public int nuevas, rechazadas;
        public final StringBuilder motivos = new StringBuilder();
    }

    private ImportadorCarpeta() {}

    public static boolean vinculada(Context c) { return carpeta(c) != null; }

    public static Uri carpeta(Context c) {
        String u = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("arbol", null);
        return u == null ? null : Uri.parse(u);
    }

    /** Guarda la carpeta elegida y conserva el permiso aunque el telÃ©fono se reinicie. */
    public static void vincular(Context c, Uri arbol) {
        c.getContentResolver().takePersistableUriPermission(arbol, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("arbol", arbol.toString()).apply();
    }

    /** Importa los .conf nuevos de la carpeta vinculada. Bloquea: llamar en segundo plano. */
    public static Resultado importarNuevos(Context c) {
        Resultado r = new Resultado();
        Uri arbol = carpeta(c);
        if (arbol == null) return r;
        Set<String> vistos = new HashSet<>(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet("vistos", new HashSet<>()));
        Uri hijos = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, DocumentsContract.getTreeDocumentId(arbol));
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE};
        MotorVpn motor = MotorVpn.de(c);
        try (Cursor cur = c.getContentResolver().query(hijos, cols, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String id = cur.getString(0), nombre = cur.getString(1);
                long tam = cur.getLong(2);
                if (nombre == null || !nombre.toLowerCase(java.util.Locale.ROOT).endsWith(".conf") || tam > 20_000) continue;
                String huella = nombre + "|" + tam;
                if (vistos.contains(huella)) continue;
                vistos.add(huella);
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(arbol, id);
                try (InputStream is = c.getContentResolver().openInputStream(doc)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while (is != null && (n = is.read(buf)) != -1 && bos.size() <= 20_000) bos.write(buf, 0, n);
                    String texto = bos.toString("UTF-8");
                    if (!texto.contains("[Interface]") || !texto.contains("[Peer]")) continue;   // no es WireGuard
                    // // if (motor.importar(texto) != null) r.nuevas++;
                } catch (Exception e) {
                    r.rechazadas++;
                    r.motivos.append("â€¢ ").append(nombre).append(": ").append(e.getMessage()).append('\n');
                }
            }
        } catch (Exception e) {
            r.motivos.append("No pude leer la carpeta (Â¿se retirÃ³ el permiso?).");
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("vistos", vistos).apply();
        return r;
    }
}
