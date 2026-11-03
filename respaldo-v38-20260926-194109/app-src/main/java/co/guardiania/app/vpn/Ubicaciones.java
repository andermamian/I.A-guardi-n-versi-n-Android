package co.guardiania.app.vpn;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Países de las ubicaciones VPN y detección por el nombre del archivo. Copia exacta de
 * pais_desde_nombre()/PAISES_VPN de app/local/vpn.py (lo comprueba UbicacionesTest).
 */
public final class Ubicaciones {

    public static final Map<String, String> PAISES = new LinkedHashMap<>();
    static {
        String[] d = {"US", "Estados Unidos", "CA", "Canadá", "MX", "México", "BR", "Brasil", "AR", "Argentina",
                "CL", "Chile", "CO", "Colombia", "PE", "Perú", "ES", "España", "GB", "Reino Unido", "UK", "Reino Unido",
                "FR", "Francia", "DE", "Alemania", "NL", "Países Bajos", "BE", "Bélgica", "CH", "Suiza", "SE", "Suecia",
                "NO", "Noruega", "FI", "Finlandia", "DK", "Dinamarca", "IT", "Italia", "PL", "Polonia", "RO", "Rumania",
                "AT", "Austria", "IE", "Irlanda", "PT", "Portugal", "CZ", "Chequia", "IS", "Islandia", "JP", "Japón",
                "KR", "Corea del Sur", "SG", "Singapur", "HK", "Hong Kong", "TW", "Taiwán", "IN", "India",
                "AU", "Australia", "NZ", "Nueva Zelanda", "ZA", "Sudáfrica", "IL", "Israel", "TR", "Turquía",
                "AE", "Emiratos Árabes"};
        for (int i = 0; i < d.length; i += 2) PAISES.put(d[i], d[i + 1]);
    }

    /** Países de salida de Tor (lista cerrada, igual que PAISES de app/local/tor.py). */
    public static final String[] PAISES_TOR = {"US", "CA", "MX", "BR", "AR", "CL", "ES", "GB", "FR", "DE", "NL",
            "CH", "SE", "IT", "PL", "RO", "JP", "SG", "AU", "IN"};

    private static final Pattern NO_LETRAS = Pattern.compile("[^A-Za-z]+");

    private Ubicaciones() {}

    /** «wg-US-FREE-12» → US; «uk-free-2» → GB; sin país claro → null. */
    public static String paisDesdeNombre(String nombre) {
        if (nombre == null) return null;
        for (String t : NO_LETRAS.split(nombre)) {
            if (t.length() != 2) continue;
            String c = t.toUpperCase(Locale.ROOT);
            if (PAISES.containsKey(c)) return "UK".equals(c) ? "GB" : c;
        }
        return null;
    }

    public static String nombre(String pais) {
        String n = pais == null ? null : PAISES.get(pais);
        return n != null ? n : "País desconocido";
    }

    /** Bandera con los indicadores regionales de Unicode («US» → 🇺🇸). */
    public static String bandera(String pais) {
        if (pais == null || !pais.matches("[A-Z]{2}")) return "🌐";
        StringBuilder b = new StringBuilder();
        for (char c : pais.toCharArray()) b.appendCodePoint(0x1F1E6 + (c - 'A'));
        return b.toString();
    }
}
