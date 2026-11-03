package co.guardiania.app.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * La red de intención del teléfono debe dar EXACTAMENTE lo mismo que la de Python
 * (app/ml/asistente.py). ref_intencion.json lo genera el backend con el mismo modelo exportado.
 */
public class RedIntencionTest {

    private static RedIntencion red;
    private static JSONArray ref;

    @BeforeClass
    public static void cargar() throws Exception {
        red = new RedIntencion(Recursos.leer("src/main/assets/intencion.json", "app/src/main/assets/intencion.json"));
        ref = new JSONArray(Recursos.leer("ref_intencion.json", "src/test/resources/ref_intencion.json",
                "app/src/test/resources/ref_intencion.json"));
    }

    @Test
    public void normalizaIgualQuePython() throws Exception {
        for (int i = 0; i < ref.length(); i++) {
            JSONObject r = ref.getJSONObject(i);
            assertEquals("normalizar(" + r.getString("texto") + ")", r.getString("norm"), Texto.normalizar(r.getString("texto")));
        }
    }

    @Test
    public void vectorizaIgualQuePython() throws Exception {
        for (int i = 0; i < ref.length(); i++) {
            JSONObject r = ref.getJSONObject(i);
            double suma = 0;
            for (double x : red.vectorizar(r.getString("texto"))) suma += x;
            assertEquals("vector de " + r.getString("texto"), r.getDouble("vec_sum"), suma, 1e-9);
        }
    }

    @Test
    public void prediceIgualQuePython() throws Exception {
        for (int i = 0; i < ref.length(); i++) {
            JSONObject r = ref.getJSONObject(i);
            RedIntencion.Prediccion p = red.predecir(r.getString("texto"));
            assertEquals("intención de " + r.getString("texto"), r.getString("intencion"), p.intencion);
            assertEquals("confianza de " + r.getString("texto"), r.getDouble("conf"), p.confianza, 1e-6);
        }
    }

    @Test
    public void textoVacioNoRompe() throws Exception {
        RedIntencion.Prediccion p = red.predecir("   ");
        assertTrue("devuelve una intención válida", p.intencion != null && !p.intencion.isEmpty());
    }
}
