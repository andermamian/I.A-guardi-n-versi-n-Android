package co.guardiania.app.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.net.ApiClient;
import co.guardiania.app.net.ApiException;
import co.guardiania.app.net.Sesion;

/**
 * Orquesta una pregunta al asistente con las MISMAS reglas que el asistente de Python, y añade lo
 * que solo el teléfono puede hacer.
 *
 * <pre>
 *  mensaje ─► guardarraíles EN EL TELÉFONO ──(crisis/alarma/peligro)──► respuesta fija al instante
 *     │                                                                 (+ se guarda en el servidor)
 *     ├──► red de intención local (solo AÑADE sospecha: SOS si detecta emergencia)
 *     └──► servidor /assistant/chat/stream (SSE): web, adjuntos, archivos, modelo de lenguaje
 *              └── sin red ──► MotorLocal (enlaces, conocimiento verificado, honestidad)
 * </pre>
 *
 * Todos los eventos se entregan en el hilo de la interfaz.
 */
public final class Asistente {

    /** Lo que la pantalla del chat necesita saber mientras llega la respuesta. */
    public interface Oyente {
        void estado(String texto);                        // «Buscando en internet…», «Creando el archivo…»
        void texto(String trozo);                         // fragmento de respuesta
        void archivo(JSONObject archivoCreado);           // el asistente creó un Word/Excel/PDF…
        void aviso(String mensaje);                       // error no fatal
        void fin(Final f);                                // respuesta completa
        void fallo(String mensaje);                       // no hubo respuesta
        /** El servidor asignó (o confirmó) la conversación; puede llegar después de fin(). */
        default void conversacion(String id) {}
        /** El servidor genera la respuesta como un trabajo: con este id se reconecta o se detiene. */
        default void trabajo(String trabajoId) {}
    }

    /** Cierre de la respuesta: fuentes, acciones sugeridas y de dónde salió. */
    public static final class Final {
        public String conversationId, proveedor = "";
        public JSONArray fuentes = new JSONArray(), acciones = new JSONArray(), archivosUsados = new JSONArray();
        public boolean buscoEnWeb, sinConexion, politicaSeguridad, detenido;
        public int latenciaMs;
    }

    private final Context ctx;
    private final Sesion sesion;
    private final RedIntencion red;

    public Asistente(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sesion = Sesion.de(ctx);
        this.red = RedIntencion.de(ctx);
    }

    public RedIntencion red() { return red; }

    public void enviar(String mensaje, String conversationId, boolean buscarWeb, String nombreAsistente,
                       AtomicBoolean cancelar, Oyente o) {
        long t0 = System.currentTimeMillis();

        // 1) Guardarraíles locales: la respuesta segura no espera a la red.
        String categoria = Guardarrailes.clasificar(mensaje);
        if (categoria != null) {
            Final f = new Final();
            f.proveedor = "safety_policy";
            f.politicaSeguridad = true;
            f.acciones = Guardarrailes.accionesPara(categoria);
            f.conversationId = conversationId;
            f.latenciaMs = (int) (System.currentTimeMillis() - t0);
            Hilos.ui(() -> { o.texto(Guardarrailes.respuestaPara(categoria)); o.fin(f); });
            // Se registra también en el servidor (historial cifrado + escalamiento que el servidor decida).
            Hilos.fondo(() -> persistirEnSilencio(mensaje, conversationId, id -> Hilos.ui(() -> o.conversacion(id))));
            return;
        }

        Hilos.fondo(() -> {
            // 2) Red de intención local: solo puede AÑADIR la sugerencia de SOS, nunca quitar nada.
            boolean sospechaEmergencia = false;
            if (red != null) {
                RedIntencion.Prediccion p = red.predecir(mensaje);
                sospechaEmergencia = "emergencia".equals(p.intencion) && p.confianza >= 0.85;
            }
            final boolean fSospecha = sospechaEmergencia;

            if (!sesion.iniciada()) {
                local(mensaje, nombreAsistente, t0, o, "Inicia sesión para hablar con el asistente completo. Respondo desde el teléfono:\n\n");
                return;
            }

            // 3) Servidor en streaming (la respuesta es un «trabajo» que sigue aunque se corte la conexión).
            JSONObject cuerpo = new JSONObject();
            try {
                cuerpo.put("message", mensaje);
                if (conversationId != null) cuerpo.put("conversation_id", conversationId);
                cuerpo.put("allow_web", buscarWeb);
                cuerpo.put("crear_archivos", true);    // v2: el teléfono sí recibe los archivos creados
            } catch (Exception ignored) {}
            transmitir(cuerpo, null, 0, conversationId, t0, fSospecha, mensaje, nombreAsistente, cancelar, o);
        });
    }

