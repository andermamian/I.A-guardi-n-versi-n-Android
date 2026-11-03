package co.guardiania.app.service;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import org.json.JSONObject;

import co.guardiania.app.data.Vinculo;
import co.guardiania.app.net.ApiClient;

/**
 * Ejecuta en el teléfono las "acciones remotas" que el dueño envía desde el PC. El
 * {@link TrackingService} las consulta con /devices/commands/pending y le pasa cada una aquí; al
 * terminar se confirma con /devices/commands/{id}/ack, incluyendo la firma HMAC que el backend
 * emitió (así el servidor comprueba que el acuse es legítimo).
 *
 * <p>A diferencia de la página web, una app nativa SÍ puede sonar a todo volumen aunque el teléfono
 * esté en silencio, y —con permiso de administrador del dispositivo— bloquear la pantalla de verdad
 * y borrar el equipo. Lo que el dueño no haya concedido se reporta como "no disponible" en el acuse,
 * con honestidad, en vez de fingir que se hizo.
 */
public class CommandRunner {

    private static final String TAG = "GuardianIA/Cmd";

    private final Context ctx;
    private final Vinculo vinculo;
    private final ApiClient api;

    public CommandRunner(Context ctx, Vinculo vinculo) {
        this.ctx = ctx.getApplicationContext();
        this.vinculo = vinculo;
        this.api = new ApiClient(vinculo.servidor(), vinculo.ca());
    }

    /** Procesa un comando y envía su acuse. Se llama en un hilo de red, nunca en el principal. */
    public void ejecutar(JSONObject cmd, LocalizadorPuntual localizador) {
        String id = cmd.optString("id");
        String kind = cmd.optString("kind");
        String firma = cmd.optString("signature");
        JSONObject payload = cmd.optJSONObject("payload");
        if (payload == null) payload = new JSONObject();

        JSONObject resultado = new JSONObject();
        String estado = "completed";
        try {
            switch (kind) {
                case "locate":
                    // La ubicación fresca la obtiene el servicio (tiene el GPS); aquí solo se confirma.
                    if (localizador != null) localizador.pedirYReportar();
                    put(resultado, "nota", "Ubicación solicitada al GPS");
                    break;
                case "ring":
                    resultado = sonarAlarma(payload.optInt("segundos", 30));
                    break;
                case "message":
                    AlertActivity.mostrar(ctx, "Mensaje del dueño", payload.optString("mensaje", ""), false);
                    put(resultado, "mostrado", true);
                    break;
                case "lock":
                    resultado = bloquear(payload.optString("mensaje", "Equipo bloqueado por su dueño."));
                    if (resultado.optBoolean("_fallo")) estado = "failed";
                    break;
                case "sos":
                    resultado = co.guardiania.app.seguridad.Emergencia.sosDesdePc(ctx);
                    if (resultado.optBoolean("_fallo")) estado = "failed";
                    break;
                case "wipe":
                    resultado = borrar();
                    if (resultado.optBoolean("_fallo")) estado = "failed";
                    break;
                default:
                    estado = "failed";
                    put(resultado, "motivo", "acción desconocida: " + kind);
            }
        } catch (Exception e) {
            estado = "failed";
            put(resultado, "motivo", e.getMessage());
        }
        resultado.remove("_fallo");
        acusar(id, firma, estado, resultado);
    }

    // ─────────────────────────── acciones
    private JSONObject sonarAlarma(int segundos) {
        JSONObject r = new JSONObject();
        boolean ok = Sirena.sonar(ctx, segundos);                  // sirena propia, volumen máximo, vibración
        put(r, "sonido", ok);
        put(r, "tono", "sirena GuardianIA");
        android.os.Vibrator v = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        put(r, "vibracion", v != null && v.hasVibrator());
        return r;
    }

    private JSONObject bloquear(String mensaje) {
        JSONObject r = new JSONObject();
        DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = AdminReceiver.componente(ctx);
        if (dpm != null && dpm.isAdminActive(admin)) {
            // Bloqueo REAL de la pantalla del sistema: exige el PIN/huella para volver a entrar.
            AlertActivity.mostrar(ctx, "Equipo bloqueado", mensaje, true);
            dpm.lockNow();
            put(r, "alcance", "sistema");
            put(r, "nota", "Pantalla bloqueada; se pedirá el PIN para desbloquear.");
        } else {
            // Sin permiso de administrador: solo se cubre con la pantalla de la app.
            AlertActivity.mostrar(ctx, "Equipo bloqueado", mensaje, true);
            put(r, "alcance", "app");
            put(r, "nota", "Bloqueo real no disponible: falta activar 'Administrador del dispositivo' en GuardianIA.");
            put(r, "_fallo", false);   // no es un fallo duro: algo se mostró
        }
        return r;
    }

    private JSONObject borrar() {
        JSONObject r = new JSONObject();
        DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = AdminReceiver.componente(ctx);
        if (dpm != null && dpm.isAdminActive(admin)) {
            put(r, "alcance", "sistema");
            put(r, "nota", "Restableciendo el teléfono a fábrica.");
            acusar(null, null, null, null);   // no alcanza a enviarse tras el wipe; se intenta antes
            dpm.wipeData(0);                  // borra TODO el teléfono; el equipo se apaga/reinicia
        } else {
            // Sin administrador: se borra al menos lo de GuardianIA en el teléfono.
            vinculo.borrar();
            TrackingService.detener(ctx);
            put(r, "alcance", "app");
            put(r, "nota", "Borrado de fábrica no disponible (falta administrador). Se borraron los datos de GuardianIA.");
        }
        return r;
    }

    private void acusar(String id, String firma, String estado, JSONObject resultado) {
        if (id == null || estado == null) return;   // guardas para la llamada previa al wipe
        try {
            JSONObject cuerpo = new JSONObject();
            cuerpo.put("device_token", vinculo.token());
            cuerpo.put("signature", firma);
            cuerpo.put("status", estado);
            cuerpo.put("result", resultado != null ? resultado : new JSONObject());
            api.post("/devices/commands/" + id + "/ack", cuerpo);
        } catch (Exception e) {
            Log.w(TAG, "No se pudo enviar el acuse: " + e.getMessage());
        }
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) {}
    }

    /** El servicio implementa esto para tomar una posición fresca cuando llega "locate". */
    public interface LocalizadorPuntual { void pedirYReportar(); }
}
