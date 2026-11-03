package co.guardiania.app.ui.pantallas;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.data.Vinculo;
import co.guardiania.app.net.DeviceApi;
import co.guardiania.app.service.TrackingService;
import co.guardiania.app.ui.MainActivity;
import co.guardiania.app.ui.kit.Ui;

/**
 * «Mi teléfono»: la ubicación en vivo sobre OpenStreetMap (osmdroid, sin Google ni clave de API), el
 * estado de la protección y el botón conectar/desconectar. Se alimenta directo del
 * {@link TrackingService} que ya corre, así que no abre un segundo GPS.
 *
 * <p>Un equipo reportado como robado NO se puede desconectar desde el propio teléfono: el servidor lo
 * rechaza, para que el ladrón tampoco pueda apagar el rastreo.
 */
public class DispositivoFragment implements TrackingService.Escucha {

    private final MainActivity a;
    private final Vinculo vinculo;
    private MapView mapa;
    private Marker punto;
    private Polyline recorrido;
    private TextView estado;
    private View btnConectar;
    private TextView estadoPc;
    private final Runnable refrescoPc = new Runnable() {
        @Override public void run() {
            pintarPc();
            co.guardiania.app.core.Hilos.uiLuego(this, 5000);
        }
    };
    private boolean seguimiento = true, centrado = false;

    public DispositivoFragment(MainActivity a) {
        this.a = a;
        this.vinculo = new Vinculo(a);
    }

