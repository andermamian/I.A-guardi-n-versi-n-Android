package co.guardiania.app.ui.kit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

/**
 * Sistema de diseño de GuardianIA para Android: los mismos colores, radios y jerarquía que la web,
 * en un solo lugar. Todas las pantallas se arman con estas piezas, así se ven iguales y un cambio
 * de estilo se hace aquí una vez.
 *
 * <p>Se construye en código (no en XML) a propósito: las pantallas son dinámicas (dependen de lo que
 * responda el servidor) y así no hay que sincronizar decenas de layouts con su lógica.
 * Accesibilidad: textos en sp (respetan el tamaño de letra del sistema), áreas táctiles ≥ 48 dp y
 * descripciones para lectores de pantalla en los botones de ícono.
 */
public final class Ui {

    private Ui() {}

    // ─────────────────────────── paleta (igual a values/colors.xml y a la web)
    public static final int FONDO = 0xFF0B1D2E;
    public static final int TARJETA = 0xFF12263B;
    public static final int TARJETA2 = 0xFF183049;
    public static final int BORDE = 0xFF1F3A55;
    public static final int TEXTO = 0xFFEAF2FB;
    public static final int TEXTO2 = 0xFF9FBAD2;
    public static final int ACENTO = 0xFF38BDF8;
    public static final int ACENTO_TEXTO = 0xFF04121E;
    public static final int OK = 0xFF34D399;
    public static final int AVISO = 0xFFFBBF24;
    public static final int PELIGRO = 0xFFF87171;

    public enum Estilo { PRIMARIO, SECUNDARIO, PELIGRO, FANTASMA }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static int conAlfa(int color, float alfa) {
        return Color.argb(Math.round(255 * alfa), Color.red(color), Color.green(color), Color.blue(color));
    }

