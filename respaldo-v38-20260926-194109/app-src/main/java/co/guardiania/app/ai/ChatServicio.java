package co.guardiania.app.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.data.Vinculo;
import co.guardiania.app.net.Sesion;
import co.guardiania.app.service.ServicioIA;

/**
 * El «cerebro» del chat: vive mientras viva la app, FUERA de la pantalla. La pantalla solo lo observa.
 *
 * <p>Antes la respuesta pertenecía a la pantalla: al girar el teléfono, cambiar de pestaña o de chat,
 * la pantalla se reconstruía y la respuesta se perdía. Ahora:
 * <ul>
 *   <li>La conversación y la respuesta en curso se guardan aquí; la pantalla se redibuja desde aquí.</li>
 *   <li>Mientras la IA escribe, {@link ServicioIA} (servicio en primer plano) evita que Android cierre
 *       la app si la persona se va a otra.</li>
 *   <li>El servidor genera la respuesta como un trabajo: si la red se corta, se reconecta donde quedó.</li>
 *   <li>Historial: se listan, abren y borran las conversaciones guardadas (cifradas) en el servidor.</li>
 * </ul>
 */
public final class ChatServicio {

    /** Un mensaje del chat tal como se dibuja. */
    public static final class Mensaje {
        public final String rol;                        // user | assistant
        public final StringBuilder texto = new StringBuilder();
        public String estado = "";                      // «Pensando…», «Buscando en internet…»
        public final List<JSONObject> archivos = new ArrayList<>();
        public Asistente.Final fin;
        public boolean enCurso, fallo;
        public String trabajoId;

        Mensaje(String rol, String texto) { this.rol = rol; this.texto.append(texto); }
    }

    public interface Observador { void cambio(); }

    private static ChatServicio instancia;
    private final Context ctx;
    private final Asistente asistente;
    private final List<Mensaje> mensajes = new CopyOnWriteArrayList<>();
    private final List<Observador> observadores = new CopyOnWriteArrayList<>();
    private String conversationId;
    private Mensaje enCurso;
    private AtomicBoolean cancelar;
    private long ultimoAviso = 0;
    private boolean avisoPendiente = false;

    public static synchronized ChatServicio de(Context c) {
        if (instancia == null) instancia = new ChatServicio(c.getApplicationContext());
        return instancia;
    }

    private ChatServicio(Context c) {
        ctx = c;
        asistente = new Asistente(c);
    }

    public List<Mensaje> mensajes() { return mensajes; }
    public String conversacion() { return conversationId; }
    public boolean escribiendo() { return enCurso != null; }

    public void observar(Observador o) { if (o != null && !observadores.contains(o)) observadores.add(o); }
    public void dejarDeObservar(Observador o) { observadores.remove(o); }

    /**
     * Avisa a la pantalla; mientras llega texto se limita a ~16 avisos por segundo para que el
     * Markdown no se recomponga con cada letra (eso era lo que hacía «tartamudear» el chat).
     */
    private void avisar(boolean ya) {
        long ahora = System.currentTimeMillis();
        if (!ya && ahora - ultimoAviso < 60) {
            if (!avisoPendiente) {
                avisoPendiente = true;
                Hilos.uiLuego(() -> { avisoPendiente = false; avisar(true); }, 60);
            }
            return;
        }
        ultimoAviso = ahora;
        for (Observador o : observadores) o.cambio();
    }

    // ─────────────────────────── conversación
    public void enviar(String texto) {
        if (enCurso != null || texto.trim().isEmpty()) return;
        mensajes.add(new Mensaje("user", texto));
        Mensaje r = new Mensaje("assistant", "");
        r.enCurso = true;
        r.estado = "Pensando…";
        mensajes.add(r);
        enCurso = r;
        cancelar = new AtomicBoolean(false);
        ServicioIA.iniciar(ctx);
        avisar(true);
        asistente.enviar(texto, conversationId, new Vinculo(ctx).buscarEnWeb(), "tu asistente", cancelar, oyentePara(r));
    }

