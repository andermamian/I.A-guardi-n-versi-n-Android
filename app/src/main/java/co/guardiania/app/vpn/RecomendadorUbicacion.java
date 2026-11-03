package co.guardiania.app.vpn;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * GuardianIA elige la ubicación de ProtonVPN según para qué la necesita la persona, y explica por qué.
 * Lógica pura (probada en RecomendadorUbicacionTest).
 *
 * <ul>
 *   <li>{@code anonimato}: países fuera de las alianzas 5/9/14 Ojos y fuera de su propio país.</li>
 *   <li>{@code desbloquear}: la ubicación del país pedido (mejor si ya se confirmó al conectar).</li>
 *   <li>{@code general}: las ubicaciones confirmadas primero.</li>
 * </ul>
 */
public final class RecomendadorUbicacion {

    public static final String ANONIMATO = "anonimato", DESBLOQUEAR = "desbloquear", GENERAL = "general";

    private RecomendadorUbicacion() {}

    public static double puntaje(JSONObject u, String proposito, String paisUsuario, String paisDestino) {
        String pais = u.isNull("pais") ? null : u.optString("pais", null);
        boolean verif = u.optBoolean("verificado");
        // Tu servidor GuardianIA VPN: dedicado y sin registros. Gana en uso general; en anonimato, una ubicación
        // fuera de las alianzas de vigilancia le gana (tu servidor puede rastrearse hasta tu cuenta de la nube).
        double propio = u.optBoolean("propio") ? 50 : 0;
        double jur = Anonimato.jurisdiccion(pais).puntaje;
        switch (proposito) {
            case DESBLOQUEAR:
                if (pais != null && pais.equals(paisDestino)) return (verif ? 100 : 90) + propio / 10;
                return 10 + jur * 5;
            case ANONIMATO:
                return jur * 80 + (pais != null && !pais.equals(paisUsuario) ? 15 : 0) + (verif ? 5 : 0) + propio;
            default:
                return (verif ? 30 : 0) + (pais != null ? 20 : 0) + jur * 20 + propio;
        }
    }

    public static String razon(JSONObject u, String proposito, String paisUsuario, String paisDestino) {
        String pais = u.isNull("pais") ? null : u.optString("pais", null);
        Anonimato.Juicio j = Anonimato.jurisdiccion(pais);
        if (u.optBoolean("propio") && !DESBLOQUEAR.equals(proposito)) {
            return "Tu propio servidor GuardianIA VPN (" + Ubicaciones.nombre(pais) + "): sin registros y sin depender de terceros.";
        }
        switch (proposito) {
            case DESBLOQUEAR:
                return pais != null && pais.equals(paisDestino)
                        ? "Sales desde " + Ubicaciones.nombre(pais) + ": los sitios te ven en ese país."
                        : "No tienes una ubicación en " + Ubicaciones.nombre(paisDestino) + ": descarga su .conf en Proton.";
            case ANONIMATO:
                return "ninguna".equals(j.alianza)
                        ? Ubicaciones.nombre(pais) + " no pertenece a las alianzas de vigilancia 5/9/14 Ojos."
                        : Ubicaciones.nombre(pais) + (pais == null ? "" : " pertenece a los " + j.alianza) + ": aceptable, no ideal.";
            default:
                return u.optBoolean("verificado") ? "Ubicación ya confirmada al conectar." : "Ubicación disponible.";
        }
    }

    /** Ubicaciones de mejor a peor para ese propósito (orden estable ante empates). */
    public static List<JSONObject> ordenar(List<JSONObject> ubic, String proposito, String paisUsuario, String paisDestino) {
        List<JSONObject> r = new ArrayList<>(ubic);
        r.sort((a, b) -> Double.compare(puntaje(b, proposito, paisUsuario, paisDestino),
                puntaje(a, proposito, paisUsuario, paisDestino)));
        return r;
    }
}
