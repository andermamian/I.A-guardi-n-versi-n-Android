package co.guardiania.app.ui.pantallas;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.data.Vinculo;
import co.guardiania.app.net.Sesion;
import co.guardiania.app.seguridad.Emergencia;
import co.guardiania.app.service.TrackingService;
import co.guardiania.app.ui.kit.BotonSos;
import co.guardiania.app.ui.MainActivity;
import co.guardiania.app.ui.kit.Ui;

/**
 * Pantalla de inicio: el estado de la protección de un vistazo (rastreo activo, permisos, batería) y
 * el botón SOS grande. Es lo primero que ve la persona: en una emergencia no debe buscar nada.
 */
public class InicioFragment {

    private final MainActivity a;
    private final Vinculo vinculo;
    private TextView estadoProteccion, estadoUbicacion;

    public InicioFragment(MainActivity a) {
        this.a = a;
        this.vinculo = new Vinculo(a);
    }

    public View crear() {
        LinearLayout raiz = Ui.pagina(a);

        raiz.addView(Ui.titulo(a, "Hola" + (vinculo.nombreUsuario().isEmpty() ? "" : ", " + primerNombre())));
        raiz.addView(Ui.parrafo(a, "Tu teléfono, tu privacidad y tu seguridad en un solo lugar."));

        // Estado de la protección antirrobo.
        LinearLayout estado = Ui.tarjeta(a);
        LinearLayout cab = Ui.fila(a);
        cab.addView(Ui.icono(a, co.guardiania.app.R.drawable.ic_escudo, Ui.ACENTO, 24));
        TextView tit = Ui.subtitulo(a, "  Protección antirrobo");
        cab.addView(tit);
        estado.addView(cab);
        estadoProteccion = Ui.parrafo(a, "");
        estadoUbicacion = Ui.pequeno(a, "");
        estado.addView(estadoProteccion);
        estado.addView(estadoUbicacion);
        if (!vinculo.estaVinculado()) {
            Ui.en(estado, Ui.boton(a, "Vincular este teléfono", Ui.Estilo.PRIMARIO)).setOnClickListener(v -> a.pedirVinculo());
        } else {
            Ui.en(estado, Ui.boton(a, "Ver mi teléfono en el mapa", Ui.Estilo.SECUNDARIO)).setOnClickListener(v -> a.irAPantalla("dispositivo"));
        }
        raiz.addView(estado);

        // SOS.
        raiz.addView(Ui.seccion(a, "Emergencia"));
        LinearLayout sosCaja = Ui.tarjeta(a);
        sosCaja.setGravity(android.view.Gravity.CENTER);
        BotonSos sos = new BotonSos(a);
        sos.alCompletar(() -> lanzarSos());
        sos.alConfirmarAccesible(() -> Ui.confirmar(a, "Avisar a mis contactos",
                "Se enviará una alerta con tu ubicación a tus contactos de emergencia.", "Avisar ahora", this::lanzarSos));
        Ui.margen(sos, 0, 8, 0, 8);
        sosCaja.addView(sos);
        sosCaja.addView(Ui.pequeno(a, "Envía un SMS con tu ubicación a tus contactos desde tu SIM: les llega aunque no tengan la app y aunque no tengas internet."));
        View panel = Ui.botonChico(a, "Agregar SOS al panel rápido", Ui.Estilo.SECUNDARIO);
        panel.setOnClickListener(v -> agregarAlPanel());
        sosCaja.addView(panel);
        raiz.addView(sosCaja);

        // Accesos rápidos.
        raiz.addView(Ui.seccion(a, "Accesos rápidos"));
        raiz.addView(Ui.itemLista(a, co.guardiania.app.R.drawable.ic_chat, "Asistente IA", "Pregunta, pide código o revisa un enlace", v -> a.irAPantalla("asistente")));
        raiz.addView(Ui.itemLista(a, co.guardiania.app.R.drawable.ic_escudo, "VPN anónima", "Oculta tu IP y tu navegación", v -> a.startActivity(new android.content.Intent(a, VpnActivity.class))));
        raiz.addView(Ui.itemLista(a, co.guardiania.app.R.drawable.ic_escudo, "Seguridad", "Analiza apps, archivos y enlaces", v -> a.irAPantalla("seguridad")));
        raiz.addView(Ui.itemLista(a, co.guardiania.app.R.drawable.ic_contactos, "Contactos de emergencia", "A quién avisar en un SOS", v -> abrirContactos()));

        return Ui.desplazable(a, raiz);
    }

