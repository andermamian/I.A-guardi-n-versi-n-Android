package co.guardiania.app.ui.kit;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.util.Linkify;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Muestra las respuestas del asistente con formato: bloques de código (monoespaciados, con
 * desplazamiento horizontal y botón Copiar), títulos, listas, **negrita**, `código` y enlaces.
 *
 * <p>Equivale a richText() de la web. Todo se pinta con Spans sobre texto plano — nunca se
 * interpreta HTML del servidor — así un «&lt;script&gt;» en la respuesta se ve como texto, igual
 * que en la web (que usa textContent).
 *
 * <p>También detecta código que el modelo mandó SIN los ``` (pasa con modelos locales): si un
 * párrafo tiene varias líneas con forma de código, se muestra como bloque.
 */
public final class Markdown {

    private static final Pattern NEGRITA = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern CODIGO_EN_LINEA = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern PARECE_CODIGO = Pattern.compile(
            "^\\s*(def |class |import |from \\S+ import|public |private |function |const |let |var |#include|"
            + "SELECT |INSERT |CREATE |<\\?php|<html|<div|for \\(|if \\(|while \\(|return |\\}|\\{)");

    private Markdown() {}

    public static View vista(Context c, String texto) {
        LinearLayout raiz = Ui.columna(c);
        String[] partes = texto.split("```", -1);
        for (int i = 0; i < partes.length; i++) {
            String p = partes[i];
            if (i % 2 == 1) {                                   // dentro de ``` … ```
                int nl = p.indexOf('\n');
                String lenguaje = nl > 0 ? p.substring(0, nl).trim() : "";
                String codigo = nl >= 0 ? p.substring(nl + 1) : p;
                if (lenguaje.contains(" ")) { lenguaje = ""; codigo = p; }
                raiz.addView(bloqueCodigo(c, lenguaje, trimFinal(codigo)));
            } else if (!p.trim().isEmpty()) {
                agregarTexto(c, raiz, p);
            }
        }
        return raiz;
    }

    /** Separa párrafos normales de trozos que parecen código sin marcar. */
    private static void agregarTexto(Context c, LinearLayout raiz, String texto) {
        StringBuilder normal = new StringBuilder(), codigo = new StringBuilder();
        for (String linea : texto.split("\n", -1)) {
            boolean esCodigo = PARECE_CODIGO.matcher(linea).find() || (linea.startsWith("    ") && codigo.length() > 0);
            if (esCodigo) {
                if (normal.length() > 0) { raiz.addView(parrafos(c, normal.toString())); normal.setLength(0); }
                codigo.append(linea).append('\n');
            } else {
                if (codigo.length() > 0) {
                    if (codigo.toString().split("\n").length >= 3) raiz.addView(bloqueCodigo(c, "", trimFinal(codigo.toString())));
                    else normal.append(codigo);
                    codigo.setLength(0);
                }
                normal.append(linea).append('\n');
            }
        }
        if (codigo.length() > 0) {
            if (codigo.toString().split("\n").length >= 3) raiz.addView(bloqueCodigo(c, "", trimFinal(codigo.toString())));
            else normal.append(codigo);
        }
        if (normal.toString().trim().length() > 0) raiz.addView(parrafos(c, normal.toString()));
    }

    private static TextView parrafos(Context c, String texto) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lineas = trimFinal(texto).split("\n", -1);
        for (int i = 0; i < lineas.length; i++) {
            String l = lineas[i];
            int inicio = sb.length();
            if (l.startsWith("### ") || l.startsWith("## ") || l.startsWith("# ")) {
                String t = l.replaceFirst("^#+\\s*", "");
                sb.append(t);
                sb.setSpan(new StyleSpan(Typeface.BOLD), inicio, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(l.startsWith("# ") ? 1.25f : 1.12f), inicio, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (l.matches("^\\s*[-*•] .*")) {
                sb.append("  •  ").append(l.replaceFirst("^\\s*[-*•] ", ""));
            } else {
                sb.append(l);
            }
            if (i < lineas.length - 1) sb.append('\n');
        }
        estilosEnLinea(sb);
        TextView t = Ui.texto(c, "", 15.5f, Ui.TEXTO);
        t.setLineSpacing(Ui.dp(c, 3), 1f);
        t.setText(sb);
        t.setTextIsSelectable(true);
        Linkify.addLinks(t, Linkify.WEB_URLS | Linkify.PHONE_NUMBERS);
        t.setLinkTextColor(Ui.ACENTO);
        t.setMovementMethod(LinkMovementMethod.getInstance());
        Ui.margen(t, 0, 2, 0, 2);
        return t;
    }

    /** **negrita** y `código`: se reemplazan de atrás hacia adelante para no mover los índices. */
    private static void estilosEnLinea(SpannableStringBuilder sb) {
        aplicar(sb, NEGRITA, new Estilo() {
            public Object[] spans() { return new Object[]{new StyleSpan(Typeface.BOLD)}; }
        });
        aplicar(sb, CODIGO_EN_LINEA, new Estilo() {
            public Object[] spans() {
                return new Object[]{new TypefaceSpan("monospace"), new BackgroundColorSpan(Ui.TARJETA2),
                        new ForegroundColorSpan(Ui.ACENTO)};
            }
        });
    }

    private interface Estilo { Object[] spans(); }

    private static void aplicar(SpannableStringBuilder sb, Pattern p, Estilo e) {
        Matcher m = p.matcher(sb.toString());
        java.util.List<int[]> hallados = new java.util.ArrayList<>();
        while (m.find()) hallados.add(new int[]{m.start(), m.end(), m.start(1), m.end(1)});
        for (int k = hallados.size() - 1; k >= 0; k--) {
            int[] h = hallados.get(k);
            CharSequence interior = sb.subSequence(h[2], h[3]);
            sb.replace(h[0], h[1], interior);
            for (Object s : e.spans()) sb.setSpan(s, h[0], h[0] + interior.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static View bloqueCodigo(Context c, String lenguaje, String codigo) {
        LinearLayout caja = Ui.columna(c);
        caja.setBackground(Ui.fondo(0xFF071522, 12, Ui.BORDE, c));
        Ui.margen(caja, 0, 8, 0, 8);
        LinearLayout cabecera = Ui.fila(c);
        cabecera.setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 4), 0);
        TextView leng = Ui.pequeno(c, lenguaje.isEmpty() ? "código" : lenguaje);
        cabecera.addView(leng, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView copiar = Ui.texto(c, "Copiar", 13, Ui.ACENTO);
        copiar.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        copiar.setOnClickListener(v -> Ui.copiar(c, "código", codigo));
        cabecera.addView(copiar);
        caja.addView(cabecera);

        HorizontalScrollView h = new HorizontalScrollView(c);
        TextView t = Ui.texto(c, codigo, 13, 0xFFD6E6F5);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setHorizontallyScrolling(true);
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 2), Ui.dp(c, 12), Ui.dp(c, 12));
        h.addView(t);
        caja.addView(h);
        return caja;
    }

    private static String trimFinal(String s) { return s.replaceAll("\\s+$", ""); }
}
