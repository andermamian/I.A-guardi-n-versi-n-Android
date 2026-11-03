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

import java.util.Locale;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.ui.kit.Ui;
import co.guardiania.app.vpn.Anonimato;
import co.guardiania.app.vpn.CerebroVpn;
import co.guardiania.app.vpn.ModoPrivacidad;
import co.guardiania.app.vpn.MotorVpn;
import co.guardiania.app.vpn.TorInterno;
import co.guardiania.app.vpn.TorVerificacion;
import co.guardiania.app.vpn.Ubicaciones;

/**
 * Privacidad del teléfono con UN botón y SOLO dos vías: tu servidor GuardianIA VPN (Google Cloud) y Tor.
 *
 * <p>v3.8: se retiraron ProtonVPN, WARP y los .conf de terceros (Android admite una sola VPN y varias vías
 * generaban conflictos). En «Automático (IA)» {@link CerebroVpn} elige la vía, explica por qué, aprende de
 * cada conexión y cambia sola si algo empeora; con la pantalla abierta se revisa cada 30 s. Si tu servidor
 * falla, nunca quedas desprotegido: pasa a Tor y vuelve a tu servidor cuando responde.
 */
public class VpnActivity extends AppCompatActivity {

    private MotorVpn motor;
    private TextView orbe, estadoTxt, paisTxt, ipTxt, velTxt, tiempoTxt, torTxt, razonTxt;
    private View boton;
    private LinearLayout modos, objetivos, iaCaja, servidorCaja, torCaja, anonCaja;
    private boolean ocupado = false;
    private String modoElegido, objetivo;
    private long rxPrev = -1, txPrev = -1, tPrev = 0;
    private TorVerificacion.Verificacion verifTor;
    private String paisTorReal;

