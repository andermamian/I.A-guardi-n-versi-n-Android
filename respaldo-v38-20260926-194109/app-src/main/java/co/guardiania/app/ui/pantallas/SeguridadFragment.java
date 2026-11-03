package co.guardiania.app.ui.pantallas;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import co.guardiania.app.ai.RiesgoUrl;
import co.guardiania.app.core.Hilos;
import co.guardiania.app.net.Sesion;
import co.guardiania.app.seguridad.AnalizadorArchivo;
import co.guardiania.app.seguridad.Contrasenas;
import co.guardiania.app.seguridad.EscanerApps;
import co.guardiania.app.ui.MainActivity;
import co.guardiania.app.ui.kit.Ui;

/**
 * Pestaña de seguridad. Tres herramientas, cada una con su límite dicho de frente:
 * <ul>
 *   <li><b>Revisar un enlace</b>: se analiza EN EL TELÉFONO (no se envía a ningún lado). Funciona sin
 *       conexión.</li>
 *   <li><b>Analizar un archivo</b>: el teléfono calcula la huella y las señales; solo eso viaja al
 *       motor del servidor. El archivo nunca se sube.</li>
 *   <li><b>Revisar mis apps</b>: reúne los permisos de las apps instaladas y el motor del servidor
 *       marca las peligrosas. Android prohíbe a una app escanear a las demás, así que se revisan los
 *       permisos, que es lo que sí se puede.</li>
 *   <li><b>Generar contraseña</b>: en el teléfono, con azar del sistema.</li>
 * </ul>
 */
public class SeguridadFragment {

    private final MainActivity a;
    private LinearLayout resultados;

    public SeguridadFragment(MainActivity a) { this.a = a; }

    public View crear() {
        LinearLayout raiz = Ui.pagina(a);
        raiz.addView(Ui.titulo(a, "Seguridad"));
        raiz.addView(Ui.parrafo(a, "Revisa enlaces y archivos sospechosos y los permisos de tus apps."));

        // VPN con IA.
        LinearLayout vpn = Ui.tarjeta(a);
        vpn.addView(Ui.subtitulo(a, "VPN con IA"));
        vpn.addView(Ui.pequeno(a, "Cifra tu conexión en WiFi públicos y cambia tu IP. Gratis con Cloudflare WARP."));
        Ui.en(vpn, Ui.boton(a, "Abrir la VPN", Ui.Estilo.PRIMARIO)).setOnClickListener(v ->
                a.startActivity(new android.content.Intent(a, VpnActivity.class)));
        raiz.addView(vpn);

        // Revisar enlace (local).
        LinearLayout enlace = Ui.tarjeta(a);
        enlace.addView(Ui.subtitulo(a, "Revisar un enlace"));
        enlace.addView(Ui.pequeno(a, "Se analiza en tu teléfono, sin enviarlo a ningún lado."));
        EditText url = Ui.campo(a, "Pega el enlace (ej. banco-seguro.xyz)", android.text.InputType.TYPE_TEXT_VARIATION_URI);
        enlace.addView(url);
        Ui.en(enlace, Ui.boton(a, "Revisar enlace", Ui.Estilo.PRIMARIO)).setOnClickListener(v -> revisarEnlace(url.getText().toString().trim()));
        raiz.addView(enlace);

        // Archivo.
        LinearLayout archivo = Ui.tarjeta(a);
        archivo.addView(Ui.subtitulo(a, "Analizar un archivo"));
        archivo.addView(Ui.pequeno(a, "El archivo no se sube: se calcula su huella y se revisa con el motor."));
        Ui.en(archivo, Ui.boton(a, "Elegir un archivo", Ui.Estilo.SECUNDARIO)).setOnClickListener(v -> a.elegirArchivoParaAnalizar(this::analizarArchivo));
        raiz.addView(archivo);

        // Apps.
        LinearLayout apps = Ui.tarjeta(a);
        apps.addView(Ui.subtitulo(a, "Revisar mis aplicaciones"));
        apps.addView(Ui.pequeno(a, "Revisa los permisos de tus apps y marca las peligrosas."));
        Ui.en(apps, Ui.boton(a, "Revisar mis apps", Ui.Estilo.SECUNDARIO)).setOnClickListener(v -> revisarApps());
        raiz.addView(apps);

        // Contraseña.
        LinearLayout clave = Ui.tarjeta(a);
        clave.addView(Ui.subtitulo(a, "Generar una contraseña segura"));
        TextView valor = Ui.texto(a, "", 18, Ui.ACENTO);
        valor.setTypeface(android.graphics.Typeface.MONOSPACE);
        valor.setTextIsSelectable(true);
        clave.addView(valor);
        Ui.en(clave, Ui.boton(a, "Generar", Ui.Estilo.PRIMARIO)).setOnClickListener(v -> {
            String p = Contrasenas.generar(20);
            valor.setText(p);
            Ui.copiar(a, "contraseña", p);
        });
        raiz.addView(clave);

        raiz.addView(Ui.seccion(a, "Resultado"));
        resultados = Ui.columna(a);
        raiz.addView(resultados);

        return Ui.desplazable(a, raiz);
    }

