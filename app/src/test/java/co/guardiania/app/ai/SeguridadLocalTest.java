package co.guardiania.app.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * El análisis de enlaces y los guardarraíles del teléfono deben coincidir con los del servidor
 * (app/ai/url_risk.py y app/ai/assistant.py). Así un enlace no es «seguro» en el celular y
 * «bloquear» en el PC, y una crisis se atiende igual con o sin conexión.
 */
public class SeguridadLocalTest {

    private static JSONObject ref;

    @BeforeClass
    public static void cargar() throws Exception {
        ref = new JSONObject(Recursos.leer("ref_seguridad.json", "src/test/resources/ref_seguridad.json",
                "app/src/test/resources/ref_seguridad.json"));
    }

    @Test
    public void enlacesIgualQuePython() throws Exception {
        JSONArray urls = ref.getJSONArray("urls");
        for (int i = 0; i < urls.length(); i++) {
            JSONObject u = urls.getJSONObject(i);
            RiesgoUrl.Veredicto v = RiesgoUrl.evaluar(u.getString("url"));
            assertEquals("veredicto de " + u.getString("url"), u.getString("veredicto"), v.veredicto);
            assertEquals("puntaje de " + u.getString("url"), (long) u.getInt("puntaje"), (long) v.puntaje);
        }
    }

    @Test
    public void guardarrailesIgualQuePython() throws Exception {
        JSONArray g = ref.getJSONArray("guard");
        for (int i = 0; i < g.length(); i++) {
            JSONObject c = g.getJSONObject(i);
            String esperado = c.isNull("cat") ? null : c.getString("cat");
            assertEquals("categoría de " + c.getString("texto"), esperado, Guardarrailes.clasificar(c.getString("texto")));
        }
    }

    @Test
    public void crisisSiempreDaLinea192() {
        String r = Guardarrailes.respuestaPara(Guardarrailes.clasificar("quiero quitarme la vida"));
        assertTrue("incluye la línea 192", r.contains("192"));
    }

    @Test
    public void ocultaDatosPersonalesIgualQuePython() throws Exception {
        JSONArray red = ref.getJSONArray("redact");
        for (int i = 0; i < red.length(); i++) {
            JSONObject c = red.getJSONObject(i);
            assertEquals("oculta " + c.getString("texto"), c.getString("sale"), Guardarrailes.ocultarDatos(c.getString("texto")));
        }
    }
}
