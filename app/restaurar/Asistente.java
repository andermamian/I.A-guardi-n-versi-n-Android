package co.guardiania.app.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import co.guardiania.app.core.Hilos;
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
    }

    /** Cierre de la respuesta: fuentes, acciones sugeridas y de dónde salió. */
    public static final class Final {
        public String conversationId, proveedor = "";
        public JSONArray fuentes = new JSONArray(), acciones = new JSONArray(), archivosUsados = new JSONArray();
        public boolean buscoEnWeb, sinConexion, politicaSeguridad;
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

            // 3) Servidor en streaming.
            JSONObject cuerpo = new JSONObject();
            try {
                cuerpo.put("message", mensaje);
                if (conversationId != null) cuerpo.put("conversation_id", conversationId);
                cuerpo.put("allow_web", buscarWeb);
                cuerpo.put("crear_archivos", true);    // v2: el teléfono sí recibe los archivos creados
            } catch (Exception ignored) {}
            final boolean[] recibioTexto = {false};
            final Final[] fin = {null};
            try {
                sesion.stream("/assistant/chat/stream", cuerpo, e -> {
                    String tipo = e.optString("tipo");
                    switch (tipo) {
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
                }, cancelar);
            } catch (ApiException e) {
                Hilos.ui(() -> o.fallo(e.getMessage()));
                return;
            } catch (IOException e) {
                if (cancelar.get()) { Hilos.ui(() -> o.fallo("Detenido.")); return; }
                if (!recibioTexto[0]) {
                    // 4) Sin servidor: el teléfono responde con lo que puede verificar.
                    local(mensaje, nombreAsistente, t0, o, "");
                } else {
                    Hilos.ui(() -> o.fallo("La conexión se cortó a mitad de la respuesta."));
                }
                return;
            }
            if (cancelar.get()) { Hilos.ui(() -> o.fallo("Detenido.")); return; }
            Final f = fin[0];
            if (f == null) { Hilos.ui(() -> o.fallo("La respuesta se interrumpió. Intenta de nuevo.")); return; }
            if (fSospecha && !contieneSos(f.acciones)) {
                f.acciones.put(Guardarrailes.accion("confirmar", "accion", "activar_sos", "Avisar a mis contactos"));
            }
            Hilos.ui(() -> o.fin(f));
        });
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