    private Asistente.Oyente oyentePara(Mensaje r) {
        return new Asistente.Oyente() {
            @Override public void estado(String t) { r.estado = t; avisar(true); }
            @Override public void texto(String trozo) { r.texto.append(trozo); r.estado = ""; avisar(false); }
            @Override public void archivo(JSONObject a) { r.archivos.add(a); avisar(true); }
            @Override public void aviso(String m) { r.estado = m; avisar(true); }
            @Override public void trabajo(String id) { r.trabajoId = id; }
            @Override public void conversacion(String id) { if (conversationId == null && id != null && !id.isEmpty()) conversationId = id; }
            @Override public void fin(Asistente.Final f) {
                if (conversationId == null && f.conversationId != null) conversationId = f.conversationId;
                r.fin = f;
                if (f.detenido) r.estado = r.texto.length() > 0 ? "Detenido." : "Cancelado.";
                else if (r.texto.length() == 0 && r.archivos.isEmpty()) r.estado = "(sin respuesta)";
                else r.estado = "";
                terminar(r);
            }
            @Override public void fallo(String m) {
                r.fallo = r.texto.length() == 0;
                r.estado = m;
                terminar(r);
            }
        };
    }

    private void terminar(Mensaje r) {
        r.enCurso = false;
        if (enCurso == r) enCurso = null;
        avisar(true);
        ServicioIA.respuestaLista(ctx, r.texto.length() > 0 ? r.texto.toString() : r.estado);
    }

    public void detener() {
        Mensaje r = enCurso;
        if (r == null) return;
        r.estado = "Deteniendo…";
        avisar(true);
        String id = r.trabajoId;
        Hilos.fondo(() -> {
            // Se le pide al servidor; si no hay red, se corta la conexión local.
            if (id == null || !asistente.detener(id)) { AtomicBoolean c = cancelar; if (c != null) c.set(true); }
        });
    }

    public void nuevoChat() {
        if (enCurso != null) return;          // no se abandona una respuesta a medias sin querer
        conversationId = null;
        mensajes.clear();
        avisar(true);
    }

    /** Abre una conversación del historial y, si la IA seguía escribiendo en ella, la retoma. */
    public void abrir(String id, Hilos.Listo<Boolean> listo, Hilos.Fallo fallo) {
        if (enCurso != null) { fallo.error(new Exception("Espera a que termine la respuesta o detenla.")); return; }
        Hilos.<JSONArray[]>tarea(() -> new JSONArray[]{
                Sesion.de(ctx).lista("/assistant/conversations/" + id), asistente.trabajos()}, r -> {
            conversationId = id;
            mensajes.clear();
            JSONArray msgs = r[0];
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m != null) mensajes.add(new Mensaje(m.optString("role"), m.optString("content")));
            }
            // ¿La IA seguía escribiendo en esta conversación? Se retoma.
            for (int i = 0; i < r[1].length(); i++) {
                JSONObject t = r[1].optJSONObject(i);
                if (t != null && id.equals(t.optString("conversation_id")) && !t.optBoolean("terminado")) {
                    retomar(t.optString("trabajo_id"));
                    break;
                }
            }
            avisar(true);
            listo.ok(true);
        }, fallo);
    }

    private void retomar(String trabajoId) {
        Mensaje r = new Mensaje("assistant", "");
        r.enCurso = true;
        r.trabajoId = trabajoId;
        r.estado = "Retomando la respuesta…";
        mensajes.add(r);
        enCurso = r;
        cancelar = new AtomicBoolean(false);
        ServicioIA.iniciar(ctx);
        asistente.retomar(trabajoId, conversationId, cancelar, oyentePara(r));
    }

    /** Conversaciones guardadas (más recientes primero). */
    public void historial(Hilos.Listo<JSONArray> listo, Hilos.Fallo fallo) {
        Hilos.tarea(() -> Sesion.de(ctx).lista("/assistant/conversations?limit=50"), listo, fallo);
    }

    public void eliminar(String id, Runnable listo, Hilos.Fallo fallo) {
        Hilos.tarea(() -> Sesion.de(ctx).obj("DELETE", "/assistant/conversations/" + id, null), r -> {
            if (id.equals(conversationId) && enCurso == null) { conversationId = null; mensajes.clear(); avisar(true); }
            listo.run();
        }, fallo);
    }

    /** Un adjunto subido abre (o continúa) la conversación. */
    public void usarConversacion(String id) { if (conversationId == null && id != null && !id.isEmpty()) conversationId = id; }

    public void agregarNota(String textoAsistente) {
        mensajes.add(new Mensaje("assistant", textoAsistente));
        avisar(true);
    }
}
