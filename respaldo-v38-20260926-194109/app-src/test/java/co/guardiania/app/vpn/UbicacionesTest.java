package co.guardiania.app.vpn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Detección de país igual que el PC y recomendación de ubicación de GuardianIA. */
public class UbicacionesTest {

    private static JSONArray ref() throws Exception {
        for (String r : new String[]{"src/test/resources/ref_ubicaciones.json", "app/src/test/resources/ref_ubicaciones.json"}) {
            if (Files.exists(Paths.get(r))) return new JSONArray(new String(Files.readAllBytes(Paths.get(r)), StandardCharsets.UTF_8));
        }
        return new JSONArray(new String(UbicacionesTest.class.getClassLoader().getResourceAsStream("ref_ubicaciones.json").readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    public void paisDesdeNombreIgualQuePython() throws Exception {
        JSONArray casos = ref();
        for (int i = 0; i < casos.length(); i++) {
            JSONObject c = casos.getJSONObject(i);
            String esperado = c.isNull("pais") ? null : c.getString("pais");
            assertEquals(c.getString("nombre"), esperado, Ubicaciones.paisDesdeNombre(c.getString("nombre")));
        }
    }

    private static JSONObject u(String id, String pais, boolean verif) throws Exception {
        return new JSONObject().put("id", id).put("nombre", "wg-" + pais + "-FREE").put("pais", pais == null ? JSONObject.NULL : pais)
                .put("verificado", verif);
    }

    @Test
    public void anonimatoPrefiereFueraDeLasAlianzasYDeTuPais() throws Exception {
        List<JSONObject> l = new ArrayList<>();
        l.add(u("a", "US", true)); l.add(u("b", "CO", true)); l.add(u("c", "CH", false)); l.add(u("d", "NL", true));
        List<JSONObject> r = RecomendadorUbicacion.ordenar(l, RecomendadorUbicacion.ANONIMATO, "CO", null);
        assertEquals("CH", r.get(0).getString("pais"));            // fuera de 5/9/14 Ojos y fuera de Colombia
        assertEquals("US", r.get(r.size() - 1).getString("pais"));  // 5 Ojos: al final
    }

    @Test
    public void desbloquearEligeElPaisPedido() throws Exception {
        List<JSONObject> l = new ArrayList<>();
        l.add(u("a", "US", false)); l.add(u("b", "JP", false)); l.add(u("c", "JP", true));
        List<JSONObject> r = RecomendadorUbicacion.ordenar(l, RecomendadorUbicacion.DESBLOQUEAR, "CO", "JP");
        assertEquals("c", r.get(0).getString("id"));               // Japón confirmado primero
        assertEquals("b", r.get(1).getString("id"));
        assertTrue(RecomendadorUbicacion.razon(r.get(0), RecomendadorUbicacion.DESBLOQUEAR, "CO", "JP").contains("Japón"));
        String sin = RecomendadorUbicacion.razon(u("x", "US", true), RecomendadorUbicacion.DESBLOQUEAR, "CO", "DE");
        assertTrue(sin, sin.contains("No tienes una ubicación en Alemania"));
    }

    @Test
    public void banderas() {
        assertEquals("🇺🇸", Ubicaciones.bandera("US"));
        assertEquals("🌐", Ubicaciones.bandera(null));
        assertEquals("🌐", Ubicaciones.bandera("us}; x"));
    }

    @Test
    public void servidorPropioGanaEnUsoGeneralPeroNoEnAnonimato() throws Exception {
        List<JSONObject> l = new ArrayList<>();
        l.add(u("a", "CH", true)); l.add(u("b", "NL", true));
        JSONObject mio = u("m", "US", false).put("propio", true);
        l.add(mio);
        assertEquals("m", RecomendadorUbicacion.ordenar(l, RecomendadorUbicacion.GENERAL, "CO", null).get(0).getString("id"));
        // Anonimato: Suiza (fuera de 5/9/14 Ojos) le gana a tu servidor en EE. UU. (rastreable hasta tu cuenta).
        assertEquals("a", RecomendadorUbicacion.ordenar(l, RecomendadorUbicacion.ANONIMATO, "CO", null).get(0).getString("id"));
        // Desbloquear un país: manda el país pedido.
        assertEquals("b", RecomendadorUbicacion.ordenar(l, RecomendadorUbicacion.DESBLOQUEAR, "CO", "NL").get(0).getString("id"));
        assertTrue(RecomendadorUbicacion.razon(mio, RecomendadorUbicacion.GENERAL, "CO", null).contains("Tu propio servidor"));
    }
}
