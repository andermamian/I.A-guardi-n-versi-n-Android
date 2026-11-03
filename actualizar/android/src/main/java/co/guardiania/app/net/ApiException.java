package co.guardiania.app.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Error que devolvió el servidor (formato «problem+json» de GuardianIA: title, code, …).
 *
 * <p>Se distingue a propósito de un IOException normal: un ApiException significa «el servidor
 * contestó que no», y la app lo muestra tal cual; un IOException significa «no hubo servidor», y la
 * app puede pasar al modo sin conexión (por ejemplo, el motor local del asistente).
 */
public class ApiException extends IOException {

    public final int status;
    public final String codigo;
    public final JSONObject cuerpo;

    public ApiException(int status, String codigo, String mensaje, JSONObject cuerpo) {
        super(mensaje);
        this.status = status;
        this.codigo = codigo;
        this.cuerpo = cuerpo != null ? cuerpo : new JSONObject();
    }

    public static ApiException de(ApiClient.Resp r, String porDefecto) {
        JSONObject j = r.json();
        String titulo = j.optString("title", "");
        if (titulo.isEmpty()) {
            Object detalle = j.opt("detail");
            if (detalle instanceof String) titulo = (String) detalle;
            else if (detalle instanceof JSONArray && ((JSONArray) detalle).length() > 0) {
                // Error de validación de FastAPI: se toma el primer mensaje.
                JSONObject d0 = ((JSONArray) detalle).optJSONObject(0);
                if (d0 != null) titulo = d0.optString("msg", "");
            }
        }
        if (titulo.isEmpty()) titulo = porDefecto + " (" + r.code + ")";
        String extra = problemas(j);
        return new ApiException(r.code, j.optString("code", ""), extra.isEmpty() ? titulo : titulo + ": " + extra, j);
    }

    /** La política de contraseñas devuelve una lista de problemas: se muestran en vez de ocultarlos. */
    private static String problemas(JSONObject j) {
        JSONArray lista = j.optJSONArray("problemas");
        if (lista == null) {
            JSONObject extra = j.optJSONObject("extra");
            if (extra != null) lista = extra.optJSONArray("problemas");
        }
        if (lista == null || lista.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lista.length(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(lista.optString(i));
        }
        return sb.toString();
    }

    public boolean sesionVencida() { return status == 401; }
}
