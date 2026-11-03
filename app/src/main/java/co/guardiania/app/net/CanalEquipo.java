package co.guardiania.app.net;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Canal en vivo del teléfono con el PC ({@code /v1/ws/equipo}): las órdenes del dueño llegan en menos
 * de un segundo en vez de esperar al siguiente latido (hasta 30 s).
 *
 * <p>Es un cliente WebSocket mínimo (RFC 6455) sobre la MISMA conexión TLS con la CA fijada que usa
 * el resto de la app: sin dependencias nuevas y sin confiar en otros certificados. Se autentica con
 * el token del dispositivo, contesta los pings y se reconecta solo con espera creciente (2 s → 60 s)
 * si se cae la red. El servidor manda un «ping» cada 30 s; si pasan 75 s sin nada, se da por caído.
 *
 * <p>No depende de Android: se prueba contra el servidor real en la JVM.
 */
public final class CanalEquipo {

    public interface Oyente {
        void evento(JSONObject e);
        default void conectado(boolean si) {}
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int LECTURA_MS = 75_000;
    private static final SecureRandom AZAR = new SecureRandom();
    /** En Android el verificador del sistema es el real; en la JVM de escritorio devuelve siempre false. */
    private static final boolean EN_ANDROID = String.valueOf(System.getProperty("java.vendor")).contains("Android");

    private final String base, token;
    private final SSLContext ssl;
    private final Oyente oyente;
    private volatile boolean activo = true;
    private volatile Socket socket;
    private Thread hilo;

    public CanalEquipo(ApiClient api, String deviceToken, Oyente oyente) {
        this.base = api.base();
        this.ssl = api.sslContext();
        this.token = deviceToken;
        this.oyente = oyente;
    }

    public synchronized void iniciar() {
        if (hilo != null && hilo.isAlive()) return;
        activo = true;
        hilo = new Thread(this::bucle, "canal-equipo");
        hilo.setDaemon(true);
        hilo.start();
    }

    public synchronized void detener() {
        activo = false;
        cerrarSocket();
        if (hilo != null) hilo.interrupt();
    }

    private volatile boolean conectadoAhora = false;
    private final Object espera = new Object();

    /** ¿El canal está abierto en este momento? */
    public boolean conectado() { return conectadoAhora; }

    /**
     * Reconecta YA: cierra el socket (si quedó colgado) y corta la espera del reintento. Se usa al
     * volver la red, cuando el servidor avisa que no ve el canal, o con el botón «Conectar».
     */
    public void reconectarYa() {
        synchronized (espera) { prisa = true; espera.notifyAll(); }
        cerrarSocket();
    }

    private boolean prisa = false;   // protegido por «espera»

    /** Compatibilidad con el nombre anterior. */
    public void reconectar() { reconectarYa(); }

    private void bucle() {
        long esperaMs = 2000;
        while (activo) {
            try {
                conectarYEscuchar();
                esperaMs = 1000;                                // se cerró limpio: reintento rápido
            } catch (CierrePermanente e) {
                conectadoAhora = false;
                oyente.conectado(false);
                return;                                         // token inválido: no insistir
            } catch (Exception e) {
                conectadoAhora = false;
                oyente.conectado(false);
            }
            if (!activo) return;
            // Espera interrumpible: reconectarYa() la corta al instante. Máximo 15 s (antes 60 s).
            synchronized (this.espera) {
                if (prisa) { prisa = false; esperaMs = 1000; continue; }   // pidieron reconectar ya
                try { this.espera.wait(esperaMs); } catch (InterruptedException ie) { return; }
                if (prisa) { prisa = false; esperaMs = 1000; continue; }
            }
            esperaMs = Math.min(15_000, esperaMs * 2);
        }
    }

    static final class CierrePermanente extends IOException {
        CierrePermanente(String m) { super(m); }
    }