    private String primerNombre() {
        String n = vinculo.nombreUsuario().trim();
        return n.contains(" ") ? n.substring(0, n.indexOf(' ')) : n;
    }

    public void actualizar() {
        if (estadoProteccion == null) return;
        if (!vinculo.estaVinculado()) {
            estadoProteccion.setText("Este teléfono aún no está protegido.");
            estadoProteccion.setTextColor(Ui.AVISO);
            estadoUbicacion.setText("Vincúlalo con el código que muestra el PC en Dispositivos.");
            return;
        }
        boolean fondo = ContextCompat.checkSelfPermission(a, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                || android.os.Build.VERSION.SDK_INT < 29;
        if (vinculo.reportado()) {
            estadoProteccion.setText("Equipo reportado. Reportando su ubicación a tu cuenta.");
            estadoProteccion.setTextColor(Ui.PELIGRO);
        } else if (fondo) {
            estadoProteccion.setText("Protección 24/7 activa. Reporta aunque la app esté cerrada.");
            estadoProteccion.setTextColor(Ui.OK);
        } else {
            estadoProteccion.setText("Rastreo limitado: falta «Ubicación todo el tiempo».");
            estadoProteccion.setTextColor(Ui.AVISO);
        }
        Location ult = TrackingService.ultimaConocida();
        estadoUbicacion.setText(ult != null
                ? "Última posición: ±" + Math.round(ult.getAccuracy()) + " m · " + Ui.hace(new java.util.Date(ult.getTime()).toInstant().toString())
                : "Esperando la primera posición del GPS…");
    }

    private void lanzarSos() {
        a.lanzarSos("Botón de pánico (app)");
    }

    /**
     * Botón «SOS» en el panel rápido de Android. En Android 13+ el sistema ofrece agregarlo con un toque;
     * antes, se explica cómo hacerlo a mano.
     */
    private void agregarAlPanel() {
        android.content.ComponentName comp = new android.content.ComponentName(a, co.guardiania.app.seguridad.SosTileService.class);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.app.StatusBarManager sbm = a.getSystemService(android.app.StatusBarManager.class);
            if (sbm != null) {
                sbm.requestAddTileService(comp, "SOS GuardianIA",
                        android.graphics.drawable.Icon.createWithResource(a, co.guardiania.app.R.drawable.ic_sos),
                        a.getMainExecutor(), r -> {
                            if (r == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)
                                Ui.aviso(a, "Listo: desliza hacia abajo y verás el botón SOS.");
                            else if (r == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                                Ui.aviso(a, "El botón SOS ya está en tu panel rápido.");
                            else instruccionesPanel();
                        });
                return;
            }
        }
        instruccionesPanel();
    }

    private void instruccionesPanel() {
        Ui.mensaje(a, "Agregar SOS al panel rápido", "1. Desliza dos veces hacia abajo desde la parte de arriba de la pantalla.\n"
                + "2. Toca el lápiz (Editar).\n3. Busca «SOS GuardianIA» y arrástralo arriba.\n\nAl tocarlo, tienes 5 segundos "
                + "para cancelar antes de que se envíe el SOS. Funciona sin desbloquear el teléfono.");
    }

    private void abrirContactos() {
        if (!Sesion.de(a).iniciada()) { a.pedirSesion(); return; }
        a.abrirContactos();
    }
}
