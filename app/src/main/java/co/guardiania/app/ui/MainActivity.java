package co.guardiania.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import co.guardiania.app.R;
import co.guardiania.app.ai.Asistente;
import co.guardiania.app.core.Hilos;
import co.guardiania.app.data.Vinculo;
import co.guardiania.app.net.Sesion;
import co.guardiania.app.service.AdminReceiver;
import co.guardiania.app.service.TrackingService;
import co.guardiania.app.ui.kit.Ui;
import co.guardiania.app.ui.pantallas.AsistenteFragment;
import co.guardiania.app.ui.pantallas.ContactosActivity;
import co.guardiania.app.ui.pantallas.CuentaActivity;
import co.guardiania.app.ui.pantallas.DispositivoFragment;
import co.guardiania.app.ui.pantallas.InicioFragment;
import co.guardiania.app.ui.pantallas.SeguridadFragment;
import co.guardiania.app.ui.pantallas.VincularActivity;

/**
 * Pantalla principal de la app v2: un contenedor con cuatro pestañas (Inicio, Asistente, Seguridad y
 * Mi teléfono) sobre una barra de navegación inferior. Concentra lo que comparten todas:
 * permisos, elección de archivos, descargas y navegación a las pantallas de cuenta.
 *
 * <p>Las pestañas se construyen la primera vez que se abren y se conservan, para no rehacer el mapa o
 * el chat al cambiar de una a otra. El rastreo antirrobo vive en su servicio y no depende de que esta
 * pantalla esté abierta.
 */
public class MainActivity extends AppCompatActivity {

    private Vinculo vinculo;
    private FrameLayout contenedor;
    private LinearLayout navegacion;
    private final View[] pantallas = new View[4];
    private InicioFragment inicio;
    private AsistenteFragment asistente;
    private SeguridadFragment seguridad;
    private DispositivoFragment dispositivo;
    private int actual = -1;

    // Callbacks pendientes de un resultado (permiso, archivo).
    private PermisoCb permisoPendiente;
    private ArchivoCb adjuntoPendiente, analizarPendiente;
    private ConId trasSesion;

    public interface PermisoCb { void resultado(boolean concedido); }
    public interface ArchivoCb { void archivo(String nombre, byte[] datos); }
    public interface ConId { void id(String conversationId); }

