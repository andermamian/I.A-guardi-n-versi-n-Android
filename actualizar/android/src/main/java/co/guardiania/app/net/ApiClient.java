package co.guardiania.app.net;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import co.guardiania.app.BuildConfig;

/**
 * Cliente HTTP hacia el backend de GuardianIA, sin librerías externas (HttpsURLConnection).
 *
 * <p><b>Pinning de la CA local:</b> si la app trae la CA de GuardianIA (la que arma el PC), se confía
 * SOLO en ella: el certificado del PC es válido sin avisos, y cualquier otro servidor —aunque el
 * sistema confíe en él— es rechazado. Sin CA fijada se usan las CA del sistema (dominio público).
 *
 * <p>Novedades de la v2: todos los métodos HTTP que usa la app, subida de archivos (multipart),
 * lectura en streaming (SSE) para que el asistente escriba a medida que piensa, y descarga binaria.
 * El SSLContext se construye una vez por CA y se reutiliza (antes se rehacía en cada petición).
 */
public class ApiClient {

    private static final String TAG = "GuardianIA/Api";
    private static final int TIMEOUT_MS = 20000;
    /** Un modelo local en CPU puede tardar minutos en el primer trozo: el streaming espera más. */
    private static final int TIMEOUT_STREAM_MS = 300000;
    private static final String AGENTE = "GuardianIA-Android/" + BuildConfig.VERSION_NAME;

    private static String caCacheada;
    private static SSLContext sslCacheado;

    private final String base;              // ej. https://10.228.124.86:8443
    private final SSLContext sslContext;    // null = CA del sistema
    private final boolean caInvalida;

    public ApiClient(String base, String caPem) {
        this.base = base.replaceAll("/+$", "");
        this.sslContext = caPem != null && !caPem.isEmpty() ? contextoConCa(caPem) : null;
        // Falla cerrado: si se entregó una CA y no se pudo fijar, NO se cae a las CA del sistema.
        this.caInvalida = caPem != null && !caPem.isEmpty() && sslContext == null;
    }

    /** Dirección base (https://host:puerto) y contexto TLS con la CA fijada (null = CA del sistema). */
    public String base() { return base; }
    public SSLContext sslContext() { return sslContext; }
    public boolean caValida() { return !caInvalida; }

    /** Respuesta cruda: código HTTP y cuerpo. El llamador decide cómo interpretarla. */
    public static class Resp {
        public final int code;
        public final String body;
        public Resp(int code, String body) { this.code = code; this.body = body; }
        public boolean ok() { return code >= 200 && code < 300; }
        public JSONObject json() {
            try { return new JSONObject(body); } catch (Exception e) { return new JSONObject(); }
        }
    }

    /** Respuesta binaria (fotos, archivos creados por el asistente). */
    public static class Bytes {
        public final int code;
        public final byte[] datos;
        public final String tipo;
        public final String nombre;
        Bytes(int code, byte[] datos, String tipo, String nombre) {
            this.code = code; this.datos = datos; this.tipo = tipo; this.nombre = nombre;
        }
        public boolean ok() { return code >= 200 && code < 300; }
    }

    /** Recibe cada evento «data: {...}» del servidor a medida que llega. */
    public interface OyenteSse { void evento(JSONObject e); }

    // ─────────────────────────── JSON
    public Resp post(String ruta, JSONObject cuerpo) throws IOException { return pedir("POST", ruta, cuerpo, null); }
    public Resp post(String ruta, JSONObject cuerpo, String bearer) throws IOException { return pedir("POST", ruta, cuerpo, bearer); }
    public Resp get(String ruta) throws IOException { return pedir("GET", ruta, null, null); }
    public Resp get(String ruta, String bearer) throws IOException { return pedir("GET", ruta, null, bearer); }

