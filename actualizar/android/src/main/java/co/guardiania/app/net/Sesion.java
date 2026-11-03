package co.guardiania.app.net;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import co.guardiania.app.data.Vinculo;

/**
 * Sesión de la CUENTA (correo/contraseña), distinta del token del DISPOSITIVO que usa el rastreo:
 * si alguien cierra la sesión, el teléfono sigue reportando su ubicación.
 *
 * <p>Modelo de tokens del backend: el login entrega un <b>refresh token</b> de larga vida (aquí se
 * guarda cifrado con el Keystore) y un <b>access token</b> de 15 min que vive solo en memoria.
 *
 * <p>v2: es un único objeto por proceso (antes cada pantalla tenía el suyo y renovaban el token a la
 * vez, lo que el servidor puede interpretar como robo del refresh token). Toda llamada que recibe un
 * 401 renueva el token UNA vez y reintenta; si la renovación falla, la sesión se cierra limpiamente.
 */
public final class Sesion {

    private static Sesion instancia;

    public static synchronized Sesion de(Context ctx) {
        Vinculo v = new Vinculo(ctx);
        if (instancia == null || !instancia.servidor.equals(v.servidor()) || !instancia.ca.equals(v.ca())) {
            instancia = new Sesion(v);
        }
        return instancia;
    }

    /** Llamar al desvincular: la próxima vez se crea con el servidor nuevo. */
    public static synchronized void olvidar() { instancia = null; }

    private final Vinculo vinculo;
    private final ApiClient api;
    private final String servidor, ca;
    private final Object candado = new Object();
    private String access;          // solo en memoria
    private long accessVence = 0;

    private Sesion(Vinculo v) {
        this.vinculo = v;
        this.servidor = v.servidor();
        this.ca = v.ca();
        this.api = new ApiClient(v.servidor(), v.ca());
    }

    public boolean iniciada() { return vinculo.tieneSesion(); }
    public String email() { return vinculo.email(); }
    public ApiClient cliente() { return api; }

    // ─────────────────────────── acceso
    /** Login con email y contraseña. Si el resultado trae "challenge_id", falta el código 2FA. */
    public JSONObject login(String email, String password) throws IOException {
        JSONObject cuerpo = new JSONObject();
        put(cuerpo, "email", email);
        put(cuerpo, "password", password);
        put(cuerpo, "device_label", "Android · " + Build.MODEL);
        ApiClient.Resp r = api.post("/auth/login", cuerpo);
        if (!r.ok()) throw ApiException.de(r, "No se pudo iniciar sesión");
        JSONObject j = r.json();
        if (j.has("challenge_id")) return j;
        guardar(j, email);
        return j;
    }

    public JSONObject loginMfa(String challengeId, String codigo, String email) throws IOException {
        JSONObject cuerpo = new JSONObject();
        put(cuerpo, "challenge_id", challengeId);
        put(cuerpo, "code", codigo);
        ApiClient.Resp r = api.post("/auth/login/mfa", cuerpo);
        if (!r.ok()) throw ApiException.de(r, "Código incorrecto");
        JSONObject j = r.json();
        guardar(j, email);
        return j;
    }

    /** Crea la cuenta desde el teléfono (mismas reglas de contraseña que la web). */
    public JSONObject registrar(String nombre, String email, String password, boolean datosSalud) throws IOException {
        JSONObject cuerpo = new JSONObject();
        put(cuerpo, "full_name", nombre);
        put(cuerpo, "email", email);
        put(cuerpo, "password", password);
        put(cuerpo, "accept_terms", true);
        put(cuerpo, "accept_health_data", datosSalud);
        put(cuerpo, "device_label", "Android · " + Build.MODEL);
        ApiClient.Resp r = api.post("/auth/register", cuerpo);
        if (!r.ok()) throw ApiException.de(r, "No se pudo crear la cuenta");
        JSONObject j = r.json();
        guardar(j, email);
        return j;
    }

    /** Cierra la sesión en el servidor (revoca el refresh) y la borra del teléfono. */
    public void cerrar() {
        try {
            if (iniciada()) {
                JSONObject cuerpo = new JSONObject();
                put(cuerpo, "refresh_token", vinculo.refreshToken());
                api.post("/auth/logout", cuerpo, accessValido());
            }
        } catch (Exception ignored) {
            // Sin red igual se borra localmente: lo importante es que el teléfono ya no la tenga.
        }
        synchronized (candado) { access = null; accessVence = 0; }
        vinculo.borrarSesion();
    }

