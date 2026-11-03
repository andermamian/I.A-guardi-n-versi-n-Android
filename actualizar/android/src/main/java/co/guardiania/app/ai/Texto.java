package co.guardiania.app.ai;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalización de texto idéntica a la del servidor Python (_fold / normalizar). */
public final class Texto {

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern NO_ALFANUM = Pattern.compile("[^a-z0-9ñ ]");
    private static final Pattern ESPACIOS = Pattern.compile("\\s+");

    private Texto() {}

    /** Minúsculas y sin tildes: «Contraseña» → «contrasena». Igual a assistant._fold(). */
    public static String plegar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        return MARCAS.matcher(n).replaceAll("");
    }

    /** Además quita signos y espacios repetidos. Igual a ml/asistente.normalizar(). */
    public static String normalizar(String s) {
        String p = plegar(s);
        p = NO_ALFANUM.matcher(p).replaceAll(" ");
        return ESPACIOS.matcher(p).replaceAll(" ").trim();
    }

    public static boolean busca(String patron, String texto) {
        return Pattern.compile(patron).matcher(texto).find();
    }
}
