package co.guardiania.app.seguridad;

import org.junit.Test;

import static org.junit.Assert.*;

/** Los errores de envío se explican y se clasifican (reintentar o no). */
public class SmsSosTest {

    @Test
    public void error111SeExplica() {
        String m = SmsSos.motivo(111);                  // RESULT_RIL_MODEM_ERR: el que salió en el Redmi
        assertTrue(m, m.contains("SIM"));
        assertFalse("se reintenta (puede ser la otra SIM)", SmsSos.permanente(111));
    }

    @Test
    public void sinSenalSeReintentaYNumeroInvalidoNo() {
        assertTrue(SmsSos.motivo(4).contains("señal"));
        assertFalse(SmsSos.permanente(4));
        assertTrue(SmsSos.motivo(11).contains("inválido"));
        assertTrue("número inválido no se reintenta", SmsSos.permanente(11));
        assertTrue("FDN no se reintenta", SmsSos.permanente(6));
    }

    @Test
    public void ningunCodigoQuedaSinTexto() {
        for (int c = 0; c < 140; c++) assertFalse("código " + c, SmsSos.motivo(c).isEmpty());
    }
}
