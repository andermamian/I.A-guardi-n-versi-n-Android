package co.guardiania.app.seguridad;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Texto de los SMS de emergencia. Va SIN tildes ni «ñ» a propósito: así cabe en el alfabeto básico
 * de SMS (GSM-7), 160 caracteres por mensaje. Con una sola tilde el teléfono cambia a otra codificación
 * de 70 caracteres y el aviso se parte en 3 SMS, que a veces llegan desordenados o incompletos.
 */
public final class MensajeSos {

    public static final int MAX_SMS = 160;

    private MensajeSos() {}

    public static String sos(String nombre, Double lat, Double lon, Integer precisionM, String hora) {
        String n = limpio(nombre, 20);
        String ubic = lat != null && lon != null
                ? "Ubicacion: " + mapa(lat, lon)
                  + (precisionM != null ? " (+-" + Math.min(precisionM, 9999) + "m " + hora + ")" : " (" + hora + ")")
                : "Ubicacion no disponible (" + hora + ")";
        String texto = "SOS GuardianIA: " + n + " necesita ayuda. " + ubic + ". Llamale ya; si no responde, llama al 123";
        if (texto.length() > MAX_SMS) texto = "SOS GuardianIA: " + n + " necesita ayuda. " + ubic + ". Llama al 123";
        return texto;
    }

    /**
     * Enlace de Google Maps que se abre con UN toque. La coma entre latitud y longitud va codificada
     * (%2C): con la coma literal, la app de mensajes cortaba el enlace en dos y tomaba la longitud como
     * si fuera un número de teléfono. Google Maps lo interpreta igual.
     */
    public static String mapa(double lat, double lon) {
        return "https://maps.google.com/?q=" + String.format(Locale.ROOT, "%.5f%%2C%.5f", lat, lon);
    }

    /** Hora en formato 24 h («17:15»): «5:15 p. m.» se leía como otro enlace o como «p m». */
    public static String hora(java.util.Date d) {
        return new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(d);
    }

    /**
     * SOS activado desde el PC (por el dueño de la cuenta o un familiar con acceso): no sabemos si la
     * persona lo pidió, así que se dice «podria necesitar ayuda» y de dónde salió el aviso.
     */
    public static String sosRemoto(String nombre, Double lat, Double lon, Integer precisionM, String hora) {
        String n = limpio(nombre, 20);
        String enlace = lat != null && lon != null ? mapa(lat, lon) : null;
        String prec = " (" + (precisionM != null ? "+-" + Math.min(precisionM, 9999) + "m " : "") + hora + ")";
        String cab = "SOS GuardianIA: " + n + " podria necesitar ayuda (aviso de su cuenta). ";
        String[] versiones = enlace != null ? new String[]{
                cab + "Ubicacion: " + enlace + prec + ". Llamale o llama al 123",
                cab + "Ubicacion: " + enlace + prec + ". Llama al 123",
                cab + enlace + prec + ". Llama al 123",
                cab + enlace + " (" + hora + ")",
                cab + enlace}
                : new String[]{cab + "Ubicacion no disponible" + prec + ". Llamale o llama al 123"};
        for (String v : versiones) if (v.length() <= MAX_SMS) return v;
        return versiones[versiones.length - 1];
    }

    public static String estoyBien(String nombre) {
        return "GuardianIA: " + limpio(nombre, 20) + " confirma que ya esta bien. Gracias por estar pendiente.";
    }

    /** Quita tildes y cualquier carácter fuera del alfabeto básico de SMS. */
    static String limpio(String s, int max) {
        String t = s == null || s.trim().isEmpty() ? "Tu contacto" : s.trim();
        int esp = t.indexOf(' ');
        if (esp > 0) t = t.substring(0, esp);                      // solo el primer nombre
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('ñ', 'n').replace('Ñ', 'N');
        t = t.replaceAll("[^A-Za-z0-9 .'-]", "");
        if (t.isEmpty()) t = "Tu contacto";
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** ¿Todo el texto cabe en el alfabeto básico (un solo SMS de hasta 160)? */
    static boolean gsm7(String s) {
        return s.matches("[A-Za-z0-9 .,:;!?'()+/=_&%@#*\"-]*");
    }
}
