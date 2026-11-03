package co.guardiania.app.vpn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** El anonimato del teléfono se califica exactamente igual que en el PC (app/ai/anonimato.py). */
public class AnonimatoTest {

    private static JSONObject ref;

    @BeforeClass
    public static void cargar() throws Exception {
        for (String r : new String[]{"src/test/resources/ref_anonimato.json", "app/src/test/resources/ref_anonimato.json"}) {
            if (Files.exists(Paths.get(r))) { ref = new JSONObject(new String(Files.readAllBytes(Paths.get(r)), StandardCharsets.UTF_8)); return; }
        }
        ref = new JSONObject(new String(AnonimatoTest.class.getClassLoader().getResourceAsStream("ref_anonimato.json").readAllBytes(), StandardCharsets.UTF_8));
    }

    private static Boolean b(JSONObject o, String k) throws Exception { return o.isNull(k) ? null : o.getBoolean(k); }
    private static String t(JSONObject o, String k) throws Exception { return o.isNull(k) ? null : o.getString(k); }

    @Test
    public void evaluaIgualQuePython() throws Exception {
        JSONArray casos = ref.getJSONArray("evaluar");
        for (int i = 0; i < casos.length(); i++) {
            JSONObject e = casos.getJSONObject(i).getJSONObject("entrada"), s = casos.getJSONObject(i).getJSONObject("salida");
            Anonimato.Resultado r = Anonimato.evaluar(e.getBoolean("ip_oculta"), b(e, "ipv6_fuga"), b(e, "dns_fuga"),
                    b(e, "kill_switch"), t(e, "proveedor"), t(e, "nombre"), t(e, "pais_salida"), true);
            String caso = "caso " + i;
            assertEquals(caso + " puntaje", (long) s.getInt("puntaje"), (long) r.puntaje);
            assertEquals(caso + " nivel", s.getString("nivel"), r.nivel);
            JSONArray revs = s.getJSONArray("revisiones");
            assertEquals(caso + " revisiones", (long) revs.length(), (long) r.revisiones.size());
            for (int k = 0; k < revs.length(); k++) {
                JSONObject x = revs.getJSONObject(k);
                Anonimato.Revision y = r.revisiones.get(k);
                assertEquals(caso + " clave " + k, x.getString("clave"), y.clave);
                assertEquals(caso + " estado " + k, x.getString("estado"), y.estado);
                assertEquals(caso + " texto " + k, x.getString("texto"), y.texto);
                assertEquals(caso + " puntos " + k, x.getDouble("puntos"), y.puntos, 1e-9);
            }
            JSONArray cons = s.getJSONArray("consejos");
            assertEquals(caso + " consejos", (long) cons.length(), (long) r.consejos.size());
        }
    }

    @Test
    public void endureceIgualQuePython() throws Exception {
        JSONArray casos = ref.getJSONArray("endurecer");
        for (int i = 0; i < casos.length(); i++) {
            JSONObject c = casos.getJSONObject(i);
            Map<String, String> inter = mapa(c.getJSONObject("interfaz")), peer = mapa(c.getJSONObject("peer"));
            List<String> cambios = Anonimato.endurecer(inter, peer);
            JSONArray esp = c.getJSONArray("cambios");
            assertEquals("cambios " + i, (long) esp.length(), (long) cambios.size());
            for (int k = 0; k < esp.length(); k++) assertEquals("cambio " + i, esp.getString(k), cambios.get(k));
            assertEquals("interfaz " + i, mapa(c.getJSONObject("interfaz_final")), inter);
            assertEquals("peer " + i, mapa(c.getJSONObject("peer_final")), peer);
        }
    }

    @Test
    public void jurisdiccionIgualQuePython() throws Exception {
        JSONArray js = ref.getJSONArray("jurisdiccion");
        for (int i = 0; i < js.length(); i++) {
            JSONObject c = js.getJSONObject(i), s = c.getJSONObject("salida");
            Anonimato.Juicio j = Anonimato.jurisdiccion(c.isNull("pais") ? null : c.getString("pais"));
            assertEquals("alianza " + i, s.getString("alianza"), j.alianza);
            assertEquals("puntaje " + i, s.getDouble("puntaje"), j.puntaje, 1e-9);
            assertEquals("texto " + i, s.getString("texto"), j.texto);
        }
    }

    private static Map<String, String> mapa(JSONObject o) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.getString(k)); }
        return m;
    }
}
