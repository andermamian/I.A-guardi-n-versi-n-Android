package co.guardiania.app.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Barreras de seguridad del asistente, ejecutadas DENTRO del teléfono. Es una copia fiel de las del
 * servidor (app/ai/assistant.py: classify, redact y las respuestas fijas), con dos propósitos:
 *
 * <ol>
 *   <li><b>Crisis sin esperar a la red.</b> Si alguien escribe que quiere hacerse daño o describe un
 *       infarto, la respuesta humana con el 192/123 aparece al instante, aunque no haya señal o el
 *       modelo del PC tarde un minuto en contestar. En ese momento cada segundo cuenta.</li>
 *   <li><b>Privacidad.</b> Los datos personales se ocultan antes de guardarlos o mostrarlos en el
 *       modo sin conexión, igual que el servidor los oculta antes de hablar con el modelo.</li>
 * </ol>
 *
 * <p>Principio de diseño (el mismo del servidor): estas respuestas son FIJAS, escritas por humanos.
 * Un modelo generativo nunca improvisa en una crisis. La red neuronal de intención puede añadir
 * sospecha, pero nunca quitarla.
 */
public final class Guardarrailes {

    private Guardarrailes() {}

    static final String[] CRISIS = {
            "\\b(quiero|voy a|pienso en|ganas de) (morir|matarme|suicidarme|quitarme la vida|desaparecer)\\b",
            "\\bsuicid", "\\bno (quiero|vale la pena) (vivir|seguir)\\b", "\\b(hacerme|me hago) dano\\b",
            "\\bcortarme\\b", "\\bterminar con (todo|mi vida)\\b",
    };
    static final String[] ALARMA_MEDICA = {
            "dolor (en el|de) pecho", "no puedo respirar", "me falta el aire", "me desmay", "perdi el conocimiento",
            "cara (caida|torcida)", "no puedo mover (el|la|un|una)", "hablo raro|no puedo hablar", "convulsi",
            "sangrado (abundante|que no para)",
    };
    static final String[] PELIGRO = {
            "me (estan|esta) (siguiendo|robando|atacando)", "(un|hay un) ladron", "me quieren (hacer|robar)",
    };

    public static final String RESPUESTA_CRISIS =
            "Gracias por contármelo. Lo que sientes importa y no tienes que pasarlo solo. "
            + "Ahora mismo puedes hablar con alguien: en Colombia la línea 192 opción 4 del Ministerio de Salud "
            + "ofrece apoyo emocional gratuito (en Bogotá también la Línea 106), y si estás en peligro inmediato "
            + "llama al 123. "
            + "Si quieres, puedo avisar a uno de tus contactos de confianza para que te acompañe. "
            + "¿Estás en un lugar seguro en este momento?";
    public static final String RESPUESTA_MEDICA =
            "Lo que describes puede ser una emergencia. Llama al 123 ahora o pide a alguien cercano que lo haga. "
            + "Si puedes, siéntate o recuéstate y no conduzcas. ¿Quieres que active tu alerta de emergencia para avisar "
            + "a tus contactos con tu ubicación?";
    public static final String RESPUESTA_PELIGRO =
            "Tu seguridad es lo primero. Si puedes, ve a un lugar con gente o iluminado y llama al 123. "
            + "Puedo activar tu alerta SOS para enviar tu ubicación a tus contactos. ¿La activo?";

    /** Categoría de riesgo del mensaje: "crisis", "medical_emergency", "personal_danger" o null. */
    public static String clasificar(String texto) {
        String f = Texto.plegar(texto);
        for (String p : CRISIS) if (Texto.busca(p, f)) return "crisis";
        for (String p : ALARMA_MEDICA) if (Texto.busca(p, f)) return "medical_emergency";
        for (String p : PELIGRO) if (Texto.busca(p, f)) return "personal_danger";
        return null;
    }

    public static String respuestaPara(String categoria) {
        switch (categoria) {
            case "crisis": return RESPUESTA_CRISIS;
            case "medical_emergency": return RESPUESTA_MEDICA;
            default: return RESPUESTA_PELIGRO;
        }
    }

    /** Acciones que acompañan la respuesta fija, en el mismo formato que el servidor. */
    public static JSONArray accionesPara(String categoria) {
        JSONArray a = new JSONArray();
        if ("crisis".equals(categoria)) a.put(accion("llamar", "numero", "192", "Llamar a la línea 192 (opción 4)"));
        else a.put(accion("llamar", "numero", "123", "Llamar al 123"));
        a.put(accion("confirmar", "accion", "activar_sos", "Avisar a mis contactos"));
        return a;
    }

    static JSONObject accion(String tipo, String clave, String valor, String etiqueta) {
        JSONObject o = new JSONObject();
        try {
            o.put("tipo", tipo);
            o.put(clave, valor);
            o.put("etiqueta", etiqueta);
        } catch (Exception ignored) {}
        return o;
    }

    // ─────────────────────────── datos personales (mismos patrones que el servidor)
    private static final Pattern[] PII = {
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+"),
            Pattern.compile("(?<!\\d)(?:\\+?57[\\s-]?)?3\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{4}(?!\\d)"),
            Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)"),
            Pattern.compile("(?<!\\d)\\d{1,3}(?:\\.\\d{3}){2,3}(?!\\d)|(?<!\\d)\\d{6,10}(?!\\d)"),
    };
    private static final String[] MARCA = {"[correo]", "[teléfono]", "[tarjeta]", "[documento]"};

    /** Oculta correo, celular, tarjeta y cédula. Devuelve el texto limpio. */
    public static String ocultarDatos(String texto) {
        String t = texto;
        for (int i = 0; i < PII.length; i++) {
            Matcher m = PII[i].matcher(t);
            t = m.replaceAll(Matcher.quoteReplacement(MARCA[i]));
        }
        return t;
    }
}
