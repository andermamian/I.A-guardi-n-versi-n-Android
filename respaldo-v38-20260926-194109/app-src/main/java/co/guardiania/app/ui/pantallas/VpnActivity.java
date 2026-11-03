package co.guardiania.app.ui.pantallas;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.ui.kit.Ui;
import co.guardiania.app.vpn.Anonimato;
import co.guardiania.app.vpn.ImportadorCarpeta;
import co.guardiania.app.vpn.ModoPrivacidad;
import co.guardiania.app.vpn.MotorVpn;
import co.guardiania.app.vpn.RecomendadorUbicacion;
import co.guardiania.app.vpn.TorInterno;
import co.guardiania.app.vpn.TorVerificacion;
import co.guardiania.app.vpn.Ubicaciones;

/**
 * Privacidad del teléfono: ProtonVPN, Tor o ambos (Tor sobre VPN), con UN botón.
 *
 * <p>Diseño (v3.5): una sola vía por modo, para que nada entre en conflicto (Android admite una sola
 * VPN). Se retiraron WARP, Orbot, el Tor del PC y los servidores propios. El panel superior muestra en
 * tiempo real el país, la IP, la velocidad, el tiempo conectado y el estado de Tor. GuardianIA elige la
 * ubicación según el propósito, conecta, verifica que la IP cambió y, si falla, prueba la siguiente.
 */
public class VpnActivity extends AppCompatActivity {

    private MotorVpn motor;
    private TextView orbe, estadoTxt, paisTxt, ipTxt, velTxt, tiempoTxt, torTxt, razonTxt;
    private View boton;
    private LinearLayout modos, propositos, ubicCaja, torCaja, anonCaja;
    private boolean ocupado = false;
    private String modoElegido, proposito = RecomendadorUbicacion.ANONIMATO, paisDestino = null;
    private long rxPrev = -1, txPrev = -1, tPrev = 0;
    private TorVerificacion.Verificacion verifTor;
    private String paisTorReal;