    private void conectarYEscuchar() throws IOException {
        URI u = URI.create(base);
        String host = u.getHost();
        int puerto = u.getPort() > 0 ? u.getPort() : 443;
        SSLSocketFactory f = ssl != null ? ssl.getSocketFactory() : (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket plano = new Socket();
        plano.connect(new InetSocketAddress(host, puerto), 10_000);
        SSLSocket s = (SSLSocket) f.createSocket(plano, host, puerto, true);
        socket = s;
        try {
            s.setSoTimeout(LECTURA_MS);
            // El certificado debe corresponder a esta dirección (igual que HttpsURLConnection).
            javax.net.ssl.SSLParameters par = s.getSSLParameters();
            par.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(par);
            s.startHandshake();
            if (EN_ANDROID && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
                throw new IOException("El certificado del PC no corresponde a " + host);
            }
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            apretonDeManos(in, out, host, puerto);
            enviarTexto(out, new JSONObject().put("device_token", token).toString());
            conectadoAhora = true;
            oyente.conectado(true);
            while (activo) {
                Marco m = leerMarco(in);
                switch (m.opcode) {
                    case 0x1:                                   // texto
                        try { oyente.evento(new JSONObject(new String(m.datos, StandardCharsets.UTF_8))); }
                        catch (Exception ignored) {}
                        break;
                    case 0x9:                                   // ping → pong
                        enviarMarco(out, 0xA, m.datos);
                        break;
                    case 0x8:                                   // cierre
                        int codigo = m.datos.length >= 2 ? ((m.datos[0] & 0xFF) << 8) | (m.datos[1] & 0xFF) : 1000;
                        try { enviarMarco(out, 0x8, m.datos); } catch (IOException ignored) {}
                        if (codigo == 4401) throw new CierrePermanente("Token de dispositivo rechazado");
                        return;
                    default:
                        break;                                  // pong u otros: nada que hacer
                }
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("Sin noticias del PC en " + LECTURA_MS / 1000 + " s", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        } finally {
            conectadoAhora = false;
            cerrarSocket();
        }
    }

    private static void apretonDeManos(InputStream in, OutputStream out, String host, int puerto) throws IOException {
        byte[] n = new byte[16];
        AZAR.nextBytes(n);
        String clave = Base64.getEncoder().encodeToString(n);
        String hostHdr = (host.contains(":") ? "[" + host + "]" : host) + ":" + puerto;
        String req = "GET /v1/ws/equipo HTTP/1.1\r\n"
                + "Host: " + hostHdr + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + clave + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: GuardianIA-Android\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String estado = linea(in);
        if (!estado.startsWith("HTTP/1.1 101")) throw new IOException("El PC no aceptó el canal en vivo: " + estado);
        String aceptado = null;
        for (String l = linea(in); !l.isEmpty(); l = linea(in)) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().toLowerCase(Locale.ROOT).equals("sec-websocket-accept")) {
                aceptado = l.substring(i + 1).trim();
            }
        }
        if (!aceptadoEsperado(clave).equals(aceptado)) throw new IOException("Respuesta de WebSocket inválida");
    }

    static String aceptadoEsperado(String clave) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest((clave + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String linea(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("Cabecera demasiado larga");
        }
        if (c == -1 && b.size() == 0) throw new EOFException();
        return b.toString("ISO-8859-1");
    }

    static final class Marco {
        final int opcode;
        final byte[] datos;
        Marco(int o, byte[] d) { opcode = o; datos = d; }
    }

    /** Lee un mensaje completo (junta fragmentos). Los marcos del servidor no van enmascarados. */
    static Marco leerMarco(InputStream in) throws IOException {
        ByteArrayOutputStream mensaje = new ByteArrayOutputStream();
        int opcodeMensaje = -1;
        while (true) {
            int b0 = leerByte(in), b1 = leerByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean mascara = (b1 & 0x80) != 0;
            long largo = b1 & 0x7F;
            if (largo == 126) largo = (leerByte(in) << 8) | leerByte(in);
            else if (largo == 127) {
                largo = 0;
                for (int i = 0; i < 8; i++) largo = (largo << 8) | leerByte(in);
            }
            if (largo > 1_000_000) throw new IOException("Mensaje demasiado grande");
            byte[] m = new byte[4];
            if (mascara) for (int i = 0; i < 4; i++) m[i] = (byte) leerByte(in);
            byte[] datos = new byte[(int) largo];
            int leidos = 0;
            while (leidos < datos.length) {
                int n = in.read(datos, leidos, datos.length - leidos);
                if (n < 0) throw new EOFException();
                leidos += n;
            }
            if (mascara) for (int i = 0; i < datos.length; i++) datos[i] ^= m[i % 4];
            if (opcode >= 0x8) return new Marco(opcode, datos);          // control: nunca fragmentado
            if (opcode != 0) opcodeMensaje = opcode;
            mensaje.write(datos);
            if (fin) return new Marco(opcodeMensaje, mensaje.toByteArray());
        }
    }

    private static int leerByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static void enviarTexto(OutputStream out, String texto) throws IOException {
        enviarMarco(out, 0x1, texto.getBytes(StandardCharsets.UTF_8));
    }

    /** El cliente SIEMPRE enmascara sus marcos (lo exige el protocolo). */
    static synchronized void enviarMarco(OutputStream out, int opcode, byte[] datos) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x80 | opcode);
        if (datos.length < 126) b.write(0x80 | datos.length);
        else if (datos.length < 65536) { b.write(0x80 | 126); b.write(datos.length >> 8); b.write(datos.length & 0xFF); }
        else {
            b.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) b.write((int) (((long) datos.length >> (8 * i)) & 0xFF));
        }
        byte[] m = new byte[4];
        AZAR.nextBytes(m);
        b.write(m);
        for (int i = 0; i < datos.length; i++) b.write(datos[i] ^ m[i % 4]);
        out.write(b.toByteArray());
        out.flush();
    }

    private void cerrarSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }
}
