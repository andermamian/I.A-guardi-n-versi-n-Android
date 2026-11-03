package co.guardiania.app.net;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class DescubrirPcTest {

    @Test
    public void vecinasCubrenLaRedSinLaPropia() throws Exception {
        List<String> v = DescubrirPc.vecinas("10.234.28.50");
        assertEquals("253 vecinas", 253L, (long) v.size());
        assertTrue("incluye el PC", v.contains("10.234.28.243"));
        assertFalse("no se prueba a sí mismo", v.contains("10.234.28.50"));
        assertFalse("no incluye la de red", v.contains("10.234.28.0"));
        assertFalse("no incluye broadcast", v.contains("10.234.28.255"));
    }
}
