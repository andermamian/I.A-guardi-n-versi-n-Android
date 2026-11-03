package co.guardiania.app.net;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Protocolo WebSocket del canal en vivo (RFC 6455). */
public class CanalEquipoTest {

    @Test
    public void aceptacionDelEjemploOficialDelRfc() {
        // Ejemplo de la sección 1.3 del RFC 6455.
        assertEquals("Sec-WebSocket-Accept", "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                CanalEquipo.aceptadoEsperado("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void marcoEnmascaradoIdaYVuelta() throws Exception {
        for (int largo : new int[]{0, 5, 125, 126, 300, 70_000}) {
            byte[] datos = new byte[largo];
            for (int i = 0; i < largo; i++) datos[i] = (byte) (i * 7);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CanalEquipo.enviarMarco(out, 0x1, datos);
            byte[] crudo = out.toByteArray();
            assertTrue("el cliente siempre enmascara", (crudo[1] & 0x80) != 0);
            CanalEquipo.Marco m = CanalEquipo.leerMarco(new ByteArrayInputStream(crudo));
            assertEquals("opcode", 1L, (long) m.opcode);
            assertTrue("datos intactos con largo " + largo, java.util.Arrays.equals(datos, m.datos));
        }
    }

    @Test
    public void juntaFragmentosDelServidor() throws Exception {
        // «hola» en dos fragmentos sin máscara: texto(FIN=0) «ho» + continuación(FIN=1) «la».
        byte[] crudo = {0x01, 0x02, 'h', 'o', (byte) 0x80, 0x02, 'l', 'a'};
        CanalEquipo.Marco m = CanalEquipo.leerMarco(new ByteArrayInputStream(crudo));
        assertEquals("opcode del mensaje", 1L, (long) m.opcode);
        assertEquals("texto unido", "hola", new String(m.datos, StandardCharsets.UTF_8));
    }
}