    public View crear() {
        if (!vinculo.estaVinculado()) {
            LinearLayout p = Ui.pagina(a);
            p.addView(Ui.titulo(a, "Mi teléfono"));
            p.addView(Ui.parrafo(a, "Vincula este teléfono para verlo en el mapa y poder ubicarlo si se pierde."));
            Ui.en(p, Ui.boton(a, "Vincular este teléfono", Ui.Estilo.PRIMARIO)).setOnClickListener(v -> a.pedirVinculo());
            return Ui.desplazable(a, p);
        }
        Configuration.getInstance().load(a, PreferenceManager.getDefaultSharedPreferences(a));
        Configuration.getInstance().setUserAgentValue("GuardianIA/2.0");
        seguimiento = vinculo.seguimiento();

        LinearLayout raiz = Ui.columna(a);
        raiz.setBackgroundColor(Ui.FONDO);
        mapa = new MapView(a);
        mapa.setTileSource(TileSourceFactory.MAPNIK);
        mapa.setMultiTouchControls(true);
        mapa.getController().setZoom(17.0);
        raiz.addView(mapa, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        recorrido = new Polyline();
        recorrido.getOutlinePaint().setColor(Ui.ACENTO);
        recorrido.getOutlinePaint().setStrokeWidth(Ui.dp(a, 4));
        mapa.getOverlays().add(recorrido);
        punto = new Marker(mapa);
        punto.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        mapa.getOverlays().add(punto);

        LinearLayout barra = Ui.columna(a);
        barra.setBackgroundColor(Ui.TARJETA);
        int p = Ui.dp(a, 16);
        barra.setPadding(p, p, p, p);
        TextView nombre = Ui.subtitulo(a, vinculo.nombre());
        barra.addView(nombre);
        estado = Ui.parrafo(a, "Esperando la primera posición del GPS…");
        barra.addView(estado);
        btnConectar = Ui.boton(a, "", Ui.Estilo.SECUNDARIO);
        btnConectar.setOnClickListener(v -> alternar());
        barra.addView(btnConectar);

        // Conexión con el PC: estado del canal en vivo + botón para reconectar ya.
        estadoPc = Ui.pequeno(a, "");
        barra.addView(estadoPc);
        LinearLayout filaPc = Ui.fila(a);
        View reconectar = Ui.botonChico(a, "Conectar con el PC", Ui.Estilo.PRIMARIO);
        reconectar.setOnClickListener(v -> {
            TrackingService.reconectar(a);
            estadoPc.setText("Conectando con el PC…");
            estadoPc.setTextColor(Ui.ACENTO);
            co.guardiania.app.core.Hilos.uiLuego(this::pintarPc, 4000);
        });
        filaPc.addView(reconectar);
        if (co.guardiania.app.service.Energia.esXiaomi() || !co.guardiania.app.service.Energia.bateriaSinRestriccion(a)) {
            View energia = Ui.botonChico(a, "Que no se desconecte", Ui.Estilo.SECUNDARIO);
            energia.setOnClickListener(v -> guiaEnergia());
            filaPc.addView(energia);
        }
        barra.addView(filaPc);
        raiz.addView(barra);

        Location ya = TrackingService.ultimaConocida();
        if (ya != null) pintar(ya);
        pintarBoton();
        return raiz;
    }

    public void alMostrar() {
        if (mapa == null) return;
        co.guardiania.app.core.Hilos.cancelar(refrescoPc);
        co.guardiania.app.core.Hilos.ui(refrescoPc);
        mapa.onResume();
        TrackingService.observar(this);
    }

    public void alOcultar() {
        co.guardiania.app.core.Hilos.cancelar(refrescoPc);
        if (mapa == null) return;
        TrackingService.dejarDeObservar(this);
        mapa.onPause();
    }

    @Override
    public void onUbicacion(Location loc, int bateria, boolean seg) {
        Hilos.ui(() -> {
            seguimiento = seg;
            vinculo.guardarRitmo(vinculo.intervaloS(), seg);
            pintar(loc);
            estado.setText(String.format("Precisión ±%d m · Batería %d%% · %s",
                    Math.round(loc.getAccuracy()), bateria, seg ? "Reportando" : "Pausado"));
            pintarBoton();
        });
    }

    private void pintar(Location loc) {
        if (mapa == null) return;
        GeoPoint g = new GeoPoint(loc.getLatitude(), loc.getLongitude());
        punto.setPosition(g);
        recorrido.addPoint(g);
        if (!centrado) { mapa.getController().setCenter(g); centrado = true; }
        else if (seguimiento) mapa.getController().animateTo(g);
        mapa.invalidate();
    }

    private void alternar() {
        boolean nuevo = !seguimiento;
        btnConectar.setEnabled(false);
        Hilos.tarea(() -> new DeviceApi(vinculo).seguimiento(nuevo), r -> {
            seguimiento = nuevo;
            vinculo.guardarRitmo(vinculo.intervaloS(), nuevo);
            pintarBoton();
            btnConectar.setEnabled(true);
            Ui.aviso(a, nuevo ? "Reportando ubicación" : "Reporte pausado");
        }, e -> { btnConectar.setEnabled(true); Ui.aviso(a, e.getMessage()); });
    }

    private void pintarPc() {
        if (estadoPc == null) return;
        boolean vivo = TrackingService.enVivoConPc();
        estadoPc.setText(vivo ? "En vivo con el PC ✓ · las órdenes llegan al instante"
                : "Sin conexión en vivo con el PC · se reintenta solo (toca «Conectar con el PC»)");
        estadoPc.setTextColor(vivo ? Ui.OK : Ui.AVISO);
    }

    /** Pasos para que HyperOS/MIUI (u otro ahorro de batería) no cierre GuardianIA en segundo plano. */
    private void guiaEnergia() {
        boolean xiaomi = co.guardiania.app.service.Energia.esXiaomi();
        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("Que el teléfono no cierre GuardianIA")
                .setMessage((xiaomi ? "Tu Xiaomi cierra las apps en segundo plano para ahorrar batería; por eso a veces el PC lo "
                        + "ve desconectado.\n\n1. Inicio automático: actívalo para GuardianIA.\n2. Batería: elige «Sin "
                        + "restricciones».\n3. En apps recientes, deja presionada GuardianIA y toca el candado para bloquearla."
                        : "1. Batería: elige «Sin restricciones» para GuardianIA.\n2. Si tu teléfono tiene «Inicio automático» "
                        + "o «Apps en segundo plano», permítelo para GuardianIA."))
                .setPositiveButton(xiaomi ? "1. Inicio automático" : "Abrir ajustes",
                        (d, w) -> co.guardiania.app.service.Energia.abrirInicioAutomatico(a))
                .setNeutralButton("2. Batería", (d, w) -> co.guardiania.app.service.Energia.abrirBateria(a))
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void pintarBoton() {
        if (btnConectar instanceof android.widget.Button)
            ((android.widget.Button) btnConectar).setText(seguimiento ? "Desconectar (dejar de enviar ubicación)" : "Conectar (enviar ubicación)");
    }
}
