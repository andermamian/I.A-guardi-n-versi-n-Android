package co.guardiania.app.vpn;

import java.util.LinkedHashMap;
import java.util.Map;

/** Nombres y banderas de países (salida de su servidor y de Tor). Mismos nombres que el PC. */
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

    private Ubicaciones() {}

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
