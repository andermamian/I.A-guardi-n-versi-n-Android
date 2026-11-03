package co.guardiania.app.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * El asistente cuando NO hay servidor (sin señal, PC apagado, fuera de la red de la casa).
 *
 * <p>Sigue el mismo orden que el modo local del servidor (_local_answer en assistant.py), porque es
 * el orden correcto: primero lo concreto que la persona preguntó (un enlace: se analiza aquí mismo),
 * luego la base de conocimiento verificada, y al final una respuesta honesta de lo que se puede y no
 * se puede hacer sin conexión. <b>Nunca inventa</b>: prefiere decir «sin conexión no puedo» a
 * improvisar una respuesta que parezca cierta.
 *
 * <p>Lo que añade el teléfono: la red de intención decide qué pantalla de la app sugerir, aunque el
 * mensaje venga con errores de escritura.
 */
public final class MotorLocal {

    private MotorLocal() {}

    /** Respuesta completa, en el mismo formato que el evento «fin» del servidor. */
    public static final class Respuesta {
        public final String texto;
        public final JSONArray acciones;
        public final String proveedor;
        Respuesta(String texto, JSONArray acciones, String proveedor) {
            this.texto = texto; this.acciones = acciones; this.proveedor = proveedor;
        }
    }

    // Base de conocimiento: mismas respuestas verificadas que el servidor (CONOCIMIENTO).
    static final String[][] CONOCIMIENTO = {
            {"phishing|suplanta|correo falso|mensaje falso|estafa",
                    "El phishing es un mensaje que finge ser de una entidad conocida (su banco, la DIAN, una "
                    + "encomienda) para que usted entregue claves o dinero. Señales: urgencia («su cuenta será "
                    + "bloqueada hoy»), un enlace con un dominio parecido pero no igual, y que le pidan datos que la "
                    + "entidad ya tiene. Qué hacer: no toque el enlace, entre escribiendo usted mismo la dirección "
                    + "oficial, y si ya entregó la clave, cámbiela de inmediato. Puede pegarme el enlace y lo reviso."},
            {"contrasena|clave segura|password",
                    "Use una contraseña distinta por servicio, de 12 caracteres o más; una frase de cuatro palabras "
                    + "es más segura y fácil de recordar que «Abc123*». Active la verificación en dos pasos donde "
                    + "pueda, empezando por su correo: quien controla el correo recupera todo lo demás."},
            {"dos pasos|2fa|autenticad|codigo de 6",
                    "La verificación en dos pasos pide, además de la contraseña, un código de su celular. Prefiera "
                    + "una app autenticadora (Aegis, Google Authenticator) antes que SMS: los mensajes se pueden "
                    + "desviar duplicando la SIM. Guarde los códigos de recuperación fuera del celular."},
            {"robaron|me robaron|perdi.*(celular|telefono)|extravi",
                    "Actúe en este orden: 1) marque el equipo como robado en Dispositivos (en el PC o en la web), así "
                    + "se bloquea y reporta ubicación; 2) llame a su operador para bloquear la línea y el IMEI; "
                    + "3) denuncie en adenunciar.policia.gov.co o en la línea 123; 4) cambie la contraseña de su "
                    + "correo. No vaya a recuperarlo por su cuenta."},
            {"virus|malware|troyano|archivo sospechoso|apk",
                    "No abra el archivo. En Seguridad → «Analizar un archivo» calculo su huella y lo reviso con "
                    + "firmas, reglas y un modelo, sin subir el archivo. Instale apps solo desde Play Store y "
                    + "desconfíe de los APK enviados por WhatsApp. «Analizar mis apps» revisa los permisos de lo "
                    + "que ya tiene instalado."},
            {"wifi|red publica|vpn",
                    "En una red WiFi pública evite entrar al banco. Si debe hacerlo, use una VPN confiable: cifra "
                    + "el tráfico para que quien esté en la misma red no lo vea. Desactive la conexión automática a "
                    + "redes abiertas."},
            {"signos vitales|pulso|ritmo cardiaco|oxigen|reloj",
                    "GuardianIA ya no mide signos vitales ni se conecta a relojes. Si le preocupa su pulso, "
                    + "consulte a un profesional de salud; si se siente mal ahora (dolor en el pecho, falta de aire, "
                    + "desmayo), llame al 123. El botón SOS avisa a sus contactos con su ubicación."},
            {"emergencia|sos|boton de panico|contactos",
                    "El botón SOS de Inicio avisa a sus contactos con su ubicación; se mantiene presionado dos "
                    + "segundos para que no se dispare en el bolsillo. Registre al menos dos contactos en "
                    + "Contactos de emergencia y pruébelos hoy: un contacto que no contesta no sirve el día que importa."},
            {"que puedes hacer|ayuda|para que sirves|opciones",
                    "Con conexión respondo de cualquier tema, escribo código, busco en internet con fuentes y creo "
                    + "archivos (Word, Excel, PowerPoint, PDF…). Sin conexión, aquí mismo en el teléfono, reviso "
                    + "enlaces sospechosos, detecto emergencias y le oriento con seguridad digital, antirrobo y privacidad."},
    };

