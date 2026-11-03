package co.guardiania.app.net;

import org.json.JSONObject;

import java.io.IOException;

import co.guardiania.app.data.Vinculo;

/**
 * Acciones del propio teléfono contra el backend, autenticadas por el token del DISPOSITIVO (no por
 * la sesión de la cuenta): un latido puntual y conectar/desconectar el seguimiento. Todo lo pesado lo
 * hace {@link co.guardiania.app.service.TrackingService} en segundo plano; esta clase es para lo que
 * la persona dispara desde la interfaz. Nunca en el hilo principal.
 */
public class DeviceApi {

    private final ApiClient api;
    private final String token;

    public DeviceApi(Vinculo v) {
        this.api = new ApiClient(v.servidor(), v.ca());
        this.token = v.token();
    }

    /** Pausa o reanuda el envío de ubicación desde este teléfono. Devuelve el estado resultante. */
    public JSONObject seguimiento(boolean activo) throws IOException {
        JSONObject cuerpo = new JSONObject();
        try {
            cuerpo.put("device_token", token);
            cuerpo.put("activo", activo);
        } catch (Exception ignored) {}
        ApiClient.Resp r = api.post("/devices/self/seguimiento", cuerpo);
        if (!r.ok()) throw ApiException.de(r, "No se pudo cambiar el seguimiento");
        return r.json();
    }
}
