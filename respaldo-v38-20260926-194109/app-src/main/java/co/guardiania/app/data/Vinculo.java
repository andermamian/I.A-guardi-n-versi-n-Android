package co.guardiania.app.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Estado persistente de la app: el vínculo del teléfono (servidor, token del dispositivo, CA para
 * el HTTPS), la sesión de la cuenta (refresh token) y las preferencias de uso.
 *
 * <p>Las dos credenciales (token del dispositivo y refresh token) se guardan cifradas con
 * {@link CajaFuerte} (Android Keystore). El resto no es secreto. Las instalaciones de la v1.x, que
 * las tenían en claro, se migran solas la primera vez que se leen.
 */
public class Vinculo {

    private static final String ARCHIVO = "guardiania_vinculo";
    private static final String K_SERVIDOR = "servidor";
    private static final String K_TOKEN = "token";
    private static final String K_CA = "ca_pem";
    private static final String K_NOMBRE = "nombre";
    private static final String K_INTERVALO = "intervalo_s";
    private static final String K_SEGUIMIENTO = "seguimiento";
    private static final String K_ESTADO = "estado_equipo";
    // Sesión de usuario (email/contraseña): la usan asistente, salud, emergencias y seguridad.
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EMAIL = "email";
    private static final String K_NOMBRE_USUARIO = "nombre_usuario";
    // Preferencias
    private static final String K_BIOMETRIA = "pref_biometria";
    private static final String K_VOZ = "pref_leer_en_voz";
    private static final String K_WEB = "pref_buscar_web";
    private static final String K_WEARABLE = "wearable_";

    private final SharedPreferences p;

    public Vinculo(Context ctx) {
        this.p = ctx.getApplicationContext().getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE);
    }

    // ─────────────────────────── vínculo del dispositivo
    public boolean estaVinculado() { return p.contains(K_TOKEN) && p.contains(K_SERVIDOR) && !token().isEmpty(); }

    public void guardar(String servidor, String token, String caPem, String nombre) {
        p.edit()
                .putString(K_SERVIDOR, servidor)
                .putString(K_TOKEN, CajaFuerte.cifrar(token))
                .putString(K_CA, caPem)
                .putString(K_NOMBRE, nombre)
                .apply();
    }

    /**
     * Guarda la dirección del PC y su CA ya comprobada, sin token de dispositivo. Sirve para iniciar
     * sesión antes (o sin) vincular el teléfono.
     */
    public void guardarServidor(String servidor, String caPem) {
        p.edit().putString(K_SERVIDOR, servidor).putString(K_CA, caPem).apply();
    }

    /** ¿Hay un PC configurado con su CA? (requisito para cualquier llamada a la API). */
    public boolean tieneServidor() { return !servidor().isEmpty() && !ca().isEmpty(); }

    public void guardarRitmo(int intervaloS, boolean seguimiento) {
        p.edit().putInt(K_INTERVALO, intervaloS).putBoolean(K_SEGUIMIENTO, seguimiento).apply();
    }

    public void guardarEstado(String estado) { p.edit().putString(K_ESTADO, estado).apply(); }

    /** Borra TODO (vínculo, sesión y preferencias): desvincular o borrado remoto sin administrador. */
    public void borrar() { p.edit().clear().apply(); }

    public String servidor() { return p.getString(K_SERVIDOR, ""); }
    public String token() { return leerSecreto(K_TOKEN); }
    public String ca() { return p.getString(K_CA, ""); }
    public String nombre() { return p.getString(K_NOMBRE, "Este teléfono"); }
    public int intervaloS() { return p.getInt(K_INTERVALO, 30); }
    public boolean seguimiento() { return p.getBoolean(K_SEGUIMIENTO, true); }
    /** ok | lost | stolen, según la última respuesta del servidor. */
    public String estadoEquipo() { return p.getString(K_ESTADO, "ok"); }
    public boolean reportado() { String e = estadoEquipo(); return "lost".equals(e) || "stolen".equals(e); }

    // ─────────────────────────── sesión de la cuenta
    public void guardarSesion(String refreshToken, String email) {
        p.edit().putString(K_REFRESH, CajaFuerte.cifrar(refreshToken)).putString(K_EMAIL, email).apply();
    }

    public void guardarNombreUsuario(String nombre) { p.edit().putString(K_NOMBRE_USUARIO, nombre).apply(); }

    public void borrarSesion() { p.edit().remove(K_REFRESH).remove(K_EMAIL).remove(K_NOMBRE_USUARIO).apply(); }

    public boolean tieneSesion() { return p.contains(K_REFRESH) && !refreshToken().isEmpty(); }
    public String refreshToken() { return leerSecreto(K_REFRESH); }
    public String email() { return p.getString(K_EMAIL, ""); }
    public String nombreUsuario() { return p.getString(K_NOMBRE_USUARIO, ""); }

    // ─────────────────────────── preferencias
    public boolean biometria() { return p.getBoolean(K_BIOMETRIA, false); }
    public void setBiometria(boolean v) { p.edit().putBoolean(K_BIOMETRIA, v).apply(); }
    public boolean leerEnVoz() { return p.getBoolean(K_VOZ, false); }
    public void setLeerEnVoz(boolean v) { p.edit().putBoolean(K_VOZ, v).apply(); }
    public boolean buscarEnWeb() { return p.getBoolean(K_WEB, true); }
    public void setBuscarEnWeb(boolean v) { p.edit().putBoolean(K_WEB, v).apply(); }

    /** id del reloj registrado en el servidor, por dirección Bluetooth. */
    public String wearableId(String direccion) { return p.getString(K_WEARABLE + direccion, null); }
    public void guardarWearable(String direccion, String id) { p.edit().putString(K_WEARABLE + direccion, id).apply(); }

    private String leerSecreto(String k) {
        String v = p.getString(k, "");
        if (v.isEmpty()) return "";
        if (!CajaFuerte.estaCifrado(v)) {
            // Migración desde la v1.x: estaba en claro; se reescribe cifrado.
            String cifrado = CajaFuerte.cifrar(v);
            if (CajaFuerte.estaCifrado(cifrado)) p.edit().putString(k, cifrado).apply();
            return v;
        }
        return CajaFuerte.descifrar(v);
    }
}