    private final ActivityResultLauncher<Intent> permisoVpn = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (MotorVpn.permiso(this) == null) aplicar(modoElegido);
                else { ocupado = false; Ui.aviso(this, "Sin el permiso de VPN no se puede conectar ProtonVPN."); }
            });
    private final ActivityResultLauncher<String[]> archivos = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::importarArchivos);
    private final ActivityResultLauncher<Uri> carpeta = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::vincularCarpeta);

    private final TorInterno.Oyente oyenteTor = (e, s, h) -> { pintarTor(); pintarVivo(); };
    private final Runnable vivo = new Runnable() {
        @Override public void run() { pintarVivo(); Hilos.uiLuego(this, 1000); }
    };

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        motor = MotorVpn.de(this);
        modoElegido = ModoPrivacidad.actual(this);
        if (ModoPrivacidad.APAGADO.equals(modoElegido)) modoElegido = ModoPrivacidad.PROTON_TOR;

        LinearLayout raiz = Ui.columna(this);
        raiz.setBackgroundColor(Ui.FONDO);
        raiz.addView(Ui.barraSuperior(this, "Privacidad"));
        LinearLayout p = Ui.pagina(this);

        p.addView(panelVivo());
        p.addView(Ui.seccion(this, "Modo"));
        modos = Ui.fila(this);
        p.addView(modos);
        pintarModos();
        p.addView(Ui.seccion(this, "¿Para qué?"));
        propositos = Ui.columna(this);
        p.addView(propositos);
        pintarPropositos();
        p.addView(Ui.seccion(this, "Ubicaciones"));
        ubicCaja = Ui.columna(this);
        p.addView(ubicCaja);
        p.addView(Ui.seccion(this, "Tor"));
        torCaja = Ui.columna(this);
        p.addView(torCaja);
        p.addView(Ui.seccion(this, "Nivel de anonimato (GuardianIA)"));
        anonCaja = Ui.columna(this);
        p.addView(anonCaja);

        LinearLayout kill = Ui.tarjeta(this);
        kill.addView(Ui.pequeno(this, "Máxima protección: activa en Android «VPN siempre activa» y «Bloquear conexiones sin VPN». "
                + "Así, si el túnel se cae, nada sale sin cifrar."));
        Ui.en(kill, Ui.botonChico(this, "Abrir ajustes de VPN", Ui.Estilo.SECUNDARIO)).setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); } catch (Exception e) { Ui.aviso(this, "Busca «VPN» en Ajustes."); }
        });
        p.addView(kill);

        raiz.addView(Ui.desplazable(this, p), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(raiz);
        pintarUbicaciones();
        pintarTor();
    }

    @Override protected void onResume() {
        super.onResume();
        TorInterno.observar(oyenteTor);
        Hilos.ui(vivo);
        pintarAnonimato(false);
        // Importación automática: si la carpeta está vinculada, cada .conf nuevo entra solo.
        if (ImportadorCarpeta.vinculada(this)) {
            Hilos.<ImportadorCarpeta.Resultado>tarea(() -> ImportadorCarpeta.importarNuevos(this), r -> {
                if (r.nuevas > 0) { Ui.aviso(this, r.nuevas + " ubicación(es) nueva(s) importada(s) automáticamente."); pintarUbicaciones(); }
                if (r.rechazadas > 0) Ui.mensaje(this, "Archivos rechazados por seguridad", r.motivos.toString());
            }, e -> {});
        }
    }

    @Override protected void onPause() {
        super.onPause();
        Hilos.cancelar(vivo);
        TorInterno.dejarDeObservar(oyenteTor);
    }

    // ─────────────────────────── panel en tiempo real + el botón
    private View panelVivo() {
        LinearLayout t = Ui.tarjeta(this);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        orbe = Ui.texto(this, "●", 54, Ui.TEXTO2);
        orbe.setGravity(Gravity.CENTER);
        t.addView(orbe);
        estadoTxt = Ui.subtitulo(this, "Sin protección");
        estadoTxt.setGravity(Gravity.CENTER);
        t.addView(estadoTxt);
        paisTxt = Ui.parrafo(this, "");
        paisTxt.setGravity(Gravity.CENTER);
        t.addView(paisTxt);
        LinearLayout datos = Ui.fila(this);
        datos.setGravity(Gravity.CENTER);
        ipTxt = Ui.pequeno(this, "");
        velTxt = Ui.pequeno(this, "");
        tiempoTxt = Ui.pequeno(this, "");
        for (TextView x : new TextView[]{ipTxt, velTxt, tiempoTxt}) {
            x.setGravity(Gravity.CENTER);
            datos.addView(x, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        t.addView(datos);
        torTxt = Ui.pequeno(this, "");
        torTxt.setGravity(Gravity.CENTER);
        t.addView(torTxt);
        razonTxt = Ui.pequeno(this, "");
        razonTxt.setGravity(Gravity.CENTER);
        razonTxt.setTextColor(Ui.ACENTO);
        t.addView(razonTxt);
        boton = Ui.boton(this, "Activar privacidad", Ui.Estilo.PRIMARIO);
        boton.setMinimumHeight(Ui.dp(this, 58));
        boton.setOnClickListener(v -> botonPrincipal());
        Ui.margen(boton, 0, 12, 0, 0);
        t.addView(boton);
        return t;
    }

    private void pintarVivo() {
        String modo = ModoPrivacidad.actual(this);
        boolean vpn = motor.activo(), tor = TorInterno.activo(), torArranca = "STARTING".equals(TorInterno.estado());
        boolean protegido = (ModoPrivacidad.usaProton(modo) ? vpn : true) && (ModoPrivacidad.usaTor(modo) ? tor : true)
                && !ModoPrivacidad.APAGADO.equals(modo);
        boolean enCurso = ocupado || (ModoPrivacidad.usaTor(modo) && torArranca);
        int color = protegido ? Ui.OK : enCurso ? Ui.AVISO : Ui.TEXTO2;
        orbe.setTextColor(color);
        orbe.animate().scaleX(protegido ? 1.12f : 1f).scaleY(protegido ? 1.12f : 1f).setDuration(600).start();
        estadoTxt.setText(ocupado ? "Conectando…" : protegido ? "Protegido · " + ModoPrivacidad.nombre(modo)
                : enCurso ? "Conectando a la red Tor…" : "Sin protección");
        ((android.widget.Button) boton).setText(ocupado ? "Un momento…" : ModoPrivacidad.APAGADO.equals(modo)
                ? "Activar privacidad" : "Apagar privacidad");
        boton.setEnabled(!ocupado);

        JSONObject st = vpn ? motor.estadisticas(false) : new JSONObject();
        String paisVpn = st.isNull("pais_salida") ? null : st.optString("pais_salida", null);
        String pais = ModoPrivacidad.usaTor(modo) && paisTorReal != null ? paisTorReal : paisVpn;
        paisTxt.setText(pais != null && !ModoPrivacidad.APAGADO.equals(modo)
                ? Ubicaciones.bandera(pais) + "  Los sitios te ven en " + Ubicaciones.nombre(pais) : "");
        ipTxt.setText(vpn && !st.isNull("ip_salida") ? "IP\n" + st.optString("ip_salida") : "");
        long rx = st.optLong("recibidos_bytes", -1), tx = st.optLong("enviados_bytes", -1), ahora = System.currentTimeMillis();
        if (vpn && rx >= 0 && rxPrev >= 0 && ahora > tPrev) {
            double s = (ahora - tPrev) / 1000.0;
            velTxt.setText("↓ " + velocidad((rx - rxPrev) / s) + "\n↑ " + velocidad((tx - txPrev) / s));
        } else velTxt.setText(vpn ? "↓ —\n↑ —" : "");
        rxPrev = rx; txPrev = tx; tPrev = ahora;
        long seg = st.optLong("segundos_conectado", 0);
        tiempoTxt.setText(vpn && seg > 0 ? "Conectado\n" + String.format(Locale.ROOT, "%d:%02d:%02d", seg / 3600, seg / 60 % 60, seg % 60) : "");
        torTxt.setText(ModoPrivacidad.usaTor(modo) ? (tor ? "Tor activo · proxy 127.0.0.1:" + TorInterno.puertoHttp()
                + (vpn ? " · Tor sobre VPN" : "") : torArranca ? "Tor conectando a sus 3 relevos…" : "Tor apagado") : "");
    }

    private static String velocidad(double bps) {
        if (bps < 0) bps = 0;
        if (bps < 1024) return String.format(Locale.ROOT, "%.0f B/s", bps);
        if (bps < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB/s", bps / 1024);
        return String.format(Locale.ROOT, "%.1f MB/s", bps / 1024 / 1024);
    }

    private void botonPrincipal() {
        if (ocupado) return;
        if (!ModoPrivacidad.APAGADO.equals(ModoPrivacidad.actual(this))) { aplicar(ModoPrivacidad.APAGADO); return; }
        if (ModoPrivacidad.usaProton(modoElegido)) {
            if (motor.ubicaciones().isEmpty() && motor.almacen().perfil() == null) {
                Ui.mensaje(this, "Falta una ubicación", "Abajo, en «Ubicaciones», toca «Usar mi servidor GuardianIA» o agrega "
                        + "ubicaciones de ProtonVPN. Mientras tanto puedes usar el modo «Tor».");
                return;
            }
            Intent pedir = MotorVpn.permiso(this);
            if (pedir != null) { ocupado = true; permisoVpn.launch(pedir); return; }
        }
        aplicar(modoElegido);
    }

    private void aplicar(String modo) {
        ocupado = true;
        razonTxt.setText("");
        pintarVivo();
        Hilos.<JSONObject>tarea(() -> ModoPrivacidad.cambiar(this, modo, proposito, paisDestino), r -> {
            ocupado = false;
            if (r != null && r.has("razon")) razonTxt.setText("GuardianIA: " + r.optString("razon")
                    + (r.optInt("intentos", 1) > 1 ? " (probé " + r.optInt("intentos") + " ubicaciones)" : ""));
            pintarModos();
            pintarUbicaciones();
            pintarVivo();
            pintarAnonimato(false);
            if (ModoPrivacidad.APAGADO.equals(modo) && TorVerificacion.wifiUsaProxy(this, "127.0.0.1", 8118)) {
                Ui.mensaje(this, "Quita el proxy del WiFi", "Tor se apagó, pero tu WiFi sigue usando 127.0.0.1:8118. "
                        + "Ve a WiFi → tu red → Proxy → Ninguno, o esa red se quedará sin internet.");
            }
        }, e -> {
            ocupado = false;
            pintarModos();
            pintarVivo();
            Ui.mensaje(this, "No se pudo activar", e.getMessage());
        });
    }

    // ─────────────────────────── modo y propósito
    private void pintarModos() {
        modos.removeAllViews();
        String[][] ms = {{ModoPrivacidad.PROTON, "ProtonVPN"}, {ModoPrivacidad.TOR, "Tor"}, {ModoPrivacidad.PROTON_TOR, "Proton + Tor"}};
        for (String[] m : ms) {
            View b = Ui.botonChico(this, m[1], m[0].equals(modoElegido) ? Ui.Estilo.PRIMARIO : Ui.Estilo.SECUNDARIO);
            b.setOnClickListener(v -> {
                modoElegido = m[0];
                pintarModos();
                if (!ModoPrivacidad.APAGADO.equals(ModoPrivacidad.actual(this))) aplicar(m[0]);   // cambia en caliente
            });
            modos.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        razonTxt.setText(ModoPrivacidad.PROTON_TOR.equals(modoElegido) && razonTxt.getText().length() == 0
                ? "Recomendado: tu operador solo ve Proton; Proton solo ve Tor cifrado; los sitios solo ven un relevo Tor." : razonTxt.getText());
    }

    private void pintarPropositos() {
        propositos.removeAllViews();
        LinearLayout f = Ui.fila(this);
        String[][] ps = {{RecomendadorUbicacion.ANONIMATO, "Anonimato"}, {RecomendadorUbicacion.DESBLOQUEAR, "Desbloquear país"},
                {RecomendadorUbicacion.GENERAL, "Uso general"}};
        for (String[] x : ps) {
            View b = Ui.botonChico(this, x[1], x[0].equals(proposito) ? Ui.Estilo.PRIMARIO : Ui.Estilo.SECUNDARIO);
            b.setOnClickListener(v -> {
                proposito = x[0];
                if (RecomendadorUbicacion.DESBLOQUEAR.equals(proposito)) elegirPaisDestino();
                pintarPropositos();
                pintarUbicaciones();
            });
            f.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        propositos.addView(f);
        if (RecomendadorUbicacion.DESBLOQUEAR.equals(proposito) && paisDestino != null) {
            propositos.addView(Ui.pequeno(this, "Quieres aparecer en: " + Ubicaciones.bandera(paisDestino) + " "
                    + Ubicaciones.nombre(paisDestino) + ". Las plataformas de streaming suelen bloquear VPN gratuitas y Tor; "
                    + "noticias y tiendas sí funcionan."));
        }
    }

    private void elegirPaisDestino() {
        String[] codigos = Ubicaciones.PAISES.keySet().stream().filter(k -> !"UK".equals(k)).toArray(String[]::new);
        CharSequence[] nombres = new CharSequence[codigos.length];
        for (int i = 0; i < codigos.length; i++) nombres[i] = Ubicaciones.bandera(codigos[i]) + "  " + Ubicaciones.nombre(codigos[i]);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("¿Desde qué país quieres aparecer?")
                .setItems(nombres, (d, w) -> { paisDestino = codigos[w]; pintarPropositos(); pintarUbicaciones();
                    if (ModoPrivacidad.usaTor(modoElegido) && java.util.Arrays.asList(Ubicaciones.PAISES_TOR).contains(paisDestino))
                        TorInterno.fijarPais(this, paisDestino); })
                .setNegativeButton("Cancelar", null).show();
    }

    // ─────────────────────────── ubicaciones de ProtonVPN
    private void pintarUbicaciones() {
        ubicCaja.removeAllViews();
        List<JSONObject> lista = RecomendadorUbicacion.ordenar(motor.ubicaciones(), proposito,
                Locale.getDefault().getCountry(), paisDestino);
        if (!motor.tienePropio()) ubicCaja.addView(tarjetaPropio());
        LinearLayout t = Ui.tarjeta(this);
        t.addView(Ui.parrafo(this, "Ubicaciones de ProtonVPN (opcional, para cambiar de país)"));
        if (!ImportadorCarpeta.vinculada(this)) {
            t.addView(Ui.parrafo(this, "Configúralo UNA sola vez:"));
            t.addView(Ui.pequeno(this, "1. En account.protonvpn.com → Downloads → WireGuard, descarga un archivo por cada país "
                    + "gratuito (quedan en Descargas).\n2. Toca «Vincular Descargas»: desde entonces cada .conf nuevo se importa solo."));
            Ui.en(t, Ui.boton(this, "Vincular Descargas (automático)", Ui.Estilo.PRIMARIO)).setOnClickListener(v -> carpeta.launch(null));
            Ui.en(t, Ui.botonChico(this, "Abrir la página de Proton", Ui.Estilo.FANTASMA)).setOnClickListener(v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://account.protonvpn.com/downloads"))); } catch (Exception ignored) {}
            });
        } else {
            t.addView(Ui.pequeno(this, "✓ Carpeta vinculada: los .conf nuevos se importan solos al abrir esta pantalla."));
        }
        Ui.en(t, Ui.botonChico(this, "Importar archivos a mano", Ui.Estilo.SECUNDARIO))
                .setOnClickListener(v -> archivos.launch(new String[]{"*/*"}));
        ubicCaja.addView(t);
        if (lista.isEmpty()) return;
        JSONObject actual = motor.almacen().perfil();
        String enUso = actual != null ? actual.optString("id", "") : "";
        for (int i = 0; i < lista.size(); i++) {
            JSONObject u = lista.get(i);
            String pais = u.isNull("pais") ? null : u.optString("pais", null), id = u.optString("id");
            LinearLayout f = Ui.tarjetaEstado(this, id.equals(enUso) ? Ui.OK : i == 0 ? Ui.ACENTO : Ui.BORDE);
            TextView tit = Ui.parrafo(this, (u.optBoolean("propio") ? "🛡  Tu servidor GuardianIA · " : "")
                    + Ubicaciones.bandera(pais) + "  " + Ubicaciones.nombre(pais)
                    + (u.optBoolean("verificado") ? "  ✓" : "") + (id.equals(enUso) ? "  · en uso" : i == 0 ? "  · recomendada" : ""));
            f.addView(tit);
            f.addView(Ui.pequeno(this, u.optString("nombre") + " · " + RecomendadorUbicacion.razon(u, proposito,
                    Locale.getDefault().getCountry(), paisDestino)));
            LinearLayout acc = Ui.fila(this);
            View usar = Ui.botonChico(this, id.equals(enUso) ? "En uso" : "Usar esta", Ui.Estilo.SECUNDARIO);
            usar.setEnabled(!id.equals(enUso));
            usar.setOnClickListener(v -> Hilos.tarea(() -> motor.usarUbicacion(id), r -> {
                pintarUbicaciones(); Ui.aviso(this, motor.activo() ? "Reconectado desde " + Ubicaciones.nombre(pais) : "Lista: toca «Activar privacidad».");
            }, e -> Ui.aviso(this, e.getMessage())));
            acc.addView(usar);
            View quitar = Ui.botonChico(this, "Eliminar", Ui.Estilo.FANTASMA);
            quitar.setOnClickListener(v -> Ui.confirmar(this, "Eliminar ubicación", "Se borrará " + u.optString("nombre")
                    + " y su clave cifrada.", "Eliminar", () -> { motor.eliminarUbicacion(id); pintarUbicaciones(); }));
            acc.addView(quitar);
            f.addView(acc);
            ubicCaja.addView(f);
            Ui.entrar(f);
        }
    }

    /**
     * Tu servidor VPN propio (Google Cloud): el PC ya tiene los datos secretos; aquí solo se pide el alta con
     * la sesión del dueño. Este teléfono genera su clave y solo envía la PÚBLICA.
     */
    private View tarjetaPropio() {
        LinearLayout t = Ui.tarjetaEstado(this, Ui.ACENTO);
        t.addView(Ui.parrafo(this, "🛡  Tu servidor GuardianIA VPN"));
        t.addView(Ui.pequeno(this, "Usa tu propio servidor (sin Proton, sin Tor, sin archivos). Primero conéctalo en el PC: "
                + "Protección → VPN → «Mi servidor GuardianIA VPN». Tu teléfono debe estar en la misma red que el PC solo esta vez."));
        View usar = Ui.boton(this, "Usar mi servidor GuardianIA", Ui.Estilo.PRIMARIO);
        usar.setOnClickListener(v -> {
            if (!co.guardiania.app.net.Sesion.de(this).iniciada()) {
                Ui.mensaje(this, "Inicia sesión", "Para dar de alta este teléfono, inicia sesión en GuardianIA con la misma cuenta del PC.");
                return;
            }
            usar.setEnabled(false);
            Ui.aviso(this, "Pidiendo el alta a tu PC…");
            Hilos.<JSONObject>tarea(() -> {
                JSONObject e = co.guardiania.app.net.Sesion.de(this).obj("GET", "/vpn/propio", null);
                if (!e.optBoolean("configurado")) {
                    throw new java.io.IOException("Tu PC aún no tiene conectado el servidor. En el PC: Protección → VPN → "
                            + "«Mi servidor GuardianIA VPN» y pega los 3 datos del instalador.");
                }
                return motor.configurarPropio();
            }, perfil -> {
                pintarUbicaciones();
                Ui.mensaje(this, "Servidor listo", "Este teléfono ya está dado de alta en tu servidor ("
                        + Ubicaciones.bandera(perfil.optString("pais", null)) + " " + Ubicaciones.nombre(perfil.optString("pais", null))
                        + "). Toca «Activar privacidad» en modo ProtonVPN o Proton + Tor: GuardianIA usará tu servidor.");
            }, e -> { usar.setEnabled(true); Ui.mensaje(this, "No se pudo", e.getMessage()); });
        });
        t.addView(usar);
        return t;
    }

    private void vincularCarpeta(@Nullable Uri arbol) {
        if (arbol == null) return;
        try { ImportadorCarpeta.vincular(this, arbol); } catch (Exception e) { Ui.aviso(this, "No se pudo guardar el permiso."); return; }
        Ui.aviso(this, "Carpeta vinculada. Buscando archivos de ProtonVPN…");
        Hilos.<ImportadorCarpeta.Resultado>tarea(() -> ImportadorCarpeta.importarNuevos(this), r -> {
            pintarUbicaciones();
            Ui.mensaje(this, "Ubicaciones", r.nuevas + " importada(s)." + (r.rechazadas > 0 ? "\n\nRechazadas por seguridad:\n" + r.motivos : "")
                    + (r.nuevas == 0 && r.rechazadas == 0 ? "\n\nNo encontré archivos .conf de WireGuard en esa carpeta todavía." : ""));
        }, e -> Ui.aviso(this, e.getMessage()));
    }

    private void importarArchivos(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        Hilos.<int[]>tarea(() -> {
            int ok = 0, mal = 0;
            for (Uri uri : uris) {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while (is != null && (n = is.read(buf)) != -1 && bos.size() <= 20_000) bos.write(buf, 0, n);
                    String nombre = uri.getLastPathSegment() == null ? "VPN importada"
                            : uri.getLastPathSegment().replaceAll(".*/", "").replaceAll("(?i)\\.conf$", "");
                    if (motor.importar(bos.toString("UTF-8"), nombre, false) != null) ok++;
                } catch (Exception e) { mal++; }
            }
            return new int[]{ok, mal};
        }, r -> { pintarUbicaciones(); Ui.aviso(this, r[0] + " ubicación(es) importada(s)" + (r[1] > 0 ? ", " + r[1] + " rechazada(s)" : "")); },
                e -> Ui.aviso(this, e.getMessage()));
    }

    // ─────────────────────────── Tor
    private void pintarTor() {
        if (torCaja == null) return;
        torCaja.removeAllViews();
        LinearLayout t = Ui.tarjeta(this);
        String pais = TorInterno.pais(this);
        t.addView(Ui.parrafo(this, "País de salida: " + (pais == null ? "🌐 el más rápido" : Ubicaciones.bandera(pais) + " " + Ubicaciones.nombre(pais))));
        Ui.en(t, Ui.botonChico(this, "Cambiar país de salida", Ui.Estilo.SECUNDARIO)).setOnClickListener(v -> elegirPaisTor());
        if (TorInterno.activo()) {
            int http = TorInterno.puertoHttp();
            boolean wifi = TorVerificacion.wifiUsaProxy(this, "127.0.0.1", http);
            TextView uso = Ui.pequeno(this, wifi ? "✓ Tu WiFi usa Tor: navega normal en Chrome (sitios https)."
                    : "Para Chrome: WiFi → tu red → Proxy → Manual → 127.0.0.1, puerto " + http + ". "
                    + "No lo escribas en la barra de direcciones: es un túnel, no una página. Funciona con sitios https://.");
            uso.setTextColor(wifi ? Ui.OK : Ui.TEXTO2);
            t.addView(uso);
            if (!wifi) Ui.en(t, Ui.botonChico(this, "Copiar y abrir el WiFi", Ui.Estilo.PRIMARIO)).setOnClickListener(v -> {
                Ui.copiar(this, "proxy Tor", "127.0.0.1:" + http);
                try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) {}
            });
            if (verifTor != null) {
                TextView r = Ui.pequeno(this, verifTor.esTor ? "✓ Verificado por el Tor Project (IP " + verifTor.ipSalida
                        + (paisTorReal != null ? ", " + Ubicaciones.bandera(paisTorReal) + " " + Ubicaciones.nombre(paisTorReal) : "") + ")."
                        : "✗ Aún no sale por Tor (" + verifTor.via + ").");
                r.setTextColor(verifTor.esTor ? Ui.OK : Ui.PELIGRO);
                t.addView(r);
            }
            Ui.en(t, Ui.botonChico(this, "Verificar con el Tor Project", Ui.Estilo.FANTASMA)).setOnClickListener(v -> {
                Ui.aviso(this, "Preguntando al Tor Project…");
                Hilos.<TorVerificacion.Verificacion>tarea(() -> { TorVerificacion.Verificacion x = TorInterno.verificar(); paisTorReal = TorInterno.paisReal(); return x; },
                        x -> { verifTor = x; pintarTor(); pintarVivo(); }, e -> Ui.aviso(this, e.getMessage()));
            });
        } else {
            t.addView(Ui.pequeno(this, "Tor se enciende con el modo «Tor» o «Proton + Tor». Si hay pocos relevos en el país "
                    + "elegido, Tor no saldrá por otro (no te engaña)."));
        }
        torCaja.addView(t);
    }

    private void elegirPaisTor() {
        String[] cods = Ubicaciones.PAISES_TOR;
        CharSequence[] nombres = new CharSequence[cods.length + 1];
        nombres[0] = "🌐  El más rápido";
        for (int i = 0; i < cods.length; i++) nombres[i + 1] = Ubicaciones.bandera(cods[i]) + "  " + Ubicaciones.nombre(cods[i]);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("País de salida de Tor")
                .setItems(nombres, (d, w) -> {
                    TorInterno.fijarPais(this, w == 0 ? null : cods[w - 1]);
                    verifTor = null; paisTorReal = null;
                    pintarTor();
                    Ui.aviso(this, TorInterno.activo() ? "Tor se reinicia para salir por el nuevo país." : "Guardado.");
                }).setNegativeButton("Cancelar", null).show();
    }

    // ─────────────────────────── anonimato
    private void pintarAnonimato(boolean repetir) {
        String modo = ModoPrivacidad.actual(this);
        if (ModoPrivacidad.APAGADO.equals(modo)) {
            anonCaja.removeAllViews();
            anonCaja.addView(Ui.pequeno(this, "Activa la privacidad para ver tu nivel de anonimato."));
            return;
        }
        Hilos.<Anonimato.Resultado>tarea(() -> {
            if (ModoPrivacidad.TOR.equals(modo)) return Anonimato.evaluar(TorInterno.activo(), null, false, null, "tor", "Tor", paisTorReal, true);
            Anonimato.Resultado vpn = motor.anonimato(repetir);
            if (!ModoPrivacidad.PROTON_TOR.equals(modo) || !TorInterno.activo()) return vpn;
            // Tor sobre VPN: el operador del último salto es Tor (nadie conoce a la vez quién y qué).
            return Anonimato.evaluar(true, null, false, null, "tor", "Tor", paisTorReal, true);
        }, r -> {
            anonCaja.removeAllViews();
            int color = "alto".equals(r.nivel) ? Ui.OK : "medio".equals(r.nivel) ? Ui.AVISO : Ui.PELIGRO;
            LinearLayout caja = Ui.tarjetaEstado(this, color);
            caja.addView(Ui.subtitulo(this, r.nivel.substring(0, 1).toUpperCase(Locale.ROOT) + r.nivel.substring(1) + " · " + r.puntaje + "/100"));
            for (Anonimato.Revision x : r.revisiones) {
                TextView l = Ui.pequeno(this, ("ok".equals(x.estado) ? "✓ " : "aviso".equals(x.estado) ? "⚠ " : "✗ ") + x.texto);
                l.setTextColor("ok".equals(x.estado) ? Ui.TEXTO2 : "aviso".equals(x.estado) ? Ui.AVISO : Ui.PELIGRO);
                caja.addView(l);
            }
            TextView lim = Ui.pequeno(this, "Lo que ninguna VPN ni Tor puede ocultar ▸");
            lim.setTextColor(Ui.ACENTO);
            lim.setOnClickListener(v -> Ui.mensaje(this, "Límites honestos", "• " + String.join("\n\n• ", Anonimato.LIMITES)));
            caja.addView(lim);
            anonCaja.addView(caja);
        }, e -> {});
    }
}
