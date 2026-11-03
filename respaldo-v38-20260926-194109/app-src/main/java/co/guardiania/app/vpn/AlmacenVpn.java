package co.guardiania.app.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import co.guardiania.app.data.CajaFuerte;

/**
 * Perfil y estado de la VPN del teléfono (equivalente a perfil.json / estado.json / claves.json de
 * app/local/vpn.py). Las claves privadas se guardan cifradas con el Keystore de Android y NUNCA
 * salen del teléfono: al servidor solo viaja la pública.
 */
public final class AlmacenVpn {

    private static final String ARCHIVO = "guardian_vpn";
    private static final String K_PERFIL = "perfil", K_ESTADO = "estado";
    private static final String K_PRIV_PROPIA = "privada_propia", K_PUB_PROPIA = "publica_propia", K_PRIV_IMPORTADA = "privada_importada";
    private static final String K_EXCLUIR_LAN = "excluir_lan", K_PROPOSITO = "proposito";

    private final SharedPreferences p;

    public AlmacenVpn(Context c) {
        p = c.getApplicationContext().getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE);
    }

    // ─────────────────────────── claves propias del teléfono
    public String privadaPropia() { return leer(K_PRIV_PROPIA); }
    public String publicaPropia() { return p.getString(K_PUB_PROPIA, ""); }

    public void guardarClavesPropias(String privada, String publica) {
        p.edit().putString(K_PRIV_PROPIA, CajaFuerte.cifrar(privada)).putString(K_PUB_PROPIA, publica).apply();
    }

    public String privadaImportada() { return leer(K_PRIV_IMPORTADA); }
    public void guardarPrivadaImportada(String privada) { p.edit().putString(K_PRIV_IMPORTADA, CajaFuerte.cifrar(privada)).apply(); }

    private String leer(String k) {
        String v = p.getString(k, "");
        if (v.isEmpty()) return "";
        String d = CajaFuerte.descifrar(v);
        return d == null ? "" : d;
    }

    // ─────────────────────────── perfil (qué túnel)
    /**
     * proveedor (warp | importado | servidor), nombre, interfaz{}, peer{}, creado, clave_publica_equipo,
     * privada_propia, servidor_id. Nunca contiene una clave privada.
     */
    public JSONObject perfil() {
        String s = p.getString(K_PERFIL, "");
        if (s.isEmpty()) return null;
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    public void guardarPerfil(JSONObject perfil) { p.edit().putString(K_PERFIL, perfil.toString()).apply(); }

    public void borrarPerfil() { p.edit().remove(K_PERFIL).remove(K_PRIV_IMPORTADA).apply(); }

    // ─────────────────────────── ubicaciones (varios .conf, uno por país)
    /** Mapa id → perfil (sin claves privadas; cada privada va cifrada aparte). */
    public JSONObject ubicaciones() {
        try { return new JSONObject(p.getString("ubicaciones", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    public void guardarUbicacion(String id, JSONObject perfil, String privada) {
        JSONObject todas = ubicaciones();
        try { todas.put(id, perfil); } catch (Exception ignored) {}
        p.edit().putString("ubicaciones", todas.toString()).putString("priv_u_" + id, CajaFuerte.cifrar(privada)).apply();
    }

    public String privadaUbicacion(String id) { return leer("priv_u_" + id); }

    public void eliminarUbicacion(String id) {
        JSONObject todas = ubicaciones();
        todas.remove(id);
        p.edit().putString("ubicaciones", todas.toString()).remove("priv_u_" + id).apply();
    }

    // ─────────────────────────── estado del túnel
    public JSONObject estado() {
        try { return new JSONObject(p.getString(K_ESTADO, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    public void guardarEstado(JSONObject e) { p.edit().putString(K_ESTADO, e.toString()).apply(); }

    /** Id estable de este teléfono para el servidor VPN propio (le asigna siempre la misma IP de túnel). */
    public String idEquipo() {
        String id = p.getString("id_equipo", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            p.edit().putString("id_equipo", id).apply();
        }
        return id;
    }

    // ─────────────────────────── preferencias
    public boolean excluirLan() { return p.getBoolean(K_EXCLUIR_LAN, true); }
    public void setExcluirLan(boolean v) { p.edit().putBoolean(K_EXCLUIR_LAN, v).apply(); }
    public String proposito() { return p.getString(K_PROPOSITO, "anonimato"); }   // enfoque: anonimato
    public void setProposito(String v) { p.edit().putString(K_PROPOSITO, v).apply(); }

    static Map<String, String> mapa(JSONObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        if (o == null) return m;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, o.optString(k));
        }
        return m;
    }

    static JSONObject json(Map<String, String> m) {
        JSONObject o = new JSONObject();
        try { for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
        return o;
    }
}
