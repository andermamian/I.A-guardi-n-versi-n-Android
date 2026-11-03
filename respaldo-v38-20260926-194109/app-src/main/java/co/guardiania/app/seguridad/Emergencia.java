package co.guardiania.app.seguridad;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.net.Sesion;
import co.guardiania.app.service.TrackingService;

/**
 * Botón de pánico desde el teléfono (/v1/emergency/sos). El servidor abre el incidente, avisa a los
 * contactos por prioridad y escala según la configuración de la cuenta.
 *
 * <p>La ubicación se toma en este orden: una posición FRESCA (máx. 4 s), la última del servicio de
 * rastreo si es reciente, y si no hay ninguna el SOS sale igual: un aviso sin mapa es mejor que
 * ningún aviso.
 */
public final class Emergencia {

    private Emergencia() {}

    /**
     * SOS: primero SMS desde la SIM a todos los contactos (sin internet, llega a cualquier celular);
     * después, en cuanto haya internet, se registra en la cuenta. Devuelve el envío con el estado de
     * cada contacto (se sigue actualizando: ver {@link SmsSos#observar}).
     *
     * <p>Si el teléfono no tiene contactos guardados, se intenta por el servidor; si tampoco hay
     * internet, se dice claramente que el aviso NO salió.
     */
    public static void sos(Context ctx, String nota, Hilos.Listo<JSONObject> ok, Hilos.Fallo fallo) {
        Context app = ctx.getApplicationContext();
        Hilos.tarea(() -> {
            Location loc = ubicacionAhora(app);
            Double lat = loc != null ? loc.getLatitude() : null, lon = loc != null ? loc.getLongitude() : null;
            Integer prec = loc != null && loc.hasAccuracy() ? Math.round(loc.getAccuracy()) : null;
            if (AgendaSos.cuantos(app) == 0) {
                // Sin agenda en el teléfono: solo queda el servidor.
                JSONObject cuerpo = new JSONObject().put("kind", "sos").put("note", nota);
                if (lat != null) { cuerpo.put("lat", lat); cuerpo.put("lon", lon); }
                try {
                    JSONObject inc = Sesion.de(app).obj("POST", "/emergency/sos", cuerpo);
                    return new JSONObject().put("servidor", "ok").put("incidente_id", inc.optString("id"))
                            .put("contactos", new JSONArray());
                } catch (Exception e) {
                    throw new Exception("No hay contactos de emergencia guardados en el teléfono y no hay internet: el "
                            + "aviso NO se envió. Llama al 123. Luego registra tus contactos en «Contactos de emergencia».");
                }
            }
            JSONObject envio = SmsSos.enviar(app, lat, lon, prec, nota);
            // Registro en la cuenta en segundo plano (espera a saber qué SMS salieron).
            Hilos.fondo(() -> {
                for (int i = 0; i < 16 && "pendiente".equals(SmsSos.envio(app).optString("servidor")); i++) {
                    SmsSos.registrarEnServidor(app);
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            });
            return envio;
        }, ok, fallo);
    }

    /**
     * SOS pedido desde el PC (comando «sos»): el teléfono avisa con SU SIM y su ubicación actual.
     * Bloquea hasta ~15 s para devolver al PC un resultado honesto (cuántos SMS salieron de verdad).
     * Llamar solo en segundo plano.
     */
    public static JSONObject sosDesdePc(Context ctx) {
        Context app = ctx.getApplicationContext();
        JSONObject r = new JSONObject();
        try {
            if (AgendaSos.cuantos(app) == 0) {
                return r.put("_fallo", true).put("motivo", "El teléfono no tiene contactos de emergencia guardados. "
                        + "Ábrelo y entra a «Contactos de emergencia».");
            }
            if (!SmsSos.tienePermiso(app)) {
                return r.put("_fallo", true).put("motivo", "El teléfono no tiene permiso para enviar SMS. En el teléfono: "
                        + "Contactos de emergencia → «Permitir que el SOS envíe SMS».");
            }
            Location loc = ubicacionAhora(app);
            Double lat = loc != null ? loc.getLatitude() : null, lon = loc != null ? loc.getLongitude() : null;
            Integer prec = loc != null && loc.hasAccuracy() ? Math.round(loc.getAccuracy()) : null;
            SmsSos.enviar(app, lat, lon, prec, "SOS activado desde el PC", true);
            AvisoSos.mostrar(app);                                    // aviso en el teléfono con «Estoy bien»
            // Espera la confirmación de Android (máx. 15 s) y registra el incidente en la cuenta.
            JSONObject e = SmsSos.envio(app);
            for (int i = 0; i < 15; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                e = SmsSos.envio(app);
                if (SmsSos.pendientes(e).isEmpty() && !contiene(e, "enviando")) break;
            }
            SmsSos.registrarEnServidor(app);
            int enviados = 0, faltan = 0;
            JSONArray cs = e.optJSONArray("contactos");
            JSONArray detalle = new JSONArray();
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject x = cs.optJSONObject(i);
                if (x == null) continue;
                boolean ok = "enviado".equals(x.optString("estado"));
                if (ok) enviados++; else faltan++;
                detalle.put(new JSONObject().put("nombre", x.optString("nombre")).put("estado", x.optString("estado"))
                        .put("error", x.optString("error", "")));
            }
            r.put("sms_enviados", enviados).put("sms_pendientes", faltan).put("contactos", detalle)
                    .put("ubicacion", lat != null);
            if (enviados == 0) r.put("_fallo", true).put("motivo", "Ningún SMS salió todavía (se sigue reintentando en el teléfono).");
            return r;
        } catch (Exception ex) {
            try { return r.put("_fallo", true).put("motivo", ex.getMessage()); } catch (Exception ignored) { return r; }
        }
    }

    private static boolean contiene(JSONObject e, String estado) {
        JSONArray cs = e.optJSONArray("contactos");
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject x = cs.optJSONObject(i);
            if (x != null && estado.equals(x.optString("estado"))) return true;
        }
        return false;
    }

    /** «Estoy bien»: SMS a quien recibió el SOS por la SIM y cierre del incidente en la cuenta (si hay internet). */
    public static void estoyBien(Context ctx, String incidenteId, Hilos.Listo<JSONObject> ok, Hilos.Fallo fallo) {
        Context app = ctx.getApplicationContext();
        Hilos.tarea(() -> {
            String id = incidenteId != null && !incidenteId.isEmpty() ? incidenteId : SmsSos.envio(app).optString("incidente_id");
            int sms = SmsSos.estoyBien(app);
            JSONObject r = new JSONObject().put("sms", sms);
            if (!id.isEmpty()) {
                try { r.put("incidente", Sesion.de(app).obj("POST", "/emergency/incidents/" + id + "/cancel", new JSONObject())); }
                catch (Exception e) { r.put("servidor", "sin conexión"); }
            }
            return r;
        }, ok, fallo);
    }

    /** Posición actual con límite de tiempo. Llamar SOLO en un hilo de fondo. */
    public static Location ubicacionAhora(Context ctx) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return TrackingService.ultimaConocida();
        }
        try {
            Location fresca = Tasks.await(LocationServices.getFusedLocationProviderClient(ctx)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null), 4, TimeUnit.SECONDS);
            if (fresca != null) return fresca;
        } catch (Exception ignored) {
            // Sin señal GPS en 4 s: se usa la última conocida.
        }
        Location ultima = TrackingService.ultimaConocida();
        if (ultima != null && System.currentTimeMillis() - ultima.getTime() < 10 * 60 * 1000) return ultima;
        try {
            return Tasks.await(LocationServices.getFusedLocationProviderClient(ctx).getLastLocation(), 2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