    private void guardar(JSONObject tokens, String email) {
        synchronized (candado) {
            access = tokens.optString("access_token", null);
            accessVence = System.currentTimeMillis() + tokens.optInt("expires_in", 900) * 1000L;
        }
        String refresh = tokens.optString("refresh_token", "");
        // En una renovación concurrente el servidor devuelve refresh_token null: se conserva el vigente.
        if (!refresh.isEmpty() && !"null".equals(refresh)) vinculo.guardarSesion(refresh, email);
        JSONObject user = tokens.optJSONObject("user");
        if (user != null) vinculo.guardarNombreUsuario(user.optString("full_name", ""));
    }

    /** Devuelve un access token válido, renovándolo con el refresh si hace falta. Un hilo a la vez. */
    private String accessValido() throws IOException {
        synchronized (candado) {
            if (access != null && System.currentTimeMillis() < accessVence - 30000) return access;
            return renovar();
        }
    }

    private String renovar() throws IOException {
        if (!vinculo.tieneSesion()) throw new ApiException(401, "sin_sesion", "Inicia sesión para usar esta función.", null);
        JSONObject cuerpo = new JSONObject();
        put(cuerpo, "refresh_token", vinculo.refreshToken());
        ApiClient.Resp r = api.post("/auth/refresh", cuerpo);
        if (r.code == 401 || r.code == 403) {
            vinculo.borrarSesion();
            access = null;
            throw new ApiException(401, "sesion_vencida", "Tu sesión expiró. Inicia sesión de nuevo.", null);
        }
        if (!r.ok()) throw ApiException.de(r, "No se pudo renovar la sesión");
        guardar(r.json(), vinculo.email());
        return access;
    }

    private void invalidarAccess() { synchronized (candado) { accessVence = 0; } }

    // ─────────────────────────── llamadas autenticadas
    /** Llamada JSON autenticada; con un 401 renueva el token y reintenta una vez. */
    public ApiClient.Resp llamar(String metodo, String ruta, JSONObject cuerpo) throws IOException {
        ApiClient.Resp r = api.pedir(metodo, ruta, cuerpo, accessValido());
        if (r.code == 401) {
            invalidarAccess();
            r = api.pedir(metodo, ruta, cuerpo, accessValido());
        }
        if (!r.ok()) throw ApiException.de(r, "El servidor rechazó la solicitud");
        return r;
    }

    public JSONObject obj(String metodo, String ruta, JSONObject cuerpo) throws IOException {
        return llamar(metodo, ruta, cuerpo).json();
    }

    public JSONArray lista(String ruta) throws IOException {
        String body = llamar("GET", ruta, null).body;
        try { return new JSONArray(body); } catch (Exception e) { return new JSONArray(); }
    }

    public ApiClient.Resp stream(String ruta, JSONObject cuerpo, ApiClient.OyenteSse oyente, AtomicBoolean cancelar)
            throws IOException {
        ApiClient.Resp r = api.stream(ruta, cuerpo, accessValido(), oyente, cancelar);
        if (r.code == 401) {
            invalidarAccess();
            r = api.stream(ruta, cuerpo, accessValido(), oyente, cancelar);
        }
        if (!r.ok()) throw ApiException.de(r, "El asistente no respondió");
        return r;
    }

    public JSONObject subir(String ruta, Map<String, String> campos, String nombre, String mime, byte[] datos)
            throws IOException {
        ApiClient.Resp r = api.multipart(ruta, campos, "file", nombre, mime, datos, accessValido());
        if (r.code == 401) {
            invalidarAccess();
            r = api.multipart(ruta, campos, "file", nombre, mime, datos, accessValido());
        }
        if (!r.ok()) throw ApiException.de(r, "No se pudo subir el archivo");
        return r.json();
    }

    public ApiClient.Bytes descargar(String ruta) throws IOException {
        ApiClient.Bytes b = api.descargar(ruta, accessValido());
        if (b.code == 401) {
            invalidarAccess();
            b = api.descargar(ruta, accessValido());
        }
        if (!b.ok()) throw new ApiException(b.code, "", "No se pudo descargar (" + b.code + ")", null);
        return b;
    }

    static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) {}
    }
}
