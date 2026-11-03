package co.guardiania.app.seguridad;

import org.junit.Test;

import static org.junit.Assert.*;

public class MensajeSosTest {

    @Test
    public void cabeEnUnSoloSmsConLosPeoresDatos() {
        String s = MensajeSos.sos("José María Núñez Rodríguez", -12.345678, -176.123456, 9999, "11:59 pm");
        assertTrue("largo " + s.length() + ": " + s, s.length() <= MensajeSos.MAX_SMS);
        assertTrue("solo alfabeto básico: " + s, MensajeSos.gsm7(s));
        assertTrue("trae el mapa", s.contains("https://maps.google.com/?q=-12.34568%2C-176.12346"));
        assertTrue("nombre sin tildes", s.contains("Jose "));
    }

    @Test
    public void mensajeNormalCompleto() {
        String s = MensajeSos.sos("Anderson Mamián", 4.609710, -74.081750, 15, "3:45 pm");
        assertTrue(s, s.length() <= MensajeSos.MAX_SMS);
        assertTrue(s, s.contains("Anderson necesita ayuda"));
        assertTrue(s, s.contains("123"));
        assertTrue(s, s.contains("Llamale"));
    }

    @Test
    public void sinUbicacionIgualAvisa() {
        String s = MensajeSos.sos("Ana", null, null, null, "8:00 am");
        assertTrue(s, s.contains("Ubicacion no disponible") && s.length() <= MensajeSos.MAX_SMS && MensajeSos.gsm7(s));
    }

    @Test
    public void estoyBienCorto() {
        String s = MensajeSos.estoyBien("Ñuñez");
        assertTrue(s, s.length() <= MensajeSos.MAX_SMS && MensajeSos.gsm7(s) && s.contains("Nunez"));
    }

    @Test
    public void enlaceDeUnSoloToque() {
        String s = MensajeSos.sos("Herney", 8.3612, -72.40833, 61, MensajeSos.hora(new java.util.Date(0)));
        assertTrue(s, s.contains("https://maps.google.com/?q=8.36120%2C-72.40833 "));   // sin coma: un solo enlace
        assertFalse("sin coma en el enlace", s.contains("8.36120,"));
        String h = MensajeSos.hora(new java.util.Date(0));
        assertTrue("hora 24 h: " + h, h.length() == 5 && h.charAt(2) == ':' && !h.contains("m"));
        assertTrue("hora en el mensaje", s.contains("(+-61m " + h + ")"));
        assertTrue(s, s.length() <= MensajeSos.MAX_SMS && MensajeSos.gsm7(s));
    }

    @Test
    public void sosRemotoEsHonestoYCabe() {
        String s = MensajeSos.sosRemoto("José María", -12.345678, -176.123456, 9999, "23:59");
        assertTrue("largo " + s.length() + ": " + s, s.length() <= MensajeSos.MAX_SMS && MensajeSos.gsm7(s));
        assertTrue(s, s.contains("podria necesitar ayuda"));
        assertTrue(s, s.contains("%2C") && s.contains("aviso de su cuenta"));
        String normal = MensajeSos.sosRemoto("Herney", 8.3612, -72.40833, 61, "17:15");
        assertTrue(normal, normal.contains("aviso de su cuenta") && normal.contains("123"));
        assertTrue(normal, normal.length() <= MensajeSos.MAX_SMS);
    }
}