    /** GET, POST, PUT o DELETE. (HttpURLConnection no soporta PATCH; la app no lo necesita.) */
    public Resp pedir(String metodo, String ruta, JSONObject cuerpo, String bearer) throws IOException {
        HttpURLConnection c = abrir(url(ruta), TIMEOUT_MS);
        c.setRequestMethod(metodo);
        cabeceras(c, bearer);
        c.setRequestProperty("Accept", "application/json, application/problem+json");
        if (cuerpo != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(cuerpo.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        return leer(c);
    }

    // ─────────────────────────── archivos
    /** Sube un archivo como multipart/form-data (el adjunto del asistente). */
    public Resp multipart(String ruta, Map<String, String> campos, String campoArchivo, String nombre,
                          String mime, byte[] datos, String bearer) throws IOException {
        String frontera = "----GuardianIA" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection c = abrir(url(ruta), 120000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        cabeceras(c, bearer);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + frontera);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(datos.length + 1024);
        if (campos != null) {
            for (Map.Entry<String, String> e : campos.entrySet()) {
                if (e.getValue() == null) continue;
                escribir(bos, "--" + frontera + "\r\nContent-Disposition: form-data; name=\"" + e.getKey()
                        + "\"\r\n\r\n" + e.getValue() + "\r\n");
            }
        }
        String seguro = nombre.replace("\"", "").replace("\r", "").replace("\n", "");
        escribir(bos, "--" + frontera + "\r\nContent-Disposition: form-data; name=\"" + campoArchivo
                + "\"; filename=\"" + seguro + "\"\r\nContent-Type: " + (mime != null ? mime : "application/octet-stream")
                + "\r\n\r\n");
        bos.write(datos);
        escribir(bos, "\r\n--" + frontera + "--\r\n");
        c.setFixedLengthStreamingMode(bos.size());
        try (OutputStream os = c.getOutputStream()) { bos.writeTo(os); }
        return leer(c);
    }

    /** Descarga binaria. Acepta rutas relativas a /v1 o absolutas del servidor (/v1/…). */
    public Bytes descargar(String ruta, String bearer) throws IOException {
        HttpURLConnection c = abrir(url(ruta), 120000);
        c.setRequestMethod("GET");
        cabeceras(c, bearer);
        int code = codigo(c);
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        byte[] datos = is != null ? consumirBytes(is) : new byte[0];
        String nombre = nombreDeDisposicion(c.getHeaderField("Content-Disposition"));
        String tipo = c.getContentType();
        c.disconnect();
        return new Bytes(code, datos, tipo, nombre);
    }

    // ─────────────────────────── streaming (Server-Sent Events)
    /**
     * POST (o GET si {@code cuerpo} es null, para reconectarse) que se lee a medida que llega: cada bloque «data: {...}» se entrega al oyente en cuanto
     * el servidor lo envía. Si {@code cancelar} pasa a true, se corta la conexión (botón Detener).
     * Devuelve la respuesta con cuerpo vacío si fue 2xx, o el error completo si no.
     */
    public Resp stream(String ruta, JSONObject cuerpo, String bearer, OyenteSse oyente, AtomicBoolean cancelar)
            throws IOException {
        HttpURLConnection c = abrir(url(ruta), TIMEOUT_STREAM_MS);
        cabeceras(c, bearer);
        c.setRequestProperty("Accept", "text/event-stream");
        if (cuerpo != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setChunkedStreamingMode(0);
            try (OutputStream os = c.getOutputStream()) {
                os.write(cuerpo.toString().getBytes(StandardCharsets.UTF_8));
            }
        } else {
            c.setRequestMethod("GET");            // reconexión a una respuesta en curso
        }
        int code = codigo(c);
        if (code < 200 || code >= 300) {
            InputStream err = c.getErrorStream();
            String texto = err != null ? new String(consumirBytes(err), StandardCharsets.UTF_8) : "";
            c.disconnect();
            return new Resp(code, texto);
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder datos = new StringBuilder();
            String linea;
            while ((linea = br.readLine()) != null) {
                if (cancelar != null && cancelar.get()) break;
                if (linea.isEmpty()) {                       // fin de un evento
                    if (datos.length() > 0) {
                        try { oyente.evento(new JSONObject(datos.toString())); }
                        catch (Exception e) { Log.w(TAG, "Evento SSE ilegible: " + e.getMessage()); }
                        datos.setLength(0);
                    }
                } else if (linea.startsWith("data:")) {
                    if (datos.length() > 0) datos.append('\n');
                    datos.append(linea.substring(5).trim());
                }
            }
        } finally {
            c.disconnect();
        }
        return new Resp(code, "");
    }

    // ─────────────────────────── internos
    private String url(String ruta) {
        if (ruta.startsWith("/v1/") || ruta.equals("/health") || ruta.equals("/ca.crt")) return base + ruta;
        return base + "/v1" + (ruta.startsWith("/") ? ruta : "/" + ruta);
    }

    private static void cabeceras(HttpURLConnection c, String bearer) {
        c.setRequestProperty("User-Agent", AGENTE);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
    }

    private HttpURLConnection abrir(String url, int timeoutLectura) throws IOException {
        if (caInvalida) throw new IOException("El certificado guardado del PC no es válido. Vuelve a vincular el teléfono.");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && sslContext != null) {
            ((HttpsURLConnection) c).setSSLSocketFactory(sslContext.getSocketFactory());
        }
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(timeoutLectura);
        c.setUseCaches(false);
        // No seguir redirecciones a ciegas: el servidor solo debería responder ya en https.
        c.setInstanceFollowRedirects(false);
        return c;
    }

    private static int codigo(HttpURLConnection c) throws IOException {
        try {
            return c.getResponseCode();
        } catch (javax.net.ssl.SSLHandshakeException e) {
            throw new IOException("El certificado del servidor no coincide con esta app. "
                    + "Si regeneraste la CA en el PC, vuelve a vincular el teléfono.", e);
        }
    }

    private Resp leer(HttpURLConnection c) throws IOException {
        int code = codigo(c);
        InputStream is = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
        String cuerpo = is != null ? new String(consumirBytes(is), StandardCharsets.UTF_8) : "";
        c.disconnect();
        return new Resp(code, cuerpo);
    }

    private static void escribir(ByteArrayOutputStream bos, String s) throws IOException {
        bos.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] consumirBytes(InputStream is) throws IOException {
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static String nombreDeDisposicion(String disp) {
        if (disp == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename\\*=UTF-8''([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(disp);
        try {
            if (m.find()) return java.net.URLDecoder.decode(m.group(1), "UTF-8");
            m = java.util.regex.Pattern.compile("filename=\"?([^\";]+)\"?").matcher(disp);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    /** SHA-256 (hex minúsculas) del certificado DER de un PEM: se compara con la huella del QR. */
    public static String huellaCa(String caPem) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate ca = cf.generateCertificate(new java.io.ByteArrayInputStream(caPem.getBytes(StandardCharsets.UTF_8)));
            byte[] h = MessageDigest.getInstance("SHA-256").digest(ca.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Huella en el formato que muestra la consola del PC: «AB:CD:…» (primeros 8 bytes). */
    public static String huellaCorta(String hex) {
        if (hex == null || hex.length() < 16) return "—";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(hex.substring(i, i + 2).toUpperCase());
        }
        return sb.toString();
    }

    /** SSLContext que confía únicamente en la CA PEM entregada (pinning). Se reutiliza por CA. */
    private static synchronized SSLContext contextoConCa(String caPem) {
        if (sslCacheado != null && caPem.equals(caCacheada)) return sslCacheado;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate ca;
            try (InputStream in = new java.io.ByteArrayInputStream(caPem.getBytes(StandardCharsets.UTF_8))) {
                ca = cf.generateCertificate(in);
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("guardiania-ca", ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            caCacheada = caPem;
            sslCacheado = ctx;
            return ctx;
        } catch (Exception e) {
            Log.e(TAG, "No se pudo fijar la CA, se usarán las del sistema", e);
            return null;
        }
    }

    public String getBase() { return base; }
}
