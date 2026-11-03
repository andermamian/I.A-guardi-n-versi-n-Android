package co.guardiania.app.seguridad;

import java.security.SecureRandom;

/**
 * Generador de contraseñas en el teléfono (acción «Generar contraseña segura» del asistente).
 * SecureRandom del sistema, 20 caracteres, sin caracteres ambiguos (0/O, 1/l/I) para que se pueda
 * dictar o copiar a mano sin errores. Nunca sale del teléfono salvo que la persona la copie.
 */
public final class Contrasenas {

    private static final String MINUSCULAS = "abcdefghijkmnpqrstuvwxyz";
    private static final String MAYUSCULAS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITOS = "23456789";
    private static final String SIMBOLOS = "!#$%&*+-=?@_";
    private static final SecureRandom AZAR = new SecureRandom();

    private Contrasenas() {}

    public static String generar(int largo) {
        String todos = MINUSCULAS + MAYUSCULAS + DIGITOS + SIMBOLOS;
        char[] c = new char[Math.max(12, largo)];
        // Al menos uno de cada clase, en posiciones al azar.
        c[0] = uno(MINUSCULAS); c[1] = uno(MAYUSCULAS); c[2] = uno(DIGITOS); c[3] = uno(SIMBOLOS);
        for (int i = 4; i < c.length; i++) c[i] = uno(todos);
        for (int i = c.length - 1; i > 0; i--) {                 // Fisher–Yates
            int j = AZAR.nextInt(i + 1);
            char t = c[i]; c[i] = c[j]; c[j] = t;
        }
        return new String(c);
    }

    private static char uno(String s) { return s.charAt(AZAR.nextInt(s.length())); }
}
