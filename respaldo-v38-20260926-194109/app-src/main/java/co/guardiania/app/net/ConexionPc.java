package co.guardiania.app.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Prepara una conexión HTTPS segura con el PC de GuardianIA, sin pedirle a la persona que instale
 * certificados a mano.
 *
 * <p><b>Qué fallaba.</b> La app solo bajaba la CA del PC por {@code http://IP:8000/ca.crt}. Si ese
 * puerto no respondía (el firewall de Windows suele permitir solo el 8443), seguía sin CA y Android
 * rechazaba el HTTPS con «Trust anchor for certification path not found». Y si la dirección apuntaba
 * a un puerto sin HTTPS, salía «Unable to parse TLS packet header».
 *
 * <p><b>Qué hace ahora.</b>
 * <ol>
 *   <li>Normaliza lo que se escriba: {@code 10.0.0.5}, {@code http://10.0.0.5:8000} o
 *       {@code https://10.0.0.5} quedan como {@code https://10.0.0.5:8443}.</li>
 *   <li>Busca la CA por el puerto 8000 y, si no responde, por el mismo 8443.</li>
 *   <li>Comprueba que esa CA <i>firmó de verdad</i> el certificado que presenta el servidor. Una CA
 *       que no firma al servidor se rechaza, venga de donde venga.</li>
 *   <li>Si el QR traía la huella, exige que coincida. Si se escribió a mano, devuelve la huella corta
 *       para que la persona la compare con la que muestra la consola del PC.</li>
 *   <li>Prueba {@code /health} ya con la CA fijada: si responde, la conexión es buena.</li>
 * </ol>
 * No depende de Android: se prueba en la JVM.
 */
public final class ConexionPc {

    public static final int PUERTO_HTTPS = 8443;
    public static final int PUERTO_HTTP = 8000;
    private static final int TIEMPO_MS = 7000;

    private ConexionPc() {}

    /** Resultado listo para guardar: dirección normalizada, CA fijada y su huella. */
    public static final class Preparada {
        public final String servidor, caPem, huella;
        /** true si la huella se comprobó contra el QR (no hace falta preguntar a la persona). */
        public final boolean verificadaPorQr;

        Preparada(String servidor, String caPem, String huella, boolean qr) {
            this.servidor = servidor;
            this.caPem = caPem;
            this.huella = huella;
            this.verificadaPorQr = qr;
        }

        public String huellaCorta() { return ApiClient.huellaCorta(huella); }
    }

    /** Error con mensaje ya explicado en español para mostrar tal cual. */
    public static final class Problema extends IOException {
        public Problema(String mensaje, Throwable causa) { super(mensaje, causa); }
    }

    // ─────────────────────────── dirección
    /**
     * Acepta lo que la persona escriba y lo deja como {@code https://host:8443}. Un 8000 escrito a
     * mano se cambia por 8443 (el 8000 solo redirige y entrega la CA; no sirve la API).
     */
    public static String normalizar(String entrada) {
        String s = entrada == null ? "" : entrada.trim().replaceAll("\\s+", "");
        if (s.isEmpty()) throw new IllegalArgumentException("Escribe la dirección del PC (ej. 192.168.1.5)");
        if (!s.contains("://")) s = "https://" + s;
        URI u;
        try {
            u = new URI(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("La dirección no es válida: " + entrada);
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("La dirección no es válida: " + entrada);
        int puerto = u.getPort();
        if (puerto <= 0 || puerto == PUERTO_HTTP || puerto == 80) puerto = PUERTO_HTTPS;
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "https://" + h.toLowerCase(Locale.ROOT) + ":" + puerto;
    }

    static String host(String servidorNormalizado) {
        return URI.create(servidorNormalizado).getHost();
    }

    static int puerto(String servidorNormalizado) {
        int p = URI.create(servidorNormalizado).getPort();
        return p > 0 ? p : PUERTO_HTTPS;
    }

    // ─────────────────────────── preparación completa
    /**
     * Obtiene y valida la CA del PC y comprueba que la API responde. Bloquea: llamar en segundo plano.
     *
     * @param entrada        lo escrito por la persona o lo leído del QR
     * @param huellaEsperada SHA-256 hex de la CA si vino en el QR; null si se escribió a mano
     */
    public static Preparada preparar(String entrada, String huellaEsperada) throws IOException {
        String servidor = normalizar(entrada);
        String host = host(servidor);
        int puerto = puerto(servidor);

        // 1. El certificado que presenta el servidor (sin confiar todavía en nada).
        X509Certificate hoja = certificadoDelServidor(host, puerto);

        // 2. La CA: primero por el puerto 8000; si no, por el 8443.
        String caPem = null;
        IOException ultimo = null;
        for (String url : new String[]{"http://" + hostUrl(host) + ":" + PUERTO_HTTP + "/ca.crt",
                "https://" + hostUrl(host) + ":" + puerto + "/ca.crt"}) {
            try {
                String pem = descargar(url);
                X509Certificate ca = leerCertificado(pem);
                if (firmo(ca, hoja)) { caPem = aPem(ca); break; }
                ultimo = new Problema("El certificado entregado por " + url + " no corresponde al servidor.", null);
            } catch (IOException e) {
                ultimo = e;
            }
        }
        if (caPem == null) {
            throw new Problema("No pude obtener el certificado del PC. Comprueba que GuardianIA esté corriendo con "
                    + "«python -m app.servidor» y que el teléfono esté en el mismo WiFi.", ultimo);
        }

        // 3. Huella: la del QR manda. A mano, se devuelve para que la persona la compare.
        String huella = ApiClient.huellaCa(caPem);
        boolean porQr = false;
        if (huellaEsperada != null && !huellaEsperada.isEmpty()) {
            if (!huellaEsperada.equalsIgnoreCase(huella)) {
                throw new Problema("El certificado del PC no coincide con el del QR. Alguien podría estar interceptando "
                        + "la red. Genera un QR nuevo en el PC e inténtalo de nuevo.", null);
            }
            porQr = true;
        }

        // 4. Prueba final con la CA fijada.
        ApiClient api = new ApiClient(servidor, caPem);
        try {
            ApiClient.Resp r = api.get("/health");
            if (r.code >= 500) throw new Problema("El PC respondió con un error (" + r.code + "). Revisa la consola del servidor.", null);
        } catch (Problema p) {
            throw p;
        } catch (IOException e) {
            throw new Problema(explicar(e), e);
        }
        return new Preparada(servidor, caPem, huella, porQr);
    }

    /** ¿El PC guardado responde con su CA fijada? (rápido: se usa antes de buscar en la red). */
    public static boolean responde(String servidor, String caPem) {
        if (servidor == null || servidor.isEmpty() || caPem == null || caPem.isEmpty()) return false;
        try {
            // Sondeo rápido del puerto: si la IP guardada ya no existe, no se esperan 20 s.
            try (java.net.Socket so = new java.net.Socket()) {
                so.connect(new java.net.InetSocketAddress(host(servidor), puerto(servidor)), 1500);
            }
            return new ApiClient(servidor, caPem).get("/health").code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────── piezas
    private static String hostUrl(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    /** Lee la cadena del servidor sin validarla (solo para compararla luego con la CA). */
    static X509Certificate certificadoDelServidor(String host, int puerto) throws IOException {
        AtomicReference<X509Certificate[]> cadena = new AtomicReference<>();
        TrustManager captura = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) { cadena.set(c); }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        HttpsURLConnection c = null;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{captura}, null);
            c = (HttpsURLConnection) new URL("https://" + hostUrl(host) + ":" + puerto + "/health").openConnection();
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier((h, s) -> true);   // aquí SOLO se lee el certificado; nada se envía
            c.setConnectTimeout(TIEMPO_MS);
            c.setReadTimeout(TIEMPO_MS);
            c.connect();
        } catch (IOException e) {
            throw new Problema(explicar(e), e);
        } catch (Exception e) {
            throw new Problema("No se pudo iniciar la conexión segura: " + e.getMessage(), e);
        } finally {
            if (c != null) c.disconnect();
        }
        X509Certificate[] cs = cadena.get();
        if (cs == null || cs.length == 0) throw new Problema("El PC no presentó ningún certificado.", null);
        return cs[0];
    }

    /** Descarga un texto pequeño (la CA). Para https confía temporalmente: la CA se valida después. */
    static String descargar(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            if (c instanceof HttpsURLConnection) {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] x, String a) {}
                    @Override public void checkServerTrusted(X509Certificate[] x, String a) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, null);
                ((HttpsURLConnection) c).setSSLSocketFactory(ctx.getSocketFactory());
                ((HttpsURLConnection) c).setHostnameVerifier((h, s) -> true);
            }
            c.setConnectTimeout(TIEMPO_MS);
            c.setReadTimeout(TIEMPO_MS);
            c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) throw new IOException(url + " respondió " + c.getResponseCode());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 64 * 1024) throw new IOException("Respuesta demasiado grande para ser un certificado");
                }
            }
            return bos.toString("UTF-8");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            c.disconnect();
        }
    }

    static X509Certificate leerCertificado(String pemODer) throws IOException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pemODer.getBytes(StandardCharsets.ISO_8859_1)));
        } catch (Exception e) {
            throw new IOException("Lo descargado no es un certificado válido", e);
        }
    }

    /** ¿Esta CA firmó el certificado del servidor? (y es de verdad una CA). */
    static boolean firmo(X509Certificate ca, X509Certificate hoja) {
        try {
            if (ca.getBasicConstraints() < 0) return false;          // no es CA
            if (!hoja.getIssuerX500Principal().equals(ca.getSubjectX500Principal())) return false;
            hoja.verify(ca.getPublicKey());
            ca.checkValidity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String aPem(X509Certificate c) throws IOException {
        try {
            String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(c.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    // ─────────────────────────── mensajes
    /** Traduce los errores técnicos de red a algo que la persona pueda resolver. */
    public static String explicar(Throwable e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        Throwable raiz = e;
        while (raiz.getCause() != null && raiz.getCause() != raiz) raiz = raiz.getCause();
        String r = String.valueOf(raiz.getMessage()).toLowerCase(Locale.ROOT);
        if (e instanceof Problema) return e.getMessage();
        if (m.contains("unable to parse tls packet header") || m.contains("not an ssl/tls record")
                || m.contains("unrecognized ssl message") || e instanceof SSLProtocolException) {
            return "Esa dirección no responde por HTTPS. Usa el puerto 8443 (ej. https://192.168.1.5:8443), no el 8000.";
        }
        if (m.contains("trust anchor") || r.contains("trust anchor") || m.contains("pkix")) {
            return "El teléfono aún no confía en el certificado del PC. Vuelve a escribir la dirección para que la app "
                    + "lo descargue y lo compruebe.";
        }
        if (e instanceof SSLPeerUnverifiedException || m.contains("hostname") || m.contains("not verified")) {
            return "El certificado del PC no incluye esta dirección IP (cambió la red). En el PC, cierra y vuelve a abrir "
                    + "«python -m app.servidor»: regenera el certificado solo.";
        }
        if (e instanceof SSLHandshakeException) {
            return "No se pudo establecer la conexión segura con el PC: " + e.getMessage();
        }
        if (e instanceof SocketTimeoutException || e instanceof ConnectException || e instanceof NoRouteToHostException
                || m.contains("failed to connect") || m.contains("timed out") || m.contains("econnrefused")) {
            return "No hay conexión con el PC. Revisa que: 1) esté encendido «python -m app.servidor», 2) el teléfono y el "
                    + "PC estén en la misma red WiFi, 3) el firewall de Windows permita Python en redes privadas.";
        }
        if (e instanceof UnknownHostException) return "No se encontró esa dirección. Escribe la IP que muestra la consola del PC.";
        return "No se pudo conectar con el PC: " + e.getMessage();
    }
}