    /**
     * Retoma una respuesta que el servidor sigue generando (tras girar la pantalla, cambiar de chat o
     * volver a la app). Entrega los eventos desde el principio para reconstruir el texto completo.
     */
    public void retomar(String trabajoId, String conversationId, AtomicBoolean cancelar, Oyente o) {
        Hilos.fondo(() -> transmitir(null, trabajoId, 0, conversationId, System.currentTimeMillis(), false, "", "",
                cancelar, o));
    }

    /** «Detener»: se le pide al servidor (cerrar la conexión ya no detiene la IA). */
    public boolean detener(String trabajoId) {
        try {
            sesion.obj("POST", "/assistant/chat/trabajos/" + trabajoId + "/detener", new JSONObject());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Respuestas en curso o recientes en el servidor (para retomarlas). */
    public JSONArray trabajos() throws IOException {
        return sesion.lista("/assistant/chat/trabajos");
    }

    private void transmitir(JSONObject cuerpo, String trabajoInicial, int desdeInicial, String conversationId, long t0,
                            boolean fSospecha, String mensaje, String nombreAsistente, AtomicBoolean cancelar, Oyente o) {
        final boolean[] recibioTexto = {false};
        final Final[] fin = {null};
        final String[] trabajo = {trabajoInicial};
        final int[] ultimo = {desdeInicial - 1};
        final boolean[] cancelado = {false};
        ApiClient.OyenteSse manejar = e -> {
            if (e.has("i")) ultimo[0] = Math.max(ultimo[0], e.optInt("i"));
            String tipo = e.optString("tipo");
            switch (tipo) {
                case "trabajo": {
                    trabajo[0] = e.optString("trabajo_id");
                    String id = trabajo[0], conv = e.optString("conversation_id");
                    Hilos.ui(() -> { o.trabajo(id); if (!conv.isEmpty()) o.conversacion(conv); });
                    break;
                }
                case "preparando":
                    if (e.optBoolean("busco_en_la_web")) Hilos.ui(() -> o.estado("Buscando en internet…"));
                    break;
                case "creando":
                    Hilos.ui(() -> o.estado("Creando el archivo (" + e.optString("formato").toUpperCase() + ")…"));
                    break;
                case "archivo":
                    Hilos.ui(() -> { o.archivo(e); o.estado("Escribiendo la explicación…"); });
                    break;
                case "archivos":
                    Hilos.ui(() -> o.estado("Leyendo tus archivos…"));
                    break;
                case "texto":
                    recibioTexto[0] = true;
                    String t = e.optString("texto");
                    Hilos.ui(() -> o.texto(t));
                    break;
                case "error":
                    String m = e.optString("mensaje");
                    Hilos.ui(() -> o.aviso(m));
                    break;
                case "cancelado":
                    cancelado[0] = true;
                    break;
                case "fin":
                    Final f = new Final();
                    f.conversationId = e.optString("conversation_id", conversationId);
                    f.proveedor = e.optString("proveedor");
                    f.buscoEnWeb = e.optBoolean("busco_en_web");
                    f.latenciaMs = e.optInt("latencia_ms", (int) (System.currentTimeMillis() - t0));
                    JSONArray fuentes = e.optJSONArray("fuentes");
                    JSONArray acciones = e.optJSONArray("acciones_sugeridas");
                    JSONArray usados = e.optJSONArray("archivos_usados");
                    if (fuentes != null) f.fuentes = fuentes;
                    if (acciones != null) f.acciones = acciones;
                    if (usados != null) f.archivosUsados = usados;
                    JSONObject seg = e.optJSONObject("seguridad");
                    f.politicaSeguridad = seg != null && seg.optBoolean("escalar");
                    fin[0] = f;
                    break;
                default:
                    break;
            }
        };

        boolean primera = cuerpo != null;
        int intentos = 0;
        while (true) {
            try {
                if (primera) sesion.stream("/assistant/chat/stream", cuerpo, manejar, cancelar);
                else sesion.stream("/assistant/chat/trabajos/" + trabajo[0] + "/stream?desde=" + (ultimo[0] + 1),
                        null, manejar, cancelar);
                if (fin[0] != null || cancelado[0] || cancelar.get()) break;
                if (trabajo[0] == null) break;                  // servidor antiguo sin trabajos
            } catch (ApiException e) {
                if (!primera && e.status == 404) break;         // el trabajo ya se olvidó: el historial lo tiene
                if (primera || trabajo[0] == null) { Hilos.ui(() -> o.fallo(e.getMessage())); return; }
            } catch (IOException e) {
                if (cancelar.get()) break;
                if (trabajo[0] == null) {
                    if (!recibioTexto[0] && cuerpo != null) local(mensaje, nombreAsistente, t0, o, "");  // 4) sin servidor
                    else Hilos.ui(() -> o.fallo("La conexión se cortó a mitad de la respuesta."));
                    return;
                }
            }
            primera = false;
            // La transmisión se cortó (red, app en segundo plano): la IA sigue en el servidor. Se reconecta.
            if (++intentos > 8) {
                Hilos.ui(() -> o.fallo("Se perdió la conexión. La respuesta seguirá guardándose y la verás en el historial."));
                return;
            }
            Hilos.ui(() -> o.estado("Reconectando…"));
            try { Thread.sleep(Math.min(10_000, 1000L * intentos)); } catch (InterruptedException ie) { return; }
        }
        if (cancelado[0] || (cancelar.get() && fin[0] == null)) {
            Final f = new Final();
            f.conversationId = conversationId;
            f.detenido = true;
            Hilos.ui(() -> o.fin(f));
            return;
        }
        Final f = fin[0];
        if (f == null) { Hilos.ui(() -> o.fallo("La respuesta se interrumpió. Intenta de nuevo.")); return; }
        if (fSospecha && !contieneSos(f.acciones)) {
            f.acciones.put(Guardarrailes.accion("confirmar", "accion", "activar_sos", "Avisar a mis contactos"));
        }
        Hilos.ui(() -> o.fin(f));
    }

    private void local(String mensaje, String nombre, long t0, Oyente o, String prefijo) {
        MotorLocal.Respuesta r = MotorLocal.responder(mensaje, nombre, red);
        Final f = new Final();
        f.proveedor = r.proveedor;
        f.sinConexion = true;
        f.acciones = r.acciones;
        f.latenciaMs = (int) (System.currentTimeMillis() - t0);
        Hilos.ui(() -> { o.texto(prefijo + r.texto); o.fin(f); });
    }

    private interface ConId { void id(String conversationId); }

    /** Envía el mensaje al servidor sin mostrar su respuesta (la fija ya se mostró). */
    private void persistirEnSilencio(String mensaje, String conversationId, ConId cb) {
        if (!sesion.iniciada()) return;
        try {
            JSONObject cuerpo = new JSONObject();
            cuerpo.put("message", mensaje);
            if (conversationId != null) cuerpo.put("conversation_id", conversationId);
            cuerpo.put("allow_web", false);
            cuerpo.put("crear_archivos", false);
            JSONObject r = sesion.obj("POST", "/assistant/chat", cuerpo);
            String id = r.optString("conversation_id", null);
            if (id != null) cb.id(id);
        } catch (Exception ignored) {
            // Sin red: la respuesta segura ya se mostró, que es lo que importa.
        }
    }

    private static boolean contieneSos(JSONArray acciones) {
        for (int i = 0; i < acciones.length(); i++) {
            JSONObject a = acciones.optJSONObject(i);
            if (a != null && "activar_sos".equals(a.optString("accion"))) return true;
        }
        return false;
    }

    /** Actualiza la red de intención desde el servidor si hay una versión nueva. Llamar en fondo. */
    public static void actualizarRed(Context ctx) {
        try {
            Sesion s = Sesion.de(ctx);
            if (!s.iniciada()) return;
            String json = s.llamar("GET", "/ml/intencion/movil", null).body;
            RedIntencion actual = RedIntencion.de(ctx);
            String nueva = new JSONObject(json).optString("version");
            if (actual == null || !nueva.equals(actual.version)) RedIntencion.instalar(ctx, json);
        } catch (Exception ignored) {
            // 409 = aún no hay red entrenada en el servidor; se sigue con la incluida en la app.
        }
    }
}