    private final ActivityResultLauncher<String[]> permisos =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), res -> {
                boolean todos = !res.isEmpty();
                for (Boolean b : res.values()) todos &= Boolean.TRUE.equals(b);
                if (permisoPendiente != null) { PermisoCb cb = permisoPendiente; permisoPendiente = null; cb.resultado(todos); }
                refrescar();
            });

    private final ActivityResultLauncher<Intent> vincularLanzador =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> { pedirPermisosProteccion(); reconstruir(); });

    private final ActivityResultLauncher<Intent> sesionLanzador =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                reconstruir();
                if (trasSesion != null && Sesion.de(this).iniciada()) { ConId c = trasSesion; trasSesion = null; c.id(null); }
            });

    private final ActivityResultLauncher<String> adjuntoLanzador =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> recibirArchivo(uri, adjuntoPendiente));
    private final ActivityResultLauncher<String> analizarLanzador =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> recibirArchivo(uri, analizarPendiente));

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vinculo = new Vinculo(this);
        construir();
        precargarDesdeIntent(getIntent());
        seleccionar(0);
        if (vinculo.estaVinculado()) {
            TrackingService.iniciar(this, null);
            pedirPermisosProteccion();
        }
        Hilos.fondo(() -> Asistente.actualizarRed(getApplicationContext()));
        abrirDesdeNotificacion(getIntent());
        // Copia local de los contactos de emergencia (el SOS por SMS debe funcionar sin internet).
        Hilos.fondo(() -> {
            try {
                if (Sesion.de(this).iniciada()) {
                    co.guardiania.app.seguridad.AgendaSos.guardar(this,
                            Sesion.de(this).obj("GET", "/emergency/contacts", null).optJSONArray("contactos"));
                }
                co.guardiania.app.seguridad.SmsSos.reintentar(this);
            } catch (Exception ignored) {}
        });
    }

    private void construir() {
        LinearLayout raiz = Ui.columna(this);
        raiz.setBackgroundColor(Ui.FONDO);

        LinearLayout topo = Ui.fila(this);
        topo.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 8));
        ImageView escudo = Ui.icono(this, R.drawable.ic_escudo, Ui.ACENTO, 22);
        topo.addView(escudo);
        topo.addView(Ui.subtitulo(this, "  GuardianIA"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView ajustes = Ui.botonIcono(this, R.drawable.ic_ajustes, "Ajustes", android.graphics.Color.TRANSPARENT, Ui.TEXTO2);
        ajustes.setOnClickListener(v -> abrirAjustes());
        topo.addView(ajustes);
        raiz.addView(topo);

        contenedor = new FrameLayout(this);
        raiz.addView(contenedor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        navegacion = Ui.fila(this);
        navegacion.setBackgroundColor(Ui.TARJETA);
        navegacion.setWeightSum(4);
        pestana(0, R.drawable.ic_inicio, "Inicio");
        pestana(1, R.drawable.ic_chat, "Asistente");
        pestana(2, R.drawable.ic_escudo, "Seguridad");
        pestana(3, R.drawable.ic_mapa, "Mi teléfono");
        raiz.addView(navegacion);
        setContentView(raiz);
    }

    private void pestana(int indice, int icono, String etiqueta) {
        LinearLayout p = Ui.columna(this);
        p.setGravity(Gravity.CENTER);
        p.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        p.setBackground(Ui.conOnda(Ui.fondo(android.graphics.Color.TRANSPARENT, 0, 0, this), Ui.conAlfa(Ui.ACENTO, 0.2f)));
        ImageView ic = Ui.icono(this, icono, Ui.TEXTO2, 22);
        TextView t = Ui.texto(this, etiqueta, 11, Ui.TEXTO2);
        p.addView(ic);
        p.addView(t);
        p.setTag(new View[]{ic, t});
        p.setContentDescription(etiqueta);
        p.setOnClickListener(v -> seleccionar(indice));
        navegacion.addView(p, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void seleccionar(int indice) {
        if (indice == actual) return;
        if (actual == 3 && dispositivo != null) dispositivo.alOcultar();
        actual = indice;
        contenedor.removeAllViews();
        View nueva = pantalla(indice);
        if (nueva.getParent() instanceof ViewGroup) ((ViewGroup) nueva.getParent()).removeView(nueva);
        contenedor.addView(nueva);
        Ui.entrar(nueva);                        // transición suave entre pestañas
        for (int i = 0; i < navegacion.getChildCount(); i++) {
            View[] partes = (View[]) navegacion.getChildAt(i).getTag();
            int color = i == indice ? Ui.ACENTO : Ui.TEXTO2;
            ((ImageView) partes[0]).setColorFilter(color);
            ((TextView) partes[1]).setTextColor(color);
        }
        if (indice == 0 && inicio != null) inicio.actualizar();
        if (indice == 3 && dispositivo != null) dispositivo.alMostrar();
    }

    private View pantalla(int indice) {
        if (pantallas[indice] == null) {
            switch (indice) {
                case 0: inicio = new InicioFragment(this); pantallas[0] = inicio.crear(); break;
                case 1: asistente = new AsistenteFragment(this); pantallas[1] = asistente.crear(); break;
                case 2: seguridad = new SeguridadFragment(this); pantallas[2] = seguridad.crear(); break;
                default: dispositivo = new DispositivoFragment(this); pantallas[3] = dispositivo.crear();
            }
        }
        return pantallas[indice];
    }

    /** «Tu respuesta está lista» / «GuardianIA está escribiendo…» abren directo el chat. */
    private void abrirDesdeNotificacion(Intent i) {
        if (i != null && "asistente".equals(i.getStringExtra("abrir"))) seleccionar(1);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        abrirDesdeNotificacion(intent);
    }

    @Override
    protected void onDestroy() {
        if (asistente != null) asistente.destruir();   // deja de observar el chat (no filtra memoria)
        super.onDestroy();
    }

    private void reconstruir() {
        for (int i = 0; i < pantallas.length; i++) pantallas[i] = null;
        if (asistente != null) asistente.destruir();
        inicio = null; asistente = null; seguridad = null; dispositivo = null;
        int a = actual; actual = -1;
        seleccionar(a < 0 ? 0 : a);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refrescar();
    }

    private void refrescar() { if (inicio != null) inicio.actualizar(); }

    // ─────────────────────────── navegación entre pestañas y pantallas
    public void irAPantalla(String clave) {
        switch (clave) {
            case "asistente": seleccionar(1); break;
            case "seguridad":
            case "vpn": seleccionar(2); break;
            case "dispositivo": seleccionar(3); break;
            default: seleccionar(0);
        }
    }

    public void pedirVinculo() { vincularLanzador.launch(new Intent(this, VincularActivity.class)); }

    public void pedirSesion() {
        if (Sesion.de(this).iniciada()) return;
        sesionLanzador.launch(new Intent(this, CuentaActivity.class));
    }

    public void abrirContactos() { startActivity(new Intent(this, ContactosActivity.class)); }

    /**
     * Lanza el SOS (pidiendo antes el permiso de SMS si falta) y muestra el estado real de cada
     * contacto mientras se envía. Nunca dice «enviado» sin la confirmación de Android.
     */
    public void lanzarSos(String nota) {
        if (!co.guardiania.app.seguridad.SmsSos.tienePermiso(this)) {
            // SMS + estado del teléfono (para ver las dos SIM). Si los niega, igual se intenta por internet.
            pedirPermisos(new String[]{Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE}, ok -> enviarSos(nota));
            return;
        }
        enviarSos(nota);
    }

    private void enviarSos(String nota) {
        Ui.aviso(this, "Enviando SOS…");
        co.guardiania.app.seguridad.Emergencia.sos(this, nota, this::mostrarSos,
                e -> Ui.mensaje(this, "El aviso no salió", e.getMessage()));
    }

    public void mostrarSos(JSONObject envio) {
        TextView estado = Ui.parrafo(this, co.guardiania.app.seguridad.SmsSos.resumen(envio));
        LinearLayout caja = Ui.columna(this);
        int p = Ui.dp(this, 22);
        caja.setPadding(p, Ui.dp(this, 8), p, 0);
        caja.addView(estado);
        LinearLayout alternativas = Ui.columna(this);
        caja.addView(alternativas);
        caja.addView(Ui.pequeno(this, "Si nadie responde en unos minutos, llama al 123."));
        pintarAlternativas(alternativas, envio);
        co.guardiania.app.seguridad.SmsSos.observar(e -> {
            estado.setText(co.guardiania.app.seguridad.SmsSos.resumen(e));
            pintarAlternativas(alternativas, e);
        });
        androidx.appcompat.app.AlertDialog d = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("SOS en curso")
                .setView(caja)
                .setPositiveButton("Estoy bien", (x, w) -> co.guardiania.app.seguridad.Emergencia.estoyBien(this,
                        envio.optString("incidente_id"),
                        r -> confirmarBien(r.optInt("sms")),
                        e -> Ui.aviso(this, e.getMessage())))
                .setNeutralButton("Llamar al 123", (x, w) -> llamar("123"))
                .setNegativeButton("Cerrar", null)
                .create();
        d.setOnDismissListener(x -> co.guardiania.app.seguridad.SmsSos.observar(null));
        d.show();
    }

    /**
     * Para quien el SMS de la SIM no llegó: el mismo aviso (con la ubicación) por WhatsApp —funciona con
     * WiFi— o con la app de mensajes del teléfono, que elige la SIM a su manera. Solo falta tocar «enviar».
     */
    private void pintarAlternativas(LinearLayout caja, JSONObject envio) {
        caja.removeAllViews();
        java.util.List<JSONObject> faltan = co.guardiania.app.seguridad.SmsSos.pendientes(envio);
        if (faltan.isEmpty()) return;
        String texto = envio.optString("texto");
        TextView t = Ui.pequeno(this, "Envíalo tú mismo mientras tanto:");
        t.setPadding(0, Ui.dp(this, 10), 0, 0);
        caja.addView(t);
        StringBuilder todos = new StringBuilder();
        for (JSONObject x : faltan) {
            String tel = x.optString("tel");
            if (todos.length() > 0) todos.append(';');
            todos.append(tel);
            View wa = Ui.botonChico(this, "WhatsApp a " + x.optString("nombre"), Ui.Estilo.SECUNDARIO);
            wa.setOnClickListener(v -> abrirWhatsApp(tel, texto));
            caja.addView(wa);
        }
        View sms = Ui.botonChico(this, "Enviar con mi app de mensajes", Ui.Estilo.SECUNDARIO);
        String destinos = todos.toString();
        sms.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(destinos)))
                        .putExtra("sms_body", texto));
            } catch (Exception e) {
                Ui.aviso(this, "No hay app de mensajes disponible.");
            }
        });
        caja.addView(sms);
    }

    /**
     * Tras «Estoy bien»: espera la confirmación de Android y dice la verdad. Si a alguien no le llegó,
     * ofrece mandárselo por WhatsApp o con la app de mensajes (igual que en el SOS).
     */
    private void confirmarBien(int contactos) {
        if (contactos == 0) { Ui.aviso(this, "Alerta cancelada."); return; }
        Ui.aviso(this, "Avisando por SMS que estás bien…");
        Hilos.uiLuego(() -> {
            JSONObject b = co.guardiania.app.seguridad.SmsSos.bien(this);
            java.util.List<JSONObject> faltan = co.guardiania.app.seguridad.SmsSos.pendientes(b);
            if (faltan.isEmpty()) { Ui.aviso(this, "✓ Se avisó a tus contactos que estás bien."); return; }
            LinearLayout caja = Ui.columna(this);
            int p = Ui.dp(this, 22);
            caja.setPadding(p, Ui.dp(this, 8), p, 0);
            caja.addView(Ui.parrafo(this, co.guardiania.app.seguridad.SmsSos.resumen(b).replace("SMS enviado ✓", "avisado ✓")
                    .replaceAll("\n• (Registrado en tu cuenta ✓|Tu cuenta: se registra cuando haya internet)", "")));
            LinearLayout alternativas = Ui.columna(this);
            caja.addView(alternativas);
            pintarAlternativas(alternativas, b);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("«Estoy bien» aún no llega a todos")
                    .setView(caja)
                    .setPositiveButton("Cerrar", null)
                    .show();
        }, 10_000);
    }

    private void abrirWhatsApp(String telE164, String texto) {
        String numero = telE164.replaceAll("[^0-9]", "");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + numero + "?text=" + Uri.encode(texto))));
        } catch (Exception e) {
            Ui.aviso(this, "WhatsApp no está instalado.");
        }
    }

    public void pedirSesionLuego(ConId cb) { trasSesion = cb; pedirSesion(); }

    // ─────────────────────────── archivos
    public void elegirAdjunto(ConId cb) {
        if (!Sesion.de(this).iniciada()) { pedirSesion(); return; }
        adjuntoPendiente = (nombre, datos) -> subirAdjunto(nombre, datos, cb);
        adjuntoLanzador.launch("*/*");
    }

    public void elegirArchivoParaAnalizar(ArchivoCb cb) { analizarPendiente = cb; analizarLanzador.launch("*/*"); }

    private void recibirArchivo(@Nullable Uri uri, @Nullable ArchivoCb cb) {
        if (uri == null || cb == null) return;
        Hilos.tarea(() -> {
            String nombre = nombreDeUri(uri);
            try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n; long total = 0;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > 64L * 1024 * 1024) throw new java.io.IOException("El archivo es demasiado grande (máx. 64 MB).");
                    bos.write(buf, 0, n);
                }
                return new Object[]{nombre, bos.toByteArray()};
            }
        }, r -> cb.archivo((String) r[0], (byte[]) r[1]), e -> Ui.aviso(this, e.getMessage()));
    }

    private void subirAdjunto(String nombre, byte[] datos, ConId cb) {
        Ui.aviso(this, "Leyendo " + nombre + "…");
        Hilos.tarea(() -> {
            java.util.Map<String, String> campos = new java.util.HashMap<>();
            return Sesion.de(this).subir("/assistant/attachments", campos, nombre, tipoMime(nombre), datos);
        }, r -> {
            cb.id(r.optString("conversation_id"));
            if (asistente != null) asistente.adjuntoSubido(r);
        }, e -> Ui.aviso(this, e.getMessage()));
    }

    public void descargarArchivo(String ruta, String nombre) {
        Ui.aviso(this, "Descargando " + nombre + "…");
        Hilos.tarea(() -> {
            co.guardiania.app.net.ApiClient.Bytes b = Sesion.de(this).descargar(ruta);
            String nom = b.nombre != null ? b.nombre : nombre;
            java.io.File dir = new java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, nom);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) { fos.write(b.datos); }
            return f;
        }, f -> compartir(f), e -> Ui.aviso(this, e.getMessage()));
    }

    private void compartir(java.io.File f) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, tipoMime(f.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(i, "Abrir " + f.getName()));
        } catch (Exception e) {
            Ui.aviso(this, "Guardado en Descargas: " + f.getName());
        }
    }

    private String nombreDeUri(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String s = uri.getLastPathSegment();
        return s != null ? s : "archivo";
    }

    static String tipoMime(String nombre) {
        String ext = nombre.contains(".") ? nombre.substring(nombre.lastIndexOf('.') + 1).toLowerCase() : "";
        switch (ext) {
            case "pdf": return "application/pdf";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "txt": case "md": case "csv": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    // ─────────────────────────── permisos, teléfono, enlaces
    public void pedirPermisos(String[] perms, PermisoCb cb) {
        java.util.List<String> faltan = new java.util.ArrayList<>();
        for (String p : perms) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) faltan.add(p);
        if (faltan.isEmpty()) { cb.resultado(true); return; }
        permisoPendiente = cb;
        permisos.launch(faltan.toArray(new String[0]));
    }

    /** Secuencia de permisos del antirrobo: notificaciones → ubicación → ubicación en segundo plano. */
    private void pedirPermisosProteccion() {
        if (!vinculo.estaVinculado()) return;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pedirPermisos(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ok -> pedirUbicacion());
            return;
        }
        pedirUbicacion();
    }

    private void pedirUbicacion() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pedirPermisos(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, ok -> {
                if (ok) pedirUbicacionFondo(); else Ui.aviso(this, "Sin ubicación no puedo localizar el teléfono.");
            });
            return;
        }
        pedirUbicacionFondo();
    }

    private void pedirUbicacionFondo() {
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Ui.confirmar(this, "Ubicación todo el tiempo", "Para rastrear con la app cerrada, Android pide que elijas "
                    + "«Permitir todo el tiempo» en la ubicación. Sin eso, solo se rastrea con la app abierta.",
                    "Elegir", () -> pedirPermisos(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, ok -> {
                        TrackingService.iniciar(this, null);
                        refrescar();
                    }));
            return;
        }
        TrackingService.iniciar(this, null);
        refrescar();
    }

    public void llamar(String numero) {
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + numero)));
        } catch (Exception e) {
            Ui.aviso(this, "No se pudo abrir el marcador.");
        }
    }

    public void abrirUrl(String url) {
        try {
            if (!url.startsWith("http")) url = "https://" + url;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Ui.aviso(this, "No se pudo abrir el enlace.");
        }
    }

    // ─────────────────────────── ajustes
    private void abrirAjustes() {
        LinearLayout raiz = Ui.pagina(this);
        boolean sesion = Sesion.de(this).iniciada();
        raiz.addView(Ui.subtitulo(this, "Ajustes"));

        raiz.addView(Ui.seccion(this, "Cuenta"));
        if (sesion) {
            raiz.addView(Ui.parrafo(this, "Sesión: " + Sesion.de(this).email()));
            raiz.addView(Ui.itemLista(this, R.drawable.ic_contactos, "Contactos de emergencia", "A quién avisar en un SOS", v -> abrirContactos()));
            Ui.en(raiz, Ui.boton(this, "Cerrar sesión", Ui.Estilo.FANTASMA)).setOnClickListener(v ->
                    Hilos.tarea(() -> { Sesion.de(this).cerrar(); return true; }, r -> { reconstruir(); Ui.aviso(this, "Sesión cerrada"); }, e -> {}));
        } else {
            Ui.en(raiz, Ui.boton(this, "Iniciar sesión", Ui.Estilo.PRIMARIO)).setOnClickListener(v -> pedirSesion());
        }

        raiz.addView(Ui.seccion(this, "Preferencias"));
        raiz.addView(interruptor("Buscar en internet en el asistente", vinculo.buscarEnWeb(), vinculo::setBuscarEnWeb));
        if (Bloqueo.disponible(this)) raiz.addView(interruptor("Pedir huella o rostro al abrir la app", vinculo.biometria(), vinculo::setBiometria));

        if (vinculo.estaVinculado()) {
            raiz.addView(Ui.seccion(this, "Protección antirrobo"));
            Ui.en(raiz, Ui.boton(this, "Revisar permisos", Ui.Estilo.SECUNDARIO)).setOnClickListener(v -> pedirPermisosProteccion());
            Ui.en(raiz, Ui.boton(this, "Quitar límite de batería", Ui.Estilo.SECUNDARIO)).setOnClickListener(v -> pedirSinRestriccionBateria());
            Ui.en(raiz, Ui.boton(this, AdminReceiver.estaActivo(this) ? "Bloqueo remoto activo ✓" : "Activar bloqueo y borrado remoto", Ui.Estilo.SECUNDARIO))
                    .setOnClickListener(v -> activarAdministrador());
            Ui.en(raiz, Ui.boton(this, "Desvincular este teléfono", Ui.Estilo.PELIGRO)).setOnClickListener(v ->
                    Ui.confirmar(this, "Desvincular", "El teléfono dejará de estar protegido. Elimínalo también en Dispositivos del PC.",
                            "Desvincular", this::desvincular));
        } else {
            raiz.addView(Ui.seccion(this, "Protección antirrobo"));
            Ui.en(raiz, Ui.boton(this, "Vincular este teléfono", Ui.Estilo.PRIMARIO)).setOnClickListener(v -> pedirVinculo());
        }

        raiz.addView(Ui.seccion(this, "Acerca de"));
        raiz.addView(Ui.pequeno(this, "GuardianIA para Android · versión 2.0. Ante una emergencia, llama al 123. "
                + "Esta app avisa y escala, pero no es un dispositivo médico certificado."));

        androidx.appcompat.app.AlertDialog d = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(Ui.desplazable(this, raiz)).setPositiveButton("Cerrar", null).create();
        d.show();
    }

    private View interruptor(String etiqueta, boolean valor, java.util.function.Consumer<Boolean> onCambio) {
        LinearLayout fila = Ui.fila(this);
        fila.addView(Ui.parrafo(this, etiqueta), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        androidx.appcompat.widget.SwitchCompat sw = new androidx.appcompat.widget.SwitchCompat(this);
        sw.setChecked(valor);
        sw.setOnCheckedChangeListener((b, c) -> onCambio.accept(c));
        fila.addView(sw);
        return fila;
    }

    private void desvincular() {
        String idDispositivo = vinculo.deviceId();
        boolean conSesion = Sesion.de(this).iniciada();
        Hilos.tarea(() -> {
            // Borra el registro en el PC también; si falla (sin sesión, sin red…) igual
            // se desvincula localmente, que es lo mínimo que el usuario pidió.
            if (conSesion && !idDispositivo.isEmpty()) {
                try { Sesion.de(this).obj("DELETE", "/devices/" + idDispositivo, null); }
                catch (Exception ignorada) { android.util.Log.w("GuardianIA", "No se pudo borrar el dispositivo en el PC: " + ignorada.getMessage()); }
            }
            return true;
        }, ok -> desvincularEnServidorListo(), e -> desvincularEnServidorListo());
    }

    private void desvincularEnServidorListo() {
        TrackingService.detener(this);
        vinculo.borrar();
        Sesion.olvidar();
        reconstruir();
        Ui.aviso(this, "Teléfono desvinculado.");
    }

    private void activarAdministrador() {
        if (AdminReceiver.estaActivo(this)) { Ui.aviso(this, "El bloqueo remoto ya está activo."); return; }
        startActivity(AdminReceiver.intentActivar(this));
    }

    private void pedirSinRestriccionBateria() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        } else {
            Ui.aviso(this, "La batería ya está sin restricciones para GuardianIA.");
        }
    }

    private void precargarDesdeIntent(Intent intent) {
        if (intent != null && intent.getData() != null && intent.getData().getQueryParameter("codigo") != null) {
            Intent i = new Intent(this, VincularActivity.class);
            i.setData(intent.getData());
            vincularLanzador.launch(i);
        }
    }
}
