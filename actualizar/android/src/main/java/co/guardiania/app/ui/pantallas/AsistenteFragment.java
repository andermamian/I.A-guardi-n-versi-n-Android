package co.guardiania.app.ui.pantallas;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import co.guardiania.app.ai.Asistente;
import co.guardiania.app.ai.ChatServicio;
import co.guardiania.app.net.Sesion;
import co.guardiania.app.ui.MainActivity;
import co.guardiania.app.ui.kit.Markdown;
import co.guardiania.app.ui.kit.Ui;

/**
 * Pestaña del asistente. Solo DIBUJA: la conversación y la respuesta en curso viven en
 * {@link ChatServicio}. Por eso girar la pantalla, cambiar de pestaña o de app no corta la respuesta:
 * al volver, la pantalla se redibuja desde el servicio y sigue mostrando lo que la IA escribe.
 *
 * <p>Detalles de diseño: la burbuja de la IA ocupa el ancho disponible (antes quedaba estrecha y el
 * texto se partía palabra por palabra); la de la persona llega al 85 %. Mientras la IA piensa se ven
 * tres puntos animados; cada mensaje entra con un deslizamiento corto. La vista solo baja sola si la
 * persona está al final (si subió a leer, no se la arrastra).
 */
public class AsistenteFragment implements ChatServicio.Observador {

    private final MainActivity a;
    private final ChatServicio chat;
    private ScrollView scroll;
    private LinearLayout log;
    private EditText entrada;
    private View enviarBtn, detenerBtn;
    private TextView titulo;
    /** Vista ya dibujada de cada mensaje (para actualizar solo la que cambia). */
    private final Map<ChatServicio.Mensaje, LinearLayout> dibujados = new HashMap<>();
    private final Map<ChatServicio.Mensaje, String> firmas = new HashMap<>();

    public AsistenteFragment(MainActivity a) {
        this.a = a;
        this.chat = ChatServicio.de(a);
    }