    private void revisarEnlace(String url) {
        resultados.removeAllViews();
        if (url.isEmpty()) { Ui.aviso(a, "Pega un enlace."); return; }
        try {
            RiesgoUrl.Veredicto v = RiesgoUrl.evaluar(url);
            int color = "bloquear".equals(v.veredicto) ? Ui.PELIGRO : "advertir".equals(v.veredicto) ? Ui.AVISO : Ui.OK;
            LinearLayout caja = Ui.tarjetaEstado(a, color);
            caja.addView(Ui.subtitulo(a, v.host));
            caja.addView(Ui.insignia(a, v.etiqueta(), color));
            for (RiesgoUrl.Motivo m : v.motivos) caja.addView(Ui.pequeno(a, "• " + m.motivo));
            caja.addView(Ui.pequeno(a, "Nunca ingreses claves desde enlaces recibidos por mensaje."));
            resultados.addView(caja);
        } catch (IllegalArgumentException e) {
            Ui.aviso(a, "Ese texto no parece un enlace.");
        }
    }

    private void analizarArchivo(String nombre, byte[] datos) {
        if (!Sesion.de(a).iniciada()) { a.pedirSesion(); return; }
        resultados.removeAllViews();
        TextView cargando = Ui.parrafo(a, "Analizando «" + nombre + "»…");
        resultados.addView(cargando);
        Hilos.<JSONObject>tarea(() -> {
            JSONObject file = AnalizadorArchivo.rasgos(nombre, new java.io.ByteArrayInputStream(datos));
            JSONObject cuerpo = new JSONObject();
            cuerpo.put("file", file);
            return Sesion.de(a).obj("POST", "/antivirus/scan", cuerpo);
        }, res -> {
            resultados.removeAllViews();
            JSONObject r = res.optJSONObject("resultado");
            String nivel = r != null ? r.optString("nivel") : "";
            int color = "malicioso".equals(nivel) ? Ui.PELIGRO : "sospechoso".equals(nivel) ? Ui.AVISO : Ui.OK;
            LinearLayout caja = Ui.tarjetaEstado(a, color);
            caja.addView(Ui.subtitulo(a, nombre));
            caja.addView(Ui.insignia(a, nivel.isEmpty() ? "analizado" : nivel, color));
            JSONArray det = r != null ? r.optJSONArray("detecciones") : null;
            if (det != null) for (int i = 0; i < det.length(); i++) caja.addView(Ui.pequeno(a, "• " + det.optJSONObject(i).optString("motivo")));
            JSONArray rec = r != null ? r.optJSONArray("recomendaciones") : null;
            if (rec != null && rec.length() > 0) caja.addView(Ui.pequeno(a, "Recomendación: " + rec.optString(0)));
            resultados.addView(caja);
        }, e -> { resultados.removeAllViews(); Ui.aviso(a, e.getMessage()); });
    }

    private void revisarApps() {
        if (!Sesion.de(a).iniciada()) { a.pedirSesion(); return; }
        resultados.removeAllViews();
        resultados.addView(Ui.parrafo(a, "Revisando los permisos de tus apps…"));
        Hilos.<JSONObject>tarea(() -> {
            JSONArray apps = EscanerApps.recolectar(a);
            JSONObject cuerpo = new JSONObject();
            cuerpo.put("apps", apps);
            return Sesion.de(a).obj("POST", "/apps/scan", cuerpo);
        }, res -> {
            resultados.removeAllViews();
            int peligrosas = res.optInt("peligrosas"), sospechosas = res.optInt("sospechosas"), total = res.optInt("total");
            LinearLayout resumen = Ui.tarjetaEstado(a, peligrosas > 0 ? Ui.PELIGRO : sospechosas > 0 ? Ui.AVISO : Ui.OK);
            resumen.addView(Ui.subtitulo(a, total + " apps revisadas"));
            resumen.addView(Ui.parrafo(a, peligrosas + " peligrosas · " + sospechosas + " sospechosas"));
            resultados.addView(resumen);
            JSONArray lista = res.optJSONArray("apps");
            if (lista != null) for (int i = 0; i < lista.length(); i++) {
                JSONObject app = lista.optJSONObject(i);
                if (app == null || "segura".equals(app.optString("verdict"))) continue;
                int color = "peligrosa".equals(app.optString("verdict")) ? Ui.PELIGRO : Ui.AVISO;
                LinearLayout caja = Ui.tarjetaEstado(a, color);
                caja.addView(Ui.subtitulo(a, app.optString("label")));
                caja.addView(Ui.insignia(a, app.optString("verdict"), color));
                JSONArray razones = app.optJSONArray("reasons");
                if (razones != null) for (int j = 0; j < Math.min(3, razones.length()); j++)
                    caja.addView(Ui.pequeno(a, "• " + razones.optJSONObject(j).optString("motivo")));
                resultados.addView(caja);
            }
            if (resultados.getChildCount() == 1) resultados.addView(Ui.parrafo(a, "No se encontraron apps peligrosas ni sospechosas. 👍"));
        }, e -> { resultados.removeAllViews(); Ui.aviso(a, e.getMessage()); });
    }
}