    private final ActivityResultLauncher<Intent> permisoVpn = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (MotorVpn.permiso(this) == null) aplicar(modoElegido);
                else { ocupado = false; pintarVivo(); Ui.aviso(this, "Sin el permiso de VPN no se puede usar tu servidor. Prueba el modo «Tor»."); }
            });

    private final TorInterno.Oyente oyenteTor = (e, s, h) -> { pintarTor(); pintarVivo(); };
    private final Runnable vivo = new Runnable() {
        @Override public void run() { pintarVivo(); Hilos.uiLuego(this, 1000); }
    };
    /** Vigilante con la pantalla abierta: la IA revisa cada 30 s y repara o cambia de vía. */
    private final Runnable vigilante = new Runnable() {
        @Override public void run() {
            if (!ocupado) {
                Hilos.<String>tarea(() -> ModoPrivacidad.revisar(VpnActivity.this), hecho -> {
                    if (hecho != null) {
                        pintarVivo(); pintarIA(); pintarAnonimato(false);
                        if ("cambio_ia".equals(hecho)) Ui.aviso(VpnActivity.this, "La IA cambió a " + ModoPrivacidad.nombre(ModoPrivacidad.efectivo(VpnActivity.this)));
                        else if ("restaurado".equals(hecho)) Ui.aviso(VpnActivity.this, "Tu servidor volvió: regresé a él.");
                        else if ("respaldo_tor".equals(hecho)) Ui.aviso(VpnActivity.this, "Tu servidor no responde: te protegí con Tor.");
                    }
                }, e -> {});
            }
            Hilos.uiLuego(this, 30_000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        motor = MotorVpn.de(this);
        modoElegido = ModoPrivacidad.actual(this);
        if (ModoPrivacidad.APAGADO.equals(modoElegido)) modoElegido = ModoPrivacidad.AUTO;
        objetivo = ModoPrivacidad.objetivo(this);

        LinearLayout raiz = Ui.columna(this);
        raiz.setBackgroundColor(Ui.FONDO);
        raiz.addView(Ui.barraSuperior(this, "Privacidad"));
        LinearLayout p = Ui.pagina(this);

        p.addView(panelVivo());
        p.addView(Ui.seccion(this, "Vía"));
        modos = Ui.columna(this);
        p.addView(modos);
        p.addView(Ui.seccion(this, "Prioridad"));
        objetivos = Ui.fila(this);
        p.addView(objetivos);
        pintarModos();
        p.addView(Ui.seccion(this, "Decisión de la IA"));
        iaCaja = Ui.columna(this);
        p.addView(iaCaja);
        p.addView(Ui.seccion(this, "Tu servidor GuardianIA VPN"));
        servidorCaja = Ui.columna(this);
        p.addView(servidorCaja);
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
        pintarServidor();
        pintarTor();
        pintarIA();
    }

    @Override protected void onResume() {
        super.onResume();
        TorInterno.observar(oyenteTor);
        Hilos.ui(vivo);
        Hilos.uiLuego(vigilante, 2000);
        pintarAnonimato(false);
    }

    @Override protected void onPause() {
        super.onPause();
        Hilos.cancelar(vivo);
        Hilos.cancelar(vigilante);
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
        boton = Ui.boton(this, "Proteger ahora", Ui.Estilo.PRIMARIO);
        boton.setMinimumHeight(Ui.dp(this, 58));
        boton.setOnClickListener(v -> botonPrincipal());
        Ui.margen(boton, 0, 12, 0, 0);
        t.addView(boton);
        return t;
    }

    private void pintarVivo() {
        String elegido = ModoPrivacidad.actual(this), modo = ModoPrivacidad.efectivo(this);
        boolean vpn = motor.activo(), tor = TorInterno.activo(), torArranca = "STARTING".equals(TorInterno.estado());
        boolean protegido = (ModoPrivacidad.usaServidor(modo) ? vpn : true) && (ModoPrivacidad.usaTor(modo) ? tor : true)
                && !ModoPrivacidad.APAGADO.equals(modo);
        boolean enCurso = ocupado || (ModoPrivacidad.usaTor(modo) && torArranca);
        int color = protegido ? Ui.OK : enCurso ? Ui.AVISO : Ui.TEXTO2;
        orbe.setTextColor(color);
        orbe.animate().scaleX(protegido ? 1.12f : 1f).scaleY(protegido ? 1.12f : 1f).setDuration(600).start();
        estadoTxt.setText(ocupado ? "Conectando…" : protegido ? "Protegido · " + ModoPrivacidad.nombre(modo)
                + (ModoPrivacidad.AUTO.equals(elegido) ? " (IA)" : "") + (ModoPrivacidad.respaldo(this) ? " · respaldo" : "")
                : enCurso ? "Conectando a la red Tor…" : "Sin protección");
        ((android.widget.Button) boton).setText(ocupado ? "Un momento…" : ModoPrivacidad.APAGADO.equals(elegido)
                ? "Proteger ahora" : "Apagar protección");
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
                + (vpn ? " · Tor dentro de tu servidor" : "") : torArranca ? "Tor conectando a sus 3 relevos…" : "Tor apagado") : "");
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
        if (ModoPrivacidad.usaServidor(modoElegido) && !motor.tienePropio()) {
            Ui.mensaje(this, "Falta tu servidor", "Abajo, en «Tu servidor GuardianIA VPN», toca «Usar mi servidor GuardianIA». "
                    + "Mientras tanto usa «Tor» o «Automático (IA)».");
            return;
        }
        // Tu servidor necesita el permiso de VPN de Android (también en automático: la IA puede elegirlo).
        if ((ModoPrivacidad.usaServidor(modoElegido) || ModoPrivacidad.AUTO.equals(modoElegido)) && motor.tienePropio()) {
            Intent pedir = MotorVpn.permiso(this);
            if (pedir != null) { ocupado = true; pintarVivo(); permisoVpn.launch(pedir); return; }
        }
        aplicar(modoElegido);
    }

    private void aplicar(String modo) {
        ocupado = true;
        razonTxt.setText("");
        pintarVivo();
        Hilos.<JSONObject>tarea(() -> ModoPrivacidad.cambiar(this, modo, objetivo), r -> {
            ocupado = false;
            if (r != null && r.has("razon")) razonTxt.setText("GuardianIA: " + r.optString("razon"));
            if (r != null && !r.optString("aviso").isEmpty()) Ui.aviso(this, r.optString("aviso"));
            pintarModos();
            pintarVivo();
            pintarIA();
            pintarAnonimato(false);
            if (ModoPrivacidad.APAGADO.equals(modo) && TorVerificacion.wifiUsaProxy(this, "127.0.0.1", 8118)) {
                Ui.mensaje(this, "Quita el proxy del WiFi", "Tor se apagó, pero tu WiFi sigue usando 127.0.0.1:8118. "
                        + "Ve a WiFi → tu red → Proxy → Ninguno, o esa red se quedará sin internet.");
            }
        }, e -> {
            ocupado = false;
            pintarModos();
            pintarVivo();
            pintarIA();
            Ui.mensaje(this, "No se pudo activar", e.getMessage());
        });
    }

    // ─────────────────────────── vía y prioridad
    private void pintarModos() {
        modos.removeAllViews();
        String[][] ms = {{ModoPrivacidad.AUTO, "🤖 Automático (IA)"}, {ModoPrivacidad.SERVIDOR, "Mi servidor"},
                {ModoPrivacidad.TOR, "Tor"}, {ModoPrivacidad.SERVIDOR_TOR, "Servidor + Tor"}};
        LinearLayout f1 = Ui.fila(this), f2 = Ui.fila(this);
        for (int i = 0; i < ms.length; i++) {
            String[] m = ms[i];
            View b = Ui.botonChico(this, m[1], m[0].equals(modoElegido) ? Ui.Estilo.PRIMARIO : Ui.Estilo.SECUNDARIO);
            b.setOnClickListener(v -> {
                modoElegido = m[0];
                pintarModos();
                if (!ModoPrivacidad.APAGADO.equals(ModoPrivacidad.actual(this))) botonCambio();
            });
            (i < 2 ? f1 : f2).addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        modos.addView(f1);
        modos.addView(f2);
        modos.addView(Ui.pequeno(this, ModoPrivacidad.AUTO.equals(modoElegido)
                ? "La IA elige entre tu servidor y Tor según la red, tu servidor, el cupo del mes y lo que aprendió aquí; cambia sola si algo empeora."
                : ModoPrivacidad.SERVIDOR.equals(modoElegido) ? "Túnel a tu servidor de Google Cloud: rápido y cifra todo el teléfono."
                : ModoPrivacidad.TOR.equals(modoElegido) ? "Tres relevos Tor: nadie une quién eres con lo que visitas. Más lento."
                : "Tor dentro de tu servidor: tu operador solo ve tu servidor y el sitio solo ve un relevo Tor."));
        objetivos.removeAllViews();
        String[][] os = {{CerebroVpn.EQUILIBRIO, "Equilibrio"}, {CerebroVpn.VELOCIDAD, "Velocidad"}, {CerebroVpn.ANONIMATO, "Anonimato"}};
        for (String[] o : os) {
            View b = Ui.botonChico(this, o[1], o[0].equals(objetivo) ? Ui.Estilo.PRIMARIO : Ui.Estilo.SECUNDARIO);
            b.setOnClickListener(v -> {
                objetivo = o[0];
                pintarModos();
                if (!ModoPrivacidad.APAGADO.equals(ModoPrivacidad.actual(this))) botonCambio();
            });
            objetivos.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    /** Cambio en caliente (ya estaba protegido): mismo camino que el botón, sin apagar primero. */
    private void botonCambio() {
        if (ocupado) return;
        if ((ModoPrivacidad.usaServidor(modoElegido) || ModoPrivacidad.AUTO.equals(modoElegido)) && motor.tienePropio()
                && MotorVpn.permiso(this) != null) {
            ocupado = true;
            permisoVpn.launch(MotorVpn.permiso(this));
            return;
        }
        aplicar(modoElegido);
    }

    // ─────────────────────────── lo que la IA decidió y por qué
    private void pintarIA() {
        if (iaCaja == null) return;
        iaCaja.removeAllViews();
        JSONObject d = ModoPrivacidad.ultimaDecision(this);
        if (!d.has("modo") || d.isNull("modo")) {
            iaCaja.addView(Ui.pequeno(this, "Toca «Proteger ahora»: la IA mirará tu servidor, Tor y tu red, y te dirá qué eligió y por qué."));
            return;
        }
        String modo = d.optString("modo");
        LinearLayout t = Ui.tarjetaEstado(this, Ui.ACENTO);
        t.addView(Ui.parrafo(this, (ModoPrivacidad.AUTO.equals(ModoPrivacidad.actual(this)) ? "Eligió: " : "Recomienda: ")
                + ModoPrivacidad.nombre(modo) + " · confianza " + Math.round(d.optDouble("confianza") * 100) + " %"));
        org.json.JSONArray rz = d.optJSONArray("razones");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; rz != null && i < rz.length(); i++) sb.append(i == 0 ? "" : " ").append(rz.optString(i));
        t.addView(Ui.pequeno(this, sb.toString()));
        JSONObject pj = d.optJSONObject("puntajes");
        if (pj != null) {
            StringBuilder ps = new StringBuilder("Puntajes: ");
            for (java.util.Iterator<String> it = pj.keys(); it.hasNext(); ) {
                String k = it.next();
                ps.append(ModoPrivacidad.nombre(k)).append(" ").append(Math.round(Math.max(0, pj.optDouble(k)) * 100)).append(it.hasNext() ? " · " : "");
            }
            t.addView(Ui.pequeno(this, ps.toString()));
        }
        JSONObject mem = ModoPrivacidad.memoria(this);
        StringBuilder ap = new StringBuilder();
        for (String m : CerebroVpn.CANDIDATOS) {
            JSONObject x = mem.optJSONObject(m);
            if (x == null || x.optInt("intentos") == 0) continue;
            ap.append(ap.length() == 0 ? "Aprendido aquí: " : " · ").append(ModoPrivacidad.nombre(m)).append(" ")
                    .append(Math.round(x.optDouble("fiabilidad") * 100)).append(" % de éxito (").append(x.optInt("intentos")).append(")");
        }
        if (ap.length() > 0) t.addView(Ui.pequeno(this, ap.toString()));
        iaCaja.addView(t);
    }

    // ─────────────────────────── tu servidor GuardianIA VPN
    private void pintarServidor() {
        servidorCaja.removeAllViews();
        if (!motor.tienePropio()) { servidorCaja.addView(tarjetaPropio("Usar mi servidor GuardianIA")); return; }
        LinearLayout t = Ui.tarjetaEstado(this, Ui.OK);
        JSONObject u = motor.ubicaciones().get(0);
        String pais = u.isNull("pais") ? null : u.optString("pais", null);
        t.addView(Ui.parrafo(this, "🛡  " + Ubicaciones.bandera(pais) + "  " + u.optString("nombre", "GuardianIA VPN") + "  ✓"));
        t.addView(Ui.pequeno(this, "Este teléfono ya tiene su clave en tu servidor. La privada nunca salió del teléfono."));
        servidorCaja.addView(t);
        servidorCaja.addView(tarjetaPropio("Volver a dar de alta este teléfono"));
    }

    /**
     * Alta en tu servidor (Google Cloud) a través del PC, con la sesión del dueño: este teléfono genera su
     * clave y solo envía la PÚBLICA. La ficha secreta del servidor nunca llega aquí.
     */
    private View tarjetaPropio(String texto) {
        LinearLayout t = Ui.tarjeta(this);
        if (!motor.tienePropio()) t.addView(Ui.pequeno(this, "Primero conéctalo en el PC: Protección → VPN → «Mi servidor GuardianIA VPN». "
                + "Tu teléfono debe estar en la misma WiFi que el PC solo esta vez. Sin servidor, la IA usa Tor."));
        View usar = Ui.botonChico(this, texto, motor.tienePropio() ? Ui.Estilo.FANTASMA : Ui.Estilo.PRIMARIO);
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
                            + "«Mi servidor GuardianIA VPN».");
                }
                return motor.configurarPropio();
            }, perfil -> {
                pintarServidor();
                Ui.mensaje(this, "Servidor listo", "Este teléfono ya está dado de alta en tu servidor ("
                        + Ubicaciones.bandera(perfil.optString("pais", null)) + " " + Ubicaciones.nombre(perfil.optString("pais", null))
                        + "). Toca «Proteger ahora»: la IA lo usará cuando convenga.");
            }, e -> { usar.setEnabled(true); Ui.mensaje(this, "No se pudo", e.getMessage()); });
        });
        t.addView(usar);
        return t;
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
            t.addView(Ui.pequeno(this, "Tor se enciende con la vía «Tor», «Servidor + Tor» o cuando la IA lo elige. Si hay pocos "
                    + "relevos en el país elegido, Tor no saldrá por otro (no te engaña)."));
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
        String modo = ModoPrivacidad.efectivo(this);
        if (ModoPrivacidad.APAGADO.equals(modo)) {
            anonCaja.removeAllViews();
            anonCaja.addView(Ui.pequeno(this, "Activa la protección para ver tu nivel de anonimato."));
            return;
        }
        Hilos.<Anonimato.Resultado>tarea(() -> {
            if (ModoPrivacidad.TOR.equals(modo)) return Anonimato.evaluar(TorInterno.activo(), null, false, null, "tor", "Tor", paisTorReal, true);
            Anonimato.Resultado vpn = motor.anonimato(repetir);
            if (!ModoPrivacidad.SERVIDOR_TOR.equals(modo) || !TorInterno.activo()) return vpn;
            // Tor dentro de tu servidor: el último salto es Tor (nadie conoce a la vez quién y qué).
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
