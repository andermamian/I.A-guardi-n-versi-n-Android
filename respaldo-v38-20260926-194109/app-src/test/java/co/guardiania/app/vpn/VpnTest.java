package co.guardiania.app.vpn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * La VPN del teléfono aplica las mismas reglas que la del PC (app/local/vpn.py y app/ai/vpn_ai.py).
 * ref_vpn.json lo genera el backend Python con los mismos casos.
 */
public class VpnTest {

    private static JSONObject ref;

    @BeforeClass
    public static void cargar() throws Exception {
        for (String r : new String[]{"src/test/resources/ref_vpn.json", "app/src/test/resources/ref_vpn.json"}) {
            if (Files.exists(Paths.get(r))) { ref = new JSONObject(new String(Files.readAllBytes(Paths.get(r)), StandardCharsets.UTF_8)); return; }
        }
        ref = new JSONObject(new String(VpnTest.class.getClassLoader().getResourceAsStream("ref_vpn.json").readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void validaConfIgualQuePython() throws Exception {
        JSONArray casos = ref.getJSONArray("conf");
        for (int i = 0; i < casos.length(); i++) {
            JSONObject c = casos.getJSONObject(i);
            String nombre = c.getString("caso");
            try {
                ConfWg.Importada imp = ConfWg.importar(c.getString("texto"));
                assertTrue(nombre + " debió fallar", c.getBoolean("ok"));
                assertEquals(nombre + " interfaz", mapa(c.getJSONObject("interfaz")), imp.interfaz);
                assertEquals(nombre + " peer", mapa(c.getJSONObject("peer")), imp.peer);
            } catch (ConfWg.Invalida e) {
                assertFalse(nombre + " debió aceptarse: " + e.getMessage(), c.getBoolean("ok"));
                assertEquals(nombre + " mensaje", c.getString("error"), e.getMessage());
            }
        }
    }

    @Test
    public void textoExcluyeRedLocalSinPerderInternet() throws Exception {
        ConfWg.Importada imp = ConfWg.importar(ref.getJSONArray("conf").getJSONObject(0).getString("texto"));
        String completo = ConfWg.texto(imp.privada, imp.interfaz, imp.peer, false);
        assertTrue("sin exclusión enruta todo", completo.contains("AllowedIPs = 0.0.0.0/0, ::/0"));
        String lan = ConfWg.texto(imp.privada, imp.interfaz, imp.peer, true);
        assertFalse("con exclusión ya no está 0.0.0.0/0", lan.contains("0.0.0.0/0"));
        assertFalse("la red 10/8 del PC queda fuera", lan.contains("10.0.0.0/8"));
        assertTrue("8.8.8.8 sigue por el túnel", lan.contains("8.0.0.0/7"));
        assertTrue("IPv6 sigue por el túnel", lan.contains("::/0"));
        assertFalse("nunca incluye PostUp", lan.toLowerCase().contains("postup"));
    }

    @Test
    public void recomiendaIgualQuePython() throws Exception {
        List<RecomendadorVpn.Servidor> servidores = new ArrayList<>();
        JSONArray ss = ref.getJSONArray("servidores");
        for (int i = 0; i < ss.length(); i++) {
            JSONObject s = ss.getJSONObject(i);
            servidores.add(new RecomendadorVpn.Servidor(s.getString("id"), s.getString("country"), s.getString("city"),
                    s.getString("protocol"), s.getBoolean("is_premium"), s.getDouble("load"), s.getInt("latency_ms"),
                    s.getBoolean("active")));
        }
        JSONArray recs = ref.getJSONArray("recomendaciones");
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = recs.getJSONObject(i);
            String prop = r.getString("proposito");
            String pais = r.isNull("pais") ? null : r.getString("pais");
            String destino = r.isNull("destino") ? null : r.getString("destino");
            Map<String, Double> obs = new HashMap<>();
            JSONObject o = r.getJSONObject("observada");
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); obs.put(k, o.getDouble(k)); }
            String caso = prop + "/" + pais + "/" + destino;

            RecomendadorVpn.Recomendacion rec = RecomendadorVpn.recomendar(servidores, prop, pais, destino, r.getBoolean("premium"), obs);
            assertEquals(caso + " mejor", r.getString("mejor"), rec.servidor.id);
            assertEquals(caso + " puntaje", r.getDouble("puntaje"), rec.puntaje, 1e-9);
            assertEquals(caso + " latencia", (long) r.getInt("latencia"), rec.latenciaEstimadaMs);
            JSONArray motivos = r.getJSONArray("motivos");
            List<String> esperados = new ArrayList<>();
            for (int j = 0; j < motivos.length(); j++) esperados.add(motivos.getString(j));
            assertEquals(caso + " motivos", esperados, rec.motivos);

            List<RecomendadorVpn.Puntuado> rk = RecomendadorVpn.ordenar(servidores, prop, pais, destino, obs, 10);
            JSONArray rank = r.getJSONArray("ranking");
            assertEquals(caso + " tamaño ranking", (long) rank.length(), (long) rk.size());
            for (int j = 0; j < rank.length(); j++) {
                assertEquals(caso + " ranking " + j, rank.getJSONArray(j).getString(0), rk.get(j).servidor.id);
                assertEquals(caso + " puntaje " + j, rank.getJSONArray(j).getDouble(1), rk.get(j).puntaje, 1e-9);
            }
        }
    }

    @Test
    public void aprendeLatenciaYDetectaSaturados() throws Exception {
        assertEquals("EWMA", ref.getDouble("ewma"), RecomendadorVpn.actualizarLatencia(100.0, 40.0), 1e-9);
        assertEquals("primera muestra", 55.0, RecomendadorVpn.actualizarLatencia(null, 55.0), 1e-9);
    }

    private static Map<String, String> mapa(JSONObject o) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.getString(k)); }
        return m;
    }
}
