package co.guardiania.app.vpn;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;

/**
 * UN solo interruptor para la privacidad del teléfono. Antes había varias vías (WARP, Orbot, Tor del
 * PC, servidores propios): activar varias a la vez generaba conflictos (Android admite una sola VPN y
 * los proxies se pisaban). Ahora hay cuatro modos excluyentes y cada cambio APAGA lo que sobra antes
 * de encender lo nuevo:
 *
 * <ul>
 *   <li>{@link #APAGADO}</li>
 *   <li>{@link #PROTON}: túnel WireGuard de ProtonVPN desde la ubicación elegida.</li>
 *   <li>{@link #TOR}: Tor integrado (3 saltos), proxy 127.0.0.1:8118.</li>
 *   <li>{@link #PROTON_TOR}: Tor sobre VPN. El operador de internet solo ve ProtonVPN; Proton solo ve
 *       tráfico Tor cifrado; el sitio solo ve un relevo Tor. Nadie tiene a la vez quién es usted y qué
 *       visita. Funciona solo: Tor sale por la interfaz del túnel de Proton.</li>
 * </ul>
 */
public final class ModoPrivacidad {

    public static final String APAGADO = "apagado", PROTON = "proton", TOR = "tor", PROTON_TOR = "proton_tor";
    private static final String PREFS = "guardian_privacidad";

    private ModoPrivacidad() {}

    public static String actual(Context c) {
        String m = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("modo", APAGADO);
        return m == null ? APAGADO : m;
    }

    public static boolean usaProton(String m) { return PROTON.equals(m) || PROTON_TOR.equals(m); }
    public static boolean usaTor(String m) { return TOR.equals(m) || PROTON_TOR.equals(m); }

    public static String nombre(String m) {
        switch (m) {
            case PROTON: return "ProtonVPN";
            case TOR: return "Tor";
            case PROTON_TOR: return "ProtonVPN + Tor";
            default: return "Apagado";
        }
    }

    /**
     * Cambia de modo sin dejar nada a medias. Bloquea (llamar en segundo plano). Primero se apaga lo
     * que el modo nuevo no usa; luego se enciende lo que falta. Si Proton falla, no se deja Tor solo
     * «creyendo» que hay VPN: el modo queda en lo que de verdad quedó activo.
     */
    public static JSONObject cambiar(Context ctx, String nuevo) throws IOException {
        return cambiar(ctx, nuevo, RecomendadorUbicacion.GENERAL, null);
    }

    public static JSONObject cambiar(Context ctx, String nuevo, String proposito, String paisDestino) throws IOException {
        Context c = ctx.getApplicationContext();
        MotorVpn motor = MotorVpn.de(c);
        if (usaProton(nuevo) && motor.almacen().perfil() == null && motor.ubicaciones().isEmpty()) {
            throw new IOException("Primero importa una ubicación de ProtonVPN (un archivo .conf por país).");
        }
        // 1) Apagar lo que sobra.
        if (!usaTor(nuevo)) TorInterno.detener(c);
        if (!usaProton(nuevo) && motor.activo()) motor.desconectar();
        // 2) Encender lo que falta. Proton primero: así Tor arranca YA dentro del túnel (Tor sobre VPN).
        JSONObject r = null;
        if (usaProton(nuevo) && !motor.activo()) {
            try {
                r = motor.conectarAutomatico(proposito, paisDestino);   // GuardianIA elige y verifica
            } catch (IOException e) {
                guardar(c, usaTor(nuevo) ? TOR : APAGADO);
                if (usaTor(nuevo)) TorInterno.iniciar(c);
                throw new IOException("ProtonVPN no conectó (" + e.getMessage() + ").", e);
            }
        }
        if (usaTor(nuevo) && !TorInterno.activo() && !"STARTING".equals(TorInterno.estado())) TorInterno.iniciar(c);
        guardar(c, nuevo);
        return r;
    }

    private static void guardar(Context c, String m) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("modo", m).apply();
    }
}