    public View crear() {
        LinearLayout raiz = Ui.columna(a);
        raiz.setBackgroundColor(Ui.FONDO);

        // Barra: título + nuevo chat + historial.
        LinearLayout barra = Ui.fila(a);
        int pb = Ui.dp(a, 12);
        barra.setPadding(Ui.dp(a, 16), pb / 2, pb / 2, pb / 2);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        titulo = Ui.subtitulo(a, "Asistente");
        barra.addView(titulo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View nuevo = Ui.botonIcono(a, co.guardiania.app.R.drawable.ic_mas, "Nuevo chat", android.graphics.Color.TRANSPARENT, Ui.TEXTO2);
        nuevo.setOnClickListener(v -> {
            if (chat.escribiendo()) { Ui.aviso(a, "Espera a que termine la respuesta o detenla."); return; }
            chat.nuevoChat();
        });
        barra.addView(nuevo);
        View hist = Ui.botonIcono(a, co.guardiania.app.R.drawable.ic_historial, "Historial", android.graphics.Color.TRANSPARENT, Ui.TEXTO2);
        hist.setOnClickListener(v -> mostrarHistorial());
        barra.addView(hist);
        raiz.addView(barra);

        if (!Sesion.de(a).iniciada()) {
            LinearLayout aviso = Ui.tarjeta(a);
            aviso.addView(Ui.pequeno(a, "Inicia sesión para el asistente completo (internet, código, documentos) y para "
                    + "guardar tu historial. Sin sesión respondo desde el teléfono."));
            Ui.en(aviso, Ui.botonChico(a, "Iniciar sesión", Ui.Estilo.PRIMARIO)).setOnClickListener(v -> a.pedirSesion());
            raiz.addView(aviso);
        }

        log = Ui.columna(a);
        int pad = Ui.dp(a, 14);
        log.setPadding(pad, pad / 2, pad, pad);
        scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.addView(log);
        raiz.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Barra de entrada.
        LinearLayout barraEntrada = Ui.fila(a);
        barraEntrada.setBackgroundColor(Ui.TARJETA);
        int p = Ui.dp(a, 10);
        barraEntrada.setPadding(p, p, p, p);
        barraEntrada.setGravity(Gravity.BOTTOM);
        entrada = Ui.campo(a, "Escribe tu pregunta", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        entrada.setMaxLines(5);
        barraEntrada.addView(Ui.ancho(entrada, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View adjuntar = Ui.botonIcono(a, co.guardiania.app.R.drawable.ic_adjuntar, "Adjuntar archivo", android.graphics.Color.TRANSPARENT, Ui.TEXTO2);
        adjuntar.setOnClickListener(v -> a.elegirAdjunto(chat::usarConversacion));
        barraEntrada.addView(adjuntar);
        detenerBtn = Ui.botonIcono(a, co.guardiania.app.R.drawable.ic_detener, "Detener respuesta", Ui.PELIGRO, android.graphics.Color.WHITE);
        detenerBtn.setOnClickListener(v -> chat.detener());
        barraEntrada.addView(detenerBtn);
        enviarBtn = Ui.botonIcono(a, co.guardiania.app.R.drawable.ic_enviar, "Enviar", Ui.ACENTO, Ui.ACENTO_TEXTO);
        enviarBtn.setOnClickListener(v -> enviar());
        barraEntrada.addView(enviarBtn);
        raiz.addView(barraEntrada);

        chat.observar(this);
        redibujarTodo();
        return raiz;
    }

    /** La pantalla se destruye (rotación, cierre): deja de observar para no filtrar memoria. */
    public void destruir() { chat.dejarDeObservar(this); }

    private void enviar() {
        String texto = entrada.getText().toString().trim();
        if (texto.isEmpty() || chat.escribiendo()) return;
        entrada.setText("");
        chat.enviar(texto);
        bajar(true);
    }

    // ─────────────────────────── dibujo
    @Override
    public void cambio() {
        boolean alFinal = cercaDelFinal();
        // Si cambió la lista (nuevo chat, historial), se redibuja entera; si no, solo lo que cambió.
        if (chat.mensajes().size() < dibujados.size() || !contieneTodos()) { redibujarTodo(); return; }
        for (ChatServicio.Mensaje m : chat.mensajes()) {
            LinearLayout v = dibujados.get(m);
            if (v == null) { agregar(m, true); continue; }
            String firma = firma(m);
            if (!firma.equals(firmas.get(m))) { rellenar(v, m); firmas.put(m, firma); }
        }
        estadoBotones();
        if (alFinal) bajar(false);
    }

    private boolean contieneTodos() {
        for (ChatServicio.Mensaje m : dibujados.keySet()) if (!chat.mensajes().contains(m)) return false;
        return true;
    }

    private void redibujarTodo() {
        log.removeAllViews();
        dibujados.clear();
        firmas.clear();
        if (chat.mensajes().isEmpty()) {
            LinearLayout vacio = Ui.columna(a);
            vacio.setGravity(Gravity.CENTER_HORIZONTAL);
            vacio.setPadding(0, Ui.dp(a, 40), 0, 0);
            vacio.addView(Ui.icono(a, co.guardiania.app.R.drawable.ic_chat, Ui.ACENTO, 44));
            TextView t = Ui.subtitulo(a, "Hola, ¿en qué te ayudo?");
            t.setGravity(Gravity.CENTER);
            Ui.margen(t, 0, 12, 0, 6);
            vacio.addView(t);
            TextView s = Ui.pequeno(a, "Reviso enlaces, escribo código, busco en internet con fuentes, creo documentos "
                    + "y te ayudo con la seguridad y la privacidad de tu teléfono.");
            s.setGravity(Gravity.CENTER);
            vacio.addView(s);
            log.addView(vacio);
            Ui.entrar(vacio);
        }
        for (ChatServicio.Mensaje m : chat.mensajes()) agregar(m, false);
        estadoBotones();
        titulo.setText(chat.conversacion() == null ? "Nuevo chat" : "Asistente");
        bajar(true);
    }

    private void agregar(ChatServicio.Mensaje m, boolean animar) {
        View vacio = log.getChildCount() == 1 && dibujados.isEmpty() ? log.getChildAt(0) : null;
        if (vacio != null) log.removeView(vacio);
        boolean mio = "user".equals(m.rol);
        LinearLayout b = Ui.columna(a);
        b.setBackground(Ui.fondo(mio ? Ui.conAlfa(Ui.ACENTO, 0.18f) : Ui.TARJETA, 16, mio ? 0 : Ui.BORDE, a));
        int p = Ui.dp(a, 12);
        b.setPadding(p + 2, p, p + 2, p);
        int ancho = a.getResources().getDisplayMetrics().widthPixels;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                mio ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = mio ? Gravity.END : Gravity.START;
        lp.topMargin = Ui.dp(a, 10);
        if (mio) lp.leftMargin = (int) (ancho * 0.15f);
        b.setLayoutParams(lp);
        rellenar(b, m);
        firmas.put(m, firma(m));
        dibujados.put(m, b);
        log.addView(b);
        if (animar) Ui.entrar(b);
        titulo.setText("Asistente");
    }

    /** Firma barata para saber si un mensaje cambió desde el último dibujo. */
    private static String firma(ChatServicio.Mensaje m) {
        return m.texto.length() + "|" + m.estado + "|" + m.archivos.size() + "|" + (m.fin != null) + "|" + m.enCurso + "|" + m.fallo;
    }

    private void rellenar(LinearLayout b, ChatServicio.Mensaje m) {
        b.removeAllViews();
        boolean mio = "user".equals(m.rol);
        for (JSONObject arch : m.archivos) b.addView(tarjetaArchivo(arch));
        if (m.texto.length() > 0) {
            if (mio) {
                TextView t = Ui.texto(a, m.texto.toString(), 15.5f, Ui.TEXTO);
                t.setTextIsSelectable(true);
                b.addView(t);
            } else {
                b.addView(Markdown.vista(a, m.texto.toString()));
            }
        }
        if (m.enCurso && m.texto.length() == 0) b.addView(puntos());
        if (!m.estado.isEmpty()) {
            TextView e = Ui.pequeno(a, m.estado);
            if (m.fallo) e.setTextColor(Ui.PELIGRO);
            Ui.margen(e, 0, m.texto.length() > 0 ? 6 : 0, 0, 0);
            b.addView(e);
        }
        if (m.fin != null) extras(b, m.fin);
    }

    /** Tres puntos que «respiran» mientras la IA piensa. */
    private View puntos() {
        LinearLayout f = Ui.fila(a);
        f.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
        for (int i = 0; i < 3; i++) {
            View d = new View(a);
            d.setBackground(Ui.fondo(Ui.TEXTO2, 99, 0, a));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 8), Ui.dp(a, 8));
            lp.rightMargin = Ui.dp(a, 5);
            f.addView(d, lp);
            ObjectAnimator an = ObjectAnimator.ofFloat(d, View.ALPHA, 0.25f, 1f);
            an.setDuration(520);
            an.setStartDelay(i * 160L);
            an.setRepeatCount(ValueAnimator.INFINITE);
            an.setRepeatMode(ValueAnimator.REVERSE);
            an.start();
        }
        return f;
    }

    private void extras(LinearLayout b, Asistente.Final f) {
        if (f.sinConexion) {
            TextView marca = Ui.pequeno(a, "Respondido en el teléfono (sin conexión al servidor)");
            marca.setTextColor(Ui.AVISO);
            b.addView(marca);
        }
        if (f.fuentes != null && f.fuentes.length() > 0) {
            LinearLayout fila = Ui.columna(a);
            Ui.margen(fila, 0, 8, 0, 0);
            fila.addView(Ui.pequeno(a, "Fuentes:"));
            for (int i = 0; i < f.fuentes.length(); i++) {
                JSONObject s = f.fuentes.optJSONObject(i);
                if (s == null) continue;
                TextView link = Ui.texto(a, "• " + s.optString("titulo", s.optString("url")), 13, Ui.ACENTO);
                String url = s.optString("url");
                link.setOnClickListener(v -> a.abrirUrl(url));
                fila.addView(link);
            }
            b.addView(fila);
        }
        if (f.acciones != null && f.acciones.length() > 0) {
            LinearLayout fila = Ui.fila(a);
            fila.setGravity(Gravity.START);
            for (int i = 0; i < f.acciones.length(); i++) {
                JSONObject ac = f.acciones.optJSONObject(i);
                if (ac == null) continue;
                View v = accion(ac);
                if (v != null) fila.addView(v);
            }
            if (fila.getChildCount() > 0) b.addView(fila);
        }
    }

    private void estadoBotones() {
        boolean escribe = chat.escribiendo();
        detenerBtn.setVisibility(escribe ? View.VISIBLE : View.GONE);
        enviarBtn.setVisibility(escribe ? View.GONE : View.VISIBLE);
    }

    private boolean cercaDelFinal() {
        View hijo = scroll.getChildAt(0);
        return hijo == null || hijo.getBottom() - (scroll.getHeight() + scroll.getScrollY()) < Ui.dp(a, 140);
    }

    private void bajar(boolean forzar) {
        scroll.post(() -> { if (forzar || cercaDelFinal()) scroll.smoothScrollTo(0, log.getBottom()); });
    }

    // ─────────────────────────── historial
    private void mostrarHistorial() {
        if (!Sesion.de(a).iniciada()) { a.pedirSesion(); return; }
        Ui.aviso(a, "Cargando tus conversaciones…");
        chat.historial(lista -> {
            if (lista.length() == 0) { Ui.mensaje(a, "Historial", "Aún no tienes conversaciones guardadas."); return; }
            CharSequence[] nombres = new CharSequence[lista.length()];
            String[] ids = new String[lista.length()];
            for (int i = 0; i < lista.length(); i++) {
                JSONObject c = lista.optJSONObject(i);
                ids[i] = c.optString("id");
                String fecha = c.optString("created_at", "");
                nombres[i] = c.optString("title", "Conversación") + (fecha.length() >= 10 ? "   · " + fecha.substring(0, 10) : "");
            }
            new androidx.appcompat.app.AlertDialog.Builder(a)
                    .setTitle("Tus conversaciones")
                    .setItems(nombres, (d, w) -> chat.abrir(ids[w], ok -> bajar(true), e -> Ui.aviso(a, e.getMessage())))
                    .setNeutralButton("Borrar una…", (d, w) -> borrarDelHistorial(nombres, ids))
                    .setNegativeButton("Cerrar", null)
                    .show();
        }, e -> Ui.aviso(a, e.getMessage()));
    }

    private void borrarDelHistorial(CharSequence[] nombres, String[] ids) {
        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("¿Cuál borrar?")
                .setItems(nombres, (d, w) -> Ui.confirmar(a, "Borrar conversación",
                        "Se borrará «" + nombres[w] + "» y su historial cifrado. No se puede deshacer.", "Borrar",
                        () -> chat.eliminar(ids[w], () -> Ui.aviso(a, "Conversación borrada"), e -> Ui.aviso(a, e.getMessage()))))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ─────────────────────────── acciones y archivos (sin cambios de comportamiento)
    private View accion(JSONObject ac) {
        String tipo = ac.optString("tipo");
        if ("llamar".equals(tipo)) {
            String num = ac.optString("numero");
            if (!num.matches("\\d{3,10}")) return null;
            View b = Ui.botonChico(a, ac.optString("etiqueta"), Ui.Estilo.PELIGRO);
            b.setOnClickListener(v -> a.llamar(num));
            return b;
        }
        if ("resultado_url".equals(tipo)) {
            String vered = ac.optString("veredicto");
            int color = "bloquear".equals(vered) ? Ui.PELIGRO : "advertir".equals(vered) ? Ui.AVISO : Ui.OK;
            LinearLayout caja = Ui.tarjetaEstado(a, color);
            caja.addView(Ui.subtitulo(a, ac.optString("host")));
            caja.addView(Ui.insignia(a, vered, color));
            JSONArray motivos = ac.optJSONArray("motivos");
            if (motivos != null) for (int i = 0; i < motivos.length(); i++) caja.addView(Ui.pequeno(a, "• " + motivos.optString(i)));
            return caja;
        }
        if ("confirmar".equals(tipo)) {
            String accion = ac.optString("accion");
            View b = Ui.botonChico(a, ac.optString("etiqueta"), "activar_sos".equals(accion) ? Ui.Estilo.PELIGRO : Ui.Estilo.SECUNDARIO);
            b.setOnClickListener(v -> ejecutar(accion));
            return b;
        }
        if ("abrir".equals(tipo)) {
            View b = Ui.botonChico(a, ac.optString("etiqueta"), Ui.Estilo.SECUNDARIO);
            b.setOnClickListener(v -> a.irAPantalla(ac.optString("pantalla")));
            return b;
        }
        return null;
    }

    private void ejecutar(String accion) {
        switch (accion) {
            case "activar_sos":
                Ui.confirmar(a, "Avisar a mis contactos", "Se enviará una alerta con tu ubicación a tus contactos de emergencia.",
                        "Avisar ahora", () -> a.lanzarSos("Solicitado desde el asistente"));
                break;
            case "escanear_aplicaciones": a.irAPantalla("seguridad"); break;
            case "modo_perdido": a.irAPantalla("dispositivo"); break;
            case "generar_contrasena": a.irAPantalla("seguridad"); break;
            default: break;
        }
    }

    private View tarjetaArchivo(JSONObject archivo) {
        LinearLayout caja = Ui.tarjetaEstado(a, Ui.ACENTO);
        caja.addView(Ui.subtitulo(a, archivo.optString("nombre")));
        caja.addView(Ui.pequeno(a, archivo.optString("resumen", "Archivo creado") + " · guardado cifrado en tu bóveda"));
        View descargar = Ui.botonChico(a, "Descargar", Ui.Estilo.PRIMARIO);
        String ruta = archivo.optString("descarga"), nombre = archivo.optString("nombre");
        descargar.setOnClickListener(v -> a.descargarArchivo(ruta, nombre));
        caja.addView(descargar);
        return caja;
    }


    public void adjuntoSubido(JSONObject r) {
        chat.usarConversacion(r.optString("conversation_id"));
        chat.agregarNota("Leí **" + r.optString("nombre") + "**"
                + (r.optBoolean("truncado") ? " (es largo: leí el comienzo; pregúntame por partes concretas)" : "")
                + ". Pregúntame lo que quieras sobre su contenido.");
    }
}
