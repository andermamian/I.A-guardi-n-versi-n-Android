package co.guardiania.app.vpn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;

import static org.junit.Assert.*;

/**
 * El celular decide EXACTAMENTE igual que el PC: mismos casos (tests/fixtures/cerebro_vpn_casos.json del
 * servidor Python, copiado a src/test/resources), mismos modos y mismos puntajes.
 */
public class CerebroVpnTest {

    private static JSONArray casos() throws Exception {
        for (String r : new String[]{"src/test/resources/cerebro_vpn_casos.json", "app/src/test/resources/cerebro_vpn_casos.json"}) {
            if (Files.exists(Paths.get(r))) return new JSONArray(new String(Files.readAllBytes(Paths.get(r)), StandardCharsets.UTF_8));
        }
        return new JSONArray(new String(CerebroVpnTest.class.getClassLoader().getResourceAsStream("cerebro_vpn_casos.json")
                .readAllBytes(), StandardCharsets.UTF_8));
    }

    static CerebroVpn.Senales senales(JSONObject j) {
        CerebroVpn.Senales s = new CerebroVpn.Senales();
        s.servidorConfigurado = j.optBoolean("servidor_configurado", false);
        s.servidorEnLinea = j.has("servidor_en_linea") && !j.isNull("servidor_en_linea") ? j.optBoolean("servidor_en_linea") : null;
        s.latenciaServidorMs = j.has("latencia_servidor_ms") && !j.isNull("latencia_servidor_ms") ? j.optInt("latencia_servidor_ms") : null;
        s.consumoFraccion = j.has("consumo_fraccion") && !j.isNull("consumo_fraccion") ? j.optDouble("consumo_fraccion") : null;
        s.torDisponible = j.optBoolean("tor_disponible", true);
        s.redInsegura = j.has("red_insegura") && !j.isNull("red_insegura") ? j.optBoolean("red_insegura") : null;
        s.tunelDisponible = j.optBoolean("tunel_disponible", true);
        return s;
    }

    @Test
    public void mismosCasosQueElPc() throws Exception {
        JSONArray cs = casos();
        assertTrue(cs.length() >= 10);
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.getJSONObject(i);
            JSONObject mem = c.isNull("memoria") ? null : c.getJSONObject("memoria");
            JSONArray ex = c.optJSONArray("excluir");
            String[] excluir = new String[ex == null ? 0 : ex.length()];
            for (int k = 0; k < excluir.length; k++) excluir[k] = ex.getString(k);
            CerebroVpn.Decision d = CerebroVpn.decidir(senales(c.getJSONObject("senales")), c.getString("objetivo"), mem,
                    c.getDouble("ahora"), excluir);
            String nombre = c.getString("nombre");
            assertEquals(nombre, c.isNull("esperado") ? null : c.getString("esperado"), d.modo);
            JSONObject pj = c.getJSONObject("puntajes");
            assertEquals(nombre, pj.length(), d.puntajes.size());
            for (Iterator<String> it = pj.keys(); it.hasNext(); ) {
                String k = it.next();
                assertEquals(nombre + " · " + k, pj.getDouble(k), d.puntajes.get(k), 1e-4);
            }
        }
    }

    @Test
    public void aprendeYRecuerdaFallosRecientes() throws Exception {
        JSONObject m = CerebroVpn.memoriaVacia();
        for (int i = 0; i < 4; i++) CerebroVpn.aprender(m, CerebroVpn.SERVIDOR, false, null, 10);
        assertTrue(m.getJSONObject(CerebroVpn.SERVIDOR).getDouble("fiabilidad") < 0.25);
        CerebroVpn.aprender(m, CerebroVpn.TOR, true, 40.0, 10);
        CerebroVpn.aprender(m, CerebroVpn.TOR, true, 20.0, 10);
        double seg = m.getJSONObject(CerebroVpn.TOR).getDouble("segundos");
        assertTrue(seg > 30 && seg < 40);
        CerebroVpn.aprender(m, "inventado", true, null, 10);
        assertFalse(m.has("inventado"));
    }

    @Test
    public void histeresis() {
        CerebroVpn.Senales s = new CerebroVpn.Senales();
        s.servidorConfigurado = true; s.servidorEnLinea = true;
        CerebroVpn.Decision d = CerebroVpn.decidir(s, CerebroVpn.EQUILIBRIO, null, 10_000);
        assertEquals(CerebroVpn.SERVIDOR, d.modo);
        assertFalse(CerebroVpn.convieneCambiar(CerebroVpn.TOR, d, 9_900, true, 10_000));
        assertTrue(CerebroVpn.convieneCambiar(CerebroVpn.TOR, d, 0, true, 10_000));
        assertTrue(CerebroVpn.convieneCambiar(CerebroVpn.TOR, d, 9_990, false, 10_000));
        assertFalse(CerebroVpn.convieneCambiar(CerebroVpn.SERVIDOR, d, 0, true, 10_000));
    }

    @Test
    public void nombresViejosSonSuServidor() {
        assertEquals(ModoPrivacidad.SERVIDOR, ModoPrivacidad.normalizar("proton"));
        assertEquals(ModoPrivacidad.SERVIDOR_TOR, ModoPrivacidad.normalizar("proton_tor"));
        assertEquals(ModoPrivacidad.APAGADO, ModoPrivacidad.normalizar("warp"));
        assertEquals(ModoPrivacidad.AUTO, ModoPrivacidad.normalizar("auto"));
    }
}