    public static GradientDrawable fondo(int color, float radioDp, int borde, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radioDp));
        if (borde != 0) g.setStroke(dp(c, 1), borde);
        return g;
    }

    public static Drawable conOnda(Drawable base, int colorOnda) {
        return new RippleDrawable(ColorStateList.valueOf(colorOnda), base, null);
    }

    // ─────────────────────────── contenedores
    public static LinearLayout columna(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout fila(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Tarjeta: el bloque visual básico (fondo elevado, borde sutil, esquinas de 16 dp). */
    public static LinearLayout tarjeta(Context c) {
        LinearLayout l = columna(c);
        l.setBackground(fondo(TARJETA, 16, BORDE, c));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        margen(l, 0, 12, 0, 0);
        return l;
    }

    /** Tarjeta con acento de color a la izquierda (estado: ok, aviso, peligro). */
    public static LinearLayout tarjetaEstado(Context c, int color) {
        LinearLayout l = tarjeta(c);
        l.setBackground(fondo(conAlfa(color, 0.10f), 16, conAlfa(color, 0.55f), c));
        return l;
    }

    public static ScrollView desplazable(Context c, View contenido) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        s.addView(contenido, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    /** Columna con el margen estándar de pantalla (20 dp). */
    public static LinearLayout pagina(Context c) {
        LinearLayout l = columna(c);
        int p = dp(c, 20);
        l.setPadding(p, dp(c, 12), p, dp(c, 28));
        return l;
    }

    // ─────────────────────────── texto
    public static TextView titulo(Context c, String s) {
        TextView t = texto(c, s, 24, TEXTO);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    public static TextView subtitulo(Context c, String s) {
        TextView t = texto(c, s, 17, TEXTO);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView parrafo(Context c, String s) {
        TextView t = texto(c, s, 15, TEXTO2);
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    public static TextView pequeno(Context c, String s) { return texto(c, s, 13, TEXTO2); }

    /** Rótulo de sección en mayúsculas pequeñas («ACCESOS RÁPIDOS»). */
    public static TextView seccion(Context c, String s) {
        TextView t = texto(c, s.toUpperCase(), 12, TEXTO2);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        margen(t, 0, 24, 0, 0);
        return t;
    }

    public static TextView texto(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** Número grande (pulso, puntaje de protección). */
    public static TextView cifra(Context c, String s, int color) {
        TextView t = texto(c, s, 40, color);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        return t;
    }

    /** Insignia de estado («Protegido», «Peligrosa»). */
    public static TextView insignia(Context c, String s, int color) {
        TextView t = texto(c, s, 12, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackground(fondo(conAlfa(color, 0.16f), 999, 0, c));
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        return t;
    }

    // ─────────────────────────── controles
    public static Button boton(Context c, String s, Estilo e) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 50));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        int fondo, texto, borde;
        switch (e) {
            case PRIMARIO: fondo = ACENTO; texto = ACENTO_TEXTO; borde = 0; break;
            case PELIGRO: fondo = conAlfa(PELIGRO, 0.14f); texto = PELIGRO; borde = conAlfa(PELIGRO, 0.6f); break;
            case FANTASMA: fondo = Color.TRANSPARENT; texto = ACENTO; borde = 0; break;
            default: fondo = TARJETA2; texto = TEXTO; borde = BORDE;
        }
        b.setTextColor(texto);
        b.setBackground(conOnda(fondo(fondo, 14, borde, c), conAlfa(Color.WHITE, 0.18f)));
        presionable(b);                          // respuesta táctil en todos los botones de la app
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 10);
        b.setLayoutParams(lp);
        return b;
    }

    /** Botón compacto (ancho según su texto), para filas de acciones. */
    public static Button botonChico(Context c, String s, Estilo e) {
        Button b = boton(c, s, e);
        b.setMinHeight(dp(c, 40));
        b.setMinimumHeight(dp(c, 40));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        lp.rightMargin = dp(c, 8);
        b.setLayoutParams(lp);
        return b;
    }

    public static ImageView icono(Context c, int drawable, int color, int tamDp) {
        ImageView i = new ImageView(c);
        i.setImageDrawable(ContextCompat.getDrawable(c, drawable));
        i.setColorFilter(color);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, tamDp), dp(c, tamDp)));
        return i;
    }

    /** Botón redondo de ícono (enviar, micrófono…), con descripción para lectores de pantalla. */
    public static ImageView botonIcono(Context c, int drawable, String descripcion, int colorFondo, int colorIcono) {
        ImageView i = new ImageView(c);
        i.setImageDrawable(ContextCompat.getDrawable(c, drawable));
        i.setColorFilter(colorIcono);
        i.setContentDescription(descripcion);
        int p = dp(c, 11);
        i.setPadding(p, p, p, p);
        i.setBackground(conOnda(fondo(colorFondo, 999, 0, c), conAlfa(Color.WHITE, 0.2f)));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 46), dp(c, 46)));
        i.setClickable(true);
        i.setFocusable(true);
        return i;
    }

    public static EditText campo(Context c, String pista, int tipo) {
        EditText e = new EditText(c);
        e.setHint(pista);
        e.setInputType(tipo);
        e.setTextColor(TEXTO);
        e.setHintTextColor(conAlfa(TEXTO2, 0.8f));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setBackground(fondo(TARJETA2, 12, BORDE, c));
        int p = dp(c, 14);
        e.setPadding(p, p, p, p);
        e.setContentDescription(pista);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        e.setLayoutParams(lp);
        return e;
    }

    public static EditText campoClave(Context c, String pista) {
        return campo(c, pista, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    }

    /** Fila de lista navegable: ícono, título, subtítulo y chevrón. */
    public static LinearLayout itemLista(Context c, int icono, String titulo, String sub, View.OnClickListener click) {
        LinearLayout f = fila(c);
        int p = dp(c, 14);
        f.setPadding(p, p, p, p);
        f.setBackground(conOnda(fondo(TARJETA, 14, BORDE, c), conAlfa(Color.WHITE, 0.12f)));
        f.setMinimumHeight(dp(c, 56));
        margen(f, 0, 8, 0, 0);
        if (icono != 0) {
            ImageView i = icono(c, icono, ACENTO, 22);
            ((LinearLayout.LayoutParams) i.getLayoutParams()).rightMargin = dp(c, 14);
            f.addView(i);
        }
        LinearLayout textos = columna(c);
        textos.addView(texto(c, titulo, 16, TEXTO));
        if (sub != null && !sub.isEmpty()) {
            TextView s = pequeno(c, sub);
            s.setMaxLines(2);
            s.setEllipsize(TextUtils.TruncateAt.END);
            textos.addView(s);
        }
        f.addView(textos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (click != null) {
            f.addView(texto(c, "›", 24, TEXTO2));
            f.setOnClickListener(click);
        }
        return f;
    }

    public static View separador(Context c) {
        View v = new View(c);
        v.setBackgroundColor(BORDE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        margen(v, 0, 12, 0, 12);
        return v;
    }

    public static View espacio(Context c, int dpAlto) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dpAlto)));
        return v;
    }

    /** Barra superior: botón atrás, título y acciones a la derecha. */
    public static LinearLayout barraSuperior(Activity a, String titulo, View... acciones) {
        LinearLayout f = fila(a);
        f.setPadding(dp(a, 8), dp(a, 8), dp(a, 12), dp(a, 8));
        f.setBackgroundColor(FONDO);
        ImageView atras = botonIcono(a, co.guardiania.app.R.drawable.ic_atras, "Volver", Color.TRANSPARENT, TEXTO);
        atras.setOnClickListener(v -> a.finish());
        f.addView(atras);
        TextView t = subtitulo(a, titulo);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(a, 6), 0, 0, 0);
        f.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (View v : acciones) f.addView(v);
        return f;
    }

    // ─────────────────────────── layout
    public static <V extends View> V margen(V v, int izq, int arr, int der, int abj) {
        Context c = v.getContext();
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        LinearLayout.LayoutParams m = lp instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) lp
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        m.setMargins(dp(c, izq), dp(c, arr), dp(c, der), dp(c, abj));
        v.setLayoutParams(m);
        return v;
    }

    // ─────────────────────────── animaciones
    /** ¿La persona pidió reducir las animaciones en Ajustes de Android (accesibilidad)? */
    public static boolean sinAnimaciones(Context c) {
        try {
            return android.provider.Settings.Global.getFloat(c.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            return false;
        }
    }

    /** Entrada suave: sube 12 dp y aparece en 280 ms (desaceleración, como las apps nativas). */
    public static void entrar(View v) {
        if (sinAnimaciones(v.getContext())) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 12));
        v.animate().alpha(1f).translationY(0f).setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
    }

    /** Respuesta táctil: el botón se hunde un poco al presionarlo. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    public static void presionable(View v) {
        v.setOnTouchListener((vista, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) vista.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
            else if (e.getAction() == android.view.MotionEvent.ACTION_UP || e.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                vista.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            return false;                        // no consume: el click sigue funcionando
        });
    }

    public static <V extends View> V ancho(V v, int ancho, int alto, float peso) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        LinearLayout.LayoutParams n = new LinearLayout.LayoutParams(ancho, alto, peso);
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            n.setMargins(m.leftMargin, m.topMargin, m.rightMargin, m.bottomMargin);
        }
        v.setLayoutParams(n);
        return v;
    }

    public static FrameLayout marco(Context c) { return new FrameLayout(c); }

    /** Añade `hijo` a `padre` y DEVUELVE el hijo, para poder encadenar (addView devuelve void). */
    public static <V extends View> V en(ViewGroup padre, V hijo) { padre.addView(hijo); return hijo; }

    /** Fija el click y devuelve la vista, para encadenar. */
    public static <V extends View> V alTocar(V v, View.OnClickListener l) { v.setOnClickListener(l); return v; }

    // ─────────────────────────── diálogos y avisos
    public static void aviso(Context c, String s) { Toast.makeText(c, s, Toast.LENGTH_LONG).show(); }

    public static void confirmar(Context c, String titulo, String mensaje, String etiqueta, Runnable si) {
        new AlertDialog.Builder(c)
                .setTitle(titulo)
                .setMessage(mensaje)
                .setPositiveButton(etiqueta, (d, w) -> si.run())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    public static void mensaje(Context c, String titulo, CharSequence texto) {
        new AlertDialog.Builder(c).setTitle(titulo).setMessage(texto).setPositiveButton("Entendido", null).show();
    }

    public static void copiar(Context c, String etiqueta, String texto) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText(etiqueta, texto);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Android 13+: no mostrar el contenido en la vista previa si es sensible (contraseñas).
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean("android.content.extra.IS_SENSITIVE", etiqueta.toLowerCase().contains("contrase"));
                clip.getDescription().setExtras(extras);
            }
            cm.setPrimaryClip(clip);
        }
        if (android.os.Build.VERSION.SDK_INT < 33) aviso(c, "Copiado");   // 13+ ya muestra su propio aviso
    }

    /** Texto «hace 5 min» a partir de una fecha ISO del servidor. */
    public static String hace(String iso) {
        try {
            long t = java.time.OffsetDateTime.parse(iso.endsWith("Z") || iso.matches(".*[+-]\\d\\d:\\d\\d$") ? iso : iso + "Z")
                    .toInstant().toEpochMilli();
            long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
            if (s < 60) return "hace un momento";
            if (s < 3600) return "hace " + (s / 60) + " min";
            if (s < 86400) return "hace " + (s / 3600) + " h";
            return "hace " + (s / 86400) + " d";
        } catch (Exception e) {
            return "";
        }
    }
}
