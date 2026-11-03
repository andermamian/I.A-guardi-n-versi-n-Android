package co.guardiania.app.vpn;

import org.junit.Test;

import static org.junit.Assert.*;

/** Nombres y banderas de países (lo que queda de «ubicaciones»: solo su servidor y Tor). */
public class UbicacionesTest {

    @Test
    public void banderas() {
        assertEquals("🇺🇸", Ubicaciones.bandera("US"));
        assertEquals("🌐", Ubicaciones.bandera(null));
        assertEquals("🌐", Ubicaciones.bandera("us}; x"));
    }

    @Test
    public void nombres() {
        assertEquals("Estados Unidos", Ubicaciones.nombre("US"));
        assertEquals("País desconocido", Ubicaciones.nombre("ZZ"));
        for (String p : Ubicaciones.PAISES_TOR) assertTrue(p, Ubicaciones.PAISES.containsKey(p));
    }
}
