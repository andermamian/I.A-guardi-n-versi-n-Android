package co.guardiania.app.seguridad;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Antivirus sin subir el archivo: igual que file-features.js de la web, el teléfono calcula la
 * huella SHA-256, la entropía, la cabecera y las cadenas legibles, y solo eso viaja al motor del
 * servidor (/v1/antivirus/scan). El contenido del archivo nunca sale del teléfono.
 *
 * <p>Límite honesto de Android: una app no puede escanear las demás apps ni el sistema (está
 * prohibido desde Android 10). Por eso se analizan los archivos que la persona elige —el APK que le
 * mandaron por WhatsApp, un PDF sospechoso— y, aparte, los PERMISOS de las apps instaladas.
 */
public final class AnalizadorArchivo {

    private static final int MAX_CADENAS = 400;
    private static final int MAX_LEER = 64 * 1024 * 1024;   // 64 MB: suficiente para un APK grande

    private AnalizadorArchivo() {}

    public static JSONObject rasgos(String nombre, InputStream entrada) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long[] frecuencia = new long[256];
        byte[] cabecera = new byte[64];
        int enCabecera = 0;
        long total = 0;
        Set<String> cadenas = new LinkedHashSet<>();
        StringBuilder actual = new StringBuilder();
        byte[] buf = new byte[65536];
        try (InputStream in = entrada) {
            int n;
            while ((n = in.read(buf)) != -1 && total < MAX_LEER) {
                sha.update(buf, 0, n);
                for (int i = 0; i < n; i++) {
                    int b = buf[i] & 0xFF;
                    frecuencia[b]++;
                    if (enCabecera < cabecera.length) cabecera[enCabecera++] = buf[i];
                    // Cadenas ASCII imprimibles de 6+ caracteres: URLs, comandos, nombres de API.
                    if (b >= 0x20 && b < 0x7F) {
                        if (actual.length() < 200) actual.append((char) b);
                    } else {
                        if (actual.length() >= 6 && cadenas.size() < MAX_CADENAS) cadenas.add(actual.toString());
                        actual.setLength(0);
                    }
                }
                total += n;
            }
        }
        if (actual.length() >= 6 && cadenas.size() < MAX_CADENAS) cadenas.add(actual.toString());

        double entropia = 0;
        for (long f : frecuencia) {
            if (f == 0) continue;
            double p = (double) f / total;
            entropia -= p * (Math.log(p) / Math.log(2));
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format("%02x", b));
        StringBuilder cab = new StringBuilder();
        for (int i = 0; i < enCabecera; i++) cab.append(String.format("%02x", cabecera[i]));

        JSONObject file = new JSONObject();
        file.put("name", nombre.length() > 260 ? nombre.substring(0, 260) : nombre);
        file.put("size", total);
        file.put("sha256", hex.toString());
        file.put("kind", tipo(nombre, cabecera, enCabecera));
        file.put("header_hex", cab.toString());
        file.put("entropy", Math.round(entropia * 1000) / 1000.0);
        JSONArray lista = new JSONArray();
        for (String s : cadenas) lista.put(s);
        file.put("strings", lista);
        return file;
    }

    static String tipo(String nombre, byte[] c, int n) {
        String ext = nombre.contains(".") ? nombre.substring(nombre.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (n >= 2 && c[0] == 'M' && c[1] == 'Z') return "pe";
        if (n >= 4 && c[0] == 0x7F && c[1] == 'E' && c[2] == 'L' && c[3] == 'F') return "elf";
        if (n >= 4 && c[0] == '%' && c[1] == 'P' && c[2] == 'D' && c[3] == 'F') return "pdf";
        if (n >= 2 && c[0] == 'P' && c[1] == 'K') {
            if (ext.equals("apk") || ext.equals("xapk")) return "apk";
            if (ext.matches("docx|xlsx|pptx|docm|xlsm|pptm")) return "office";
            return "apk".equals(ext) ? "apk" : "unknown";
        }
        if (ext.matches("doc|xls|ppt|docx|xlsx|pptx|docm|xlsm")) return "office";
        if (ext.matches("js|vbs|ps1|bat|cmd|sh|py|hta")) return "script";
        return "unknown";
    }
}
