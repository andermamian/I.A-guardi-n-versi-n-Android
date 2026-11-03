package co.guardiania.app.net;

import org.junit.Test;

import javax.net.ssl.SSLProtocolException;

import static org.junit.Assert.*;

/** Lo que la persona escribe como dirección del PC y cómo se le explican los errores. */
public class ConexionPcTest {

    @Test
    public void normalizaLoQueSeEscriba() throws Exception {
        assertEquals("solo IP", "https://10.234.28.243:8443", ConexionPc.normalizar("10.234.28.243"));
        assertEquals("http con 8443", "https://10.234.28.243:8443", ConexionPc.normalizar("http://10.234.28.243:8443"));
        assertEquals("puerto 8000", "https://192.168.1.5:8443", ConexionPc.normalizar("http://192.168.1.5:8000"));
        assertEquals("https sin puerto", "https://192.168.1.5:8443", ConexionPc.normalizar("https://192.168.1.5/"));
        assertEquals("puerto propio", "https://192.168.1.5:9443", ConexionPc.normalizar("192.168.1.5:9443"));
        assertEquals("espacios", "https://192.168.1.5:8443", ConexionPc.normalizar("  192.168.1.5 "));
    }

    @Test
    public void rechazaVacio() throws Exception {
        try {
            ConexionPc.normalizar("  ");
            throw new AssertionError("debió rechazar");
        } catch (IllegalArgumentException esperado) { /* ok */ }
    }

    @Test
    public void explicaErroresTecnicos() throws Exception {
        String tls = ConexionPc.explicar(new SSLProtocolException("Unable to parse TLS packet header"));
        assertTrue("TLS → puerto 8443: " + tls, tls.contains("8443"));
        String ancla = ConexionPc.explicar(new javax.net.ssl.SSLHandshakeException(
                "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."));
        assertTrue("trust anchor explicado: " + ancla, ancla.contains("certificado"));
        String red = ConexionPc.explicar(new java.net.SocketTimeoutException("timeout"));
        assertTrue("sin red explicado: " + red, red.contains("WiFi"));
    }
}