    public static Respuesta responder(String mensaje, String nombreAsistente, RedIntencion red) {
        String f = Texto.plegar(mensaje);
        JSONArray acciones = new JSONArray();

        // 1) Enlaces: se analizan aquí mismo, sin enviarlos a ningún lado.
        List<String> enlaces = RiesgoUrl.enlacesEn(mensaje);
        StringBuilder sobreEnlaces = new StringBuilder();
        for (String u : enlaces) {
            try {
                RiesgoUrl.Veredicto v = RiesgoUrl.evaluar(u);
                acciones.put(accionUrl(v));
                sobreEnlaces.append("• ").append(v.host).append(" ").append(v.etiqueta().toLowerCase());
                if (!v.motivos.isEmpty()) sobreEnlaces.append(" (").append(v.motivos.get(0).motivo.toLowerCase()).append(")");
                sobreEnlaces.append('\n');
            } catch (IllegalArgumentException ignored) {}
        }
        sugerir(f, red, mensaje, acciones);
        if (sobreEnlaces.length() > 0) {
            return new Respuesta("Revisé el enlace:\n" + sobreEnlaces + "Nunca ingrese claves desde enlaces recibidos por mensaje.",
                    acciones, "telefono");
        }

        // 2) Conocimiento verificado.
        for (String[] par : CONOCIMIENTO) {
            if (Texto.busca(par[0], f)) return new Respuesta(par[1], acciones, "telefono");
        }

        // 3) Saludo.
        if (Texto.busca("hola|buen(os|as)|saludos", f)) {
            return new Respuesta("Hola, soy " + nombreAsistente + ". Ahora estoy sin conexión con el servidor, pero "
                    + "aquí en el teléfono puedo revisar enlaces, orientarle en una emergencia y explicarle cómo "
                    + "proteger sus cuentas y su celular. ¿Con qué le ayudo?", acciones, "telefono");
        }

        // 4) Honestidad: qué haría con conexión, según lo que entendió la red.
        String intencion = red != null ? red.predecir(mensaje).intencion : "conocimiento";
        String extra;
        switch (intencion) {
            case "crear_archivo": extra = "Crear ese archivo necesita el servidor: en cuanto vuelva la conexión, pídamelo de nuevo y se lo entrego aquí mismo."; break;
            case "codigo": extra = "Escribir o revisar código necesita el modelo de lenguaje del servidor."; break;
            case "salud": extra = "Si tiene síntomas de alarma (dolor en el pecho, falta de aire, desmayo), llame al 123."; break;
            default: extra = "Para conversación abierta necesito el modelo de lenguaje del servidor.";
        }
        return new Respuesta("Estoy sin conexión con el servidor de GuardianIA y prefiero no inventar una respuesta. "
                + extra + "\n\nMientras tanto sí puedo: revisar enlaces sospechosos (péguelos aquí), orientarle ante "
                + "un robo o una emergencia, y darle consejos de contraseñas, phishing y WiFi.", acciones, "telefono");
    }

    /** Acciones sugeridas (mismas que _suggest del servidor) + la pantalla que indica la red. */
    static void sugerir(String f, RedIntencion red, String mensaje, JSONArray acciones) {
        if (Texto.busca("escane|analiz(a|ar) (mis )?apps|virus|malware", f)) {
            acciones.put(Guardarrailes.accion("confirmar", "accion", "escanear_aplicaciones", "Escanear mis apps"));
        }
        if (Texto.busca("perdi|robaron|no encuentro (mi|el) (celular|telefono)", f)) {
            acciones.put(Guardarrailes.accion("confirmar", "accion", "modo_perdido", "Ver mi teléfono en el mapa"));
        }
        if (Texto.busca("contrasena|clave", f)) {
            acciones.put(Guardarrailes.accion("confirmar", "accion", "generar_contrasena", "Generar contraseña segura"));
        }
        if (red != null && acciones.length() == 0) {
            RedIntencion.Prediccion p = red.predecir(mensaje);
            if (p.confianza >= 0.7) {
                if ("salud".equals(p.intencion)) acciones.put(Guardarrailes.accion("llamar", "numero", "123", "Llamar al 123"));
                if ("dispositivo".equals(p.intencion)) acciones.put(Guardarrailes.accion("confirmar", "accion", "modo_perdido", "Ver mi teléfono en el mapa"));
                if ("seguridad".equals(p.intencion)) acciones.put(Guardarrailes.accion("abrir", "pantalla", "seguridad", "Abrir Seguridad"));
            }
        }
    }

    static JSONObject accionUrl(RiesgoUrl.Veredicto v) {
        JSONObject o = new JSONObject();
        try {
            o.put("tipo", "resultado_url");
            o.put("host", v.host);
            o.put("veredicto", v.veredicto);
            o.put("puntaje", v.puntaje);
            JSONArray m = new JSONArray();
            for (int i = 0; i < Math.min(3, v.motivos.size()); i++) m.put(v.motivos.get(i).motivo);
            o.put("motivos", m);
        } catch (Exception ignored) {}
        return o;
    }
}
