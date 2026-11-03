package co.guardiania.app.vpn;

import android.content.Context;
import android.content.Intent;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyPair;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import co.guardiania.app.net.Sesion;

/**
 * La VPN del teléfono: WireGuard real con el motor oficial (GoBackend sobre VpnService). Mismos
 * requisitos que app/local/vpn.py en el PC:
 * <ul>
 *   <li>Tres orígenes, los tres WireGuard real: Cloudflare WARP (gratis, sin cuenta), un .conf
 *       importado (ProtonVPN u otro) o un servidor propio registrado en GuardianIA.</li>
 *   <li>La clave privada se genera en el teléfono y nunca sale de él.</li>
 *   <li><b>Nada se marca como conectado sin prueba</b>: se mide la IP pública antes y después;
 *       conectado = la IP cambió o Cloudflare confirma warp=on.</li>
 *   <li>Con servidor propio, la sesión se registra en la cuenta (/vpn/conectar → /vpn/peer →
 *       /vpn/tunel), así se ve y se puede cortar desde el PC.</li>
 * </ul>
 * Todas las operaciones bloquean: llamarlas en segundo plano.
 */
public final class MotorVpn {

    public static final String NOMBRE = "guardiania";

    private static MotorVpn instancia;
    private final Context ctx;
    private final AlmacenVpn almacen;
    private final GoBackend backend;
    private volatile Tunnel.State estadoTunel = Tunnel.State.DOWN;

    private final Tunnel tunel = new Tunnel() {
        @Override public String getName() { return NOMBRE; }
        @Override public void onStateChange(State s) { estadoTunel = s; }
    };

    public static synchronized MotorVpn de(Context c) {
        if (instancia == null) instancia = new MotorVpn(c.getApplicationContext());
        return instancia;
    }

    private MotorVpn(Context c) {
        ctx = c;
        almacen = new AlmacenVpn(c);
        backend = new GoBackend(c);
    }

    public AlmacenVpn almacen() { return almacen; }

    /** Intent que Android exige mostrar la primera vez (permiso de VPN). null = ya concedido. */
    public static Intent permiso(Context c) { return android.net.VpnService.prepare(c); }

    public boolean activo() {
        try { return backend.getState(tunel) == Tunnel.State.UP; } catch (Exception e) { return estadoTunel == Tunnel.State.UP; }
    }

    // ─────────────────────────── claves
    /** (privada, pública) del teléfono; se crean una vez. La privada queda cifrada con el Keystore. */
    public synchronized String[] claves() {
        String priv = almacen.privadaPropia(), pub = almacen.publicaPropia();
        if (!priv.isEmpty() && !pub.isEmpty()) return new String[]{priv, pub};
        KeyPair par = new KeyPair();
        priv = par.getPrivateKey().toBase64();
        pub = par.getPublicKey().toBase64();
        almacen.guardarClavesPropias(priv, pub);
        return new String[]{priv, pub};
    }

    static String publicaDe(String privada) throws IOException {
        try {
            return new KeyPair(Key.fromBase64(privada)).getPublicKey().toBase64();
        } catch (Exception e) {
            throw new IOException("La clave privada del archivo no es válida", e);
        }
    }

    public JSONObject importar(String texto, String nombre) throws IOException { return importar(texto, nombre, true); }

    /**
     * @param activar true = queda como túnel en uso (importación manual). false = solo se agrega a las
     *                ubicaciones (importación automática: no cambia el túnel que la persona usa ahora).
     * @return el perfil, o null si esa ubicación ya existía y no se activó.
     */
    public JSONObject importar(String texto, String nombre, boolean activar) throws IOException {
        ConfWg.Importada imp;
        try {
            imp = ConfWg.importar(texto);
        } catch (ConfWg.Invalida e) {
            throw new IOException(e.getMessage(), e);
        }
        String publica = publicaDe(imp.privada);
        String repetida = idRepetida(imp.peer.get("PublicKey"), imp.peer.get("Endpoint"));
        if (repetida != null && !activar) return null;               // ya estaba: nada que hacer
        if (activar) almacen.guardarPrivadaImportada(imp.privada);
        try {
            JSONObject perfil = new JSONObject();
            perfil.put("proveedor", "importado");
            String n = nombre == null || nombre.isEmpty() ? "VPN importada" : nombre;
            perfil.put("nombre", n.length() > 60 ? n.substring(0, 60) : n);
            perfil.put("creado", System.currentTimeMillis() / 1000.0);
            perfil.put("clave_publica_equipo", publica);
            perfil.put("privada_propia", false);
            perfil.put("interfaz", AlmacenVpn.json(imp.interfaz));
            perfil.put("peer", AlmacenVpn.json(imp.peer));
            perfil.put("endurecido", new org.json.JSONArray(imp.endurecido));
            String pais = Ubicaciones.paisDesdeNombre(n);
            if (pais != null) perfil.put("pais", pais);
            // Biblioteca de ubicaciones: sin duplicar (misma clave del servidor + mismo endpoint).
            String id = idRepetida(imp.peer.get("PublicKey"), imp.peer.get("Endpoint"));
            if (id == null) id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            perfil.put("id", id);
            almacen.guardarUbicacion(id, perfil, imp.privada);
            if (activar) almacen.guardarPerfil(perfil);
            return perfil;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private String idRepetida(String clave, String endpoint) {
        JSONObject todas = almacen.ubicaciones();
        for (java.util.Iterator<String> it = todas.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject u = todas.optJSONObject(k), peer = u != null ? u.optJSONObject("peer") : null;
            if (peer != null && peer.optString("PublicKey").equals(clave) && peer.optString("Endpoint").equals(endpoint)) return k;
        }
        return null;
    }

    /** Ubicaciones guardadas, ordenadas por país, con cuál está en uso. */
    public java.util.List<JSONObject> ubicaciones() {
        java.util.List<JSONObject> r = new java.util.ArrayList<>();
        JSONObject todas = almacen.ubicaciones();
        JSONObject actual = almacen.perfil();
        String enUso = actual != null ? actual.optString("id", "") : "";
        for (java.util.Iterator<String> it = todas.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject u = todas.optJSONObject(k);
            if (u == null) continue;
            try {
                String pais = u.optString("pais_verificado", u.optString("pais", ""));
                r.add(new JSONObject().put("id", k).put("nombre", u.optString("nombre"))
                        .put("pais", pais.isEmpty() ? JSONObject.NULL : pais)
                        .put("verificado", u.has("pais_verificado"))
                        .put("endpoint", u.optJSONObject("peer") != null ? u.optJSONObject("peer").optString("Endpoint") : "")
                        .put("en_uso", k.equals(enUso))
                        .put("propio", u.optBoolean("propio")));
            } catch (Exception ignored) {}
        }
        // Tu servidor GuardianIA VPN primero; luego por país.
        r.sort((a, b) -> a.optBoolean("propio") != b.optBoolean("propio") ? (a.optBoolean("propio") ? -1 : 1)
                : Ubicaciones.nombre(a.isNull("pais") ? null : a.optString("pais"))
                .compareTo(Ubicaciones.nombre(b.isNull("pais") ? null : b.optString("pais"))));
        return r;
    }

    // ─────────────────────────── servidor VPN propio de GuardianIA
    /** ¿Ya está dado de alta este teléfono en el servidor propio? */
    public boolean tienePropio() {
        for (JSONObject u : ubicaciones()) if (u.optBoolean("propio")) return true;
        return false;
    }

    /**
     * Da de alta este teléfono en el servidor VPN propio A TRAVÉS del PC (con la sesión del dueño).
     * Solo viaja la clave PÚBLICA del teléfono; la ficha secreta del servidor nunca llega aquí.
     * Bloquea: llamar en segundo plano.
     */
    public synchronized JSONObject configurarPropio() throws IOException {
        String[] par = claves();
        JSONObject r;
        try {
            r = Sesion.de(ctx).obj("POST", "/vpn/propio/alta",
                    new JSONObject().put("clave_publica", par[1]).put("nombre", android.os.Build.MODEL));
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage(), e);
        }
        return guardarPropio(r, par[0]);
    }

    /** Valida la respuesta del servidor y la guarda como ubicación (separado para poder probarlo). */
    JSONObject guardarPropio(JSONObject r, String privada) throws IOException {
        String direccion = r.optString("direccion"), dns = r.optString("dns"), clave = r.optString("clave_publica_servidor"),
                endpoint = r.optString("endpoint");
        if (!direccion.matches("[0-9a-f.:/, ]{9,80}") || !dns.matches("[0-9a-f.:, ]{7,60}")
                || !clave.matches("[A-Za-z0-9+/]{43}=") || !endpoint.matches("[A-Za-z0-9.\\-\\[\\]:]{3,80}:\\d{1,5}")) {
            throw new IOException("La respuesta del servidor VPN no es válida.");
        }
        for (JSONObject u : ubicaciones()) if (u.optBoolean("propio")) almacen.eliminarUbicacion(u.optString("id"));
        try {
            String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            JSONObject interfaz = new JSONObject().put("Address", direccion).put("DNS", dns);
            JSONObject peer = new JSONObject().put("PublicKey", clave).put("Endpoint", endpoint)
                    .put("AllowedIPs", r.optString("allowed_ips", "0.0.0.0/0, ::/0"))
                    .put("PersistentKeepalive", String.valueOf(r.optInt("keepalive", 25)));
            String pais = r.optString("pais", "");
            JSONObject perfil = new JSONObject().put("proveedor", "servidor").put("propio", true).put("id", id)
                    .put("nombre", r.optString("nombre", "GuardianIA VPN")).put("privada_propia", false)
                    .put("creado", System.currentTimeMillis() / 1000.0).put("interfaz", interfaz).put("peer", peer)
                    .put("endurecido", new org.json.JSONArray());
            if (pais.matches("[A-Z]{2}")) perfil.put("pais", pais);
            almacen.guardarUbicacion(id, perfil, privada);
            return perfil;
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Cambia el túnel a esa ubicación; si la VPN está arriba, reconecta desde ahí. Bloquea. */
    public JSONObject usarUbicacion(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}")) throw new IOException("Ubicación no válida");
        JSONObject u = almacen.ubicaciones().optJSONObject(id);
        String privada = almacen.privadaUbicacion(id);
        if (u == null || privada.isEmpty()) throw new IOException("Esa ubicación ya no existe");
        almacen.guardarPrivadaImportada(privada);
        almacen.guardarPerfil(u);
        return activo() ? conectar() : null;
    }

    public void eliminarUbicacion(String id) { almacen.eliminarUbicacion(id); }

    /**
     * UN botón: GuardianIA ordena las ubicaciones para ese propósito, conecta la mejor y VERIFICA que la
     * IP cambió; si falla, prueba la siguiente (hasta 3). Nunca deja un túnel sin verificar encendido.
     * Bloquea: llamar en segundo plano.
     */
    public synchronized JSONObject conectarAutomatico(String proposito, String paisDestino) throws IOException {
        String paisUsuario = java.util.Locale.getDefault().getCountry();
        java.util.List<JSONObject> orden = RecomendadorUbicacion.ordenar(ubicaciones(), proposito, paisUsuario, paisDestino);
        if (orden.isEmpty()) {
            if (almacen.perfil() == null) throw new IOException("Aún no hay ubicaciones de ProtonVPN.");
            return conectar();
        }
        IOException ultimo = null;
        for (int i = 0; i < Math.min(3, orden.size()); i++) {
            JSONObject u = orden.get(i);
            try {
                if (activo()) desconectar();
                usarUbicacion(u.optString("id"));
                JSONObject e = conectar();
                if (e.optBoolean("verificado")) {
                    try {
                        e.put("ubicacion", u.optString("nombre"));
                        e.put("razon", RecomendadorUbicacion.razon(u, proposito, paisUsuario, paisDestino));
                        e.put("intentos", i + 1);
                    } catch (Exception ignored) {}
                    return e;
                }
                ultimo = new IOException("Con «" + u.optString("nombre") + "» la IP no cambió");
            } catch (IOException ex) {
                ultimo = ex;
            }
        }
        if (activo()) desconectar();         // no se deja un túnel encendido que no protege
        throw new IOException("Probé " + Math.min(3, orden.size()) + " ubicación(es) y ninguna protegió la conexión. "
                + (ultimo != null ? ultimo.getMessage() : ""));
    }

    private void confirmarPais(String paisReal) {
        JSONObject p = almacen.perfil();
        if (paisReal == null || p == null || p.optString("id", "").isEmpty()) return;
        String id = p.optString("id");
        JSONObject u = almacen.ubicaciones().optJSONObject(id);
        String privada = almacen.privadaUbicacion(id);
        if (u == null || privada.isEmpty()) return;
        try { u.put("pais_verificado", paisReal.toUpperCase(java.util.Locale.ROOT)); } catch (Exception ignored) {}
        almacen.guardarUbicacion(id, u, privada);
    }

    String textoConf(JSONObject perfil) throws IOException {
        String privada = perfil.optBoolean("privada_propia", true) ? claves()[0] : almacen.privadaImportada();
        if (privada.isEmpty()) throw new IOException("Falta la clave del túnel: vuelve a importar el archivo.");
        return ConfWg.texto(privada, AlmacenVpn.mapa(perfil.optJSONObject("interfaz")),
                AlmacenVpn.mapa(perfil.optJSONObject("peer")), almacen.excluirLan());
    }

    // ─────────────────────────── conectar / desconectar
    /** Resultado de conectar: estado guardado (ip_previa, ip_salida, pais, warp, verificado…). */
    public synchronized JSONObject conectar() throws IOException {
        JSONObject perfil = almacen.perfil();
        if (perfil == null) throw new IOException("Primero elige un túnel: Cloudflare WARP, un archivo .conf o tu servidor.");
        if (permiso(ctx) != null) throw new IOException("Falta el permiso de VPN de Android. Toca «Conectar» de nuevo y acéptalo.");

        Internet.Traza antes = activo() ? null : Internet.traza(6000);
        // Para la prueba de anonimato: IPv6 y resolutor DNS SIN la VPN (se comparan con los de después).
        String ip6Antes = antes != null ? Internet.ipv6Publica() : almacen.estado().optString("ip6_previa", null);
        String dnsAntes = antes != null ? Internet.resolutorDns() : almacen.estado().optString("dns_previo", null);
        Config config;
        try {
            config = Config.parse(new ByteArrayInputStream(textoConf(perfil).getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("La configuración del túnel no es válida: " + e.getMessage(), e);
        }
        try {
            if (activo()) backend.setState(tunel, Tunnel.State.DOWN, null);   // reinstala limpio
            backend.setState(tunel, Tunnel.State.UP, config);
        } catch (Exception e) {
            reportar(perfil, "error", null, null, 0, "no inició");
            throw new IOException("WireGuard no pudo iniciar el túnel: " + e.getMessage(), e);
        }

        // Verificación: hasta 20 s para el primer apretón de manos y el cambio de IP.
        Internet.Traza despues = null;
        boolean verificado = false;
        for (int i = 0; i < 10 && !verificado; i++) {
            dormir(2000);
            despues = Internet.traza(4000);
            verificado = despues.ip != null && ((antes != null && antes.ip != null && !despues.ip.equals(antes.ip))
                    || despues.porWarp());
        }
        Long latencia = Internet.latenciaMs();
        if (verificado && despues != null) confirmarPais(despues.pais);   // el país que de verdad ven los sitios
        // Pruebas de anonimato con el túnel arriba.
        Boolean[] f = verificado ? Internet.fugas(ip6Antes, dnsAntes, Internet.ipv6Publica(), Internet.resolutorDns())
                : new Boolean[]{null, null};
        JSONObject e = new JSONObject();
        try {
            e.put("desde", System.currentTimeMillis());
            e.put("ip_previa", antes == null ? JSONObject.NULL : antes.ip);
            e.put("ip_salida", despues == null ? JSONObject.NULL : despues.ip);
            e.put("pais", despues == null ? JSONObject.NULL : despues.pais);
            e.put("warp", despues == null ? JSONObject.NULL : despues.warp);
            e.put("colo", despues == null ? JSONObject.NULL : despues.colo);
            e.put("verificado", verificado);
            e.put("proveedor", perfil.optString("proveedor"));
            if (latencia != null) e.put("latencia_ms", latencia);
            // La IP real, la IPv6 y el resolutor se quedan SOLO en el teléfono (nunca se envían al servidor).
            if (ip6Antes != null) e.put("ip6_previa", ip6Antes);
            if (dnsAntes != null) e.put("dns_previo", dnsAntes);
            e.put("ipv6_fuga", f[0] == null ? JSONObject.NULL : f[0]);
            e.put("dns_fuga", f[1] == null ? JSONObject.NULL : f[1]);
        } catch (Exception ignored) {}
        almacen.guardarEstado(e);
        reportar(perfil, "conectada", despues == null ? null : despues.ip, verificado, latencia == null ? 0 : latencia, null);
        return e;
    }

    public synchronized JSONObject desconectar() throws IOException {
        JSONObject previo = almacen.estado();
        try {
            backend.setState(tunel, Tunnel.State.DOWN, null);
        } catch (Exception e) {
            throw new IOException("No se pudo cerrar el túnel: " + e.getMessage(), e);
        }
        almacen.guardarEstado(new JSONObject());
        JSONObject perfil = almacen.perfil();
        if (perfil != null && "servidor".equals(perfil.optString("proveedor")) && !perfil.optBoolean("propio")
                && Sesion.de(ctx).iniciada()) {
            try { Sesion.de(ctx).obj("POST", "/vpn/desconectar", new JSONObject()); } catch (Exception ignored) {}
        }
        return previo;
    }

    /** Vuelve a comprobar que el tráfico sale por el túnel (botón «Verificar»). */
    public JSONObject verificar() {
        JSONObject e = almacen.estado();
        Internet.Traza ahora = Internet.traza(6000);
        boolean arriba = activo();
        String previa = e.optString("ip_previa", "");
        boolean verificado = arriba && ahora.ip != null && ((!previa.isEmpty() && !"null".equals(previa)
                && !ahora.ip.equals(previa)) || ahora.porWarp());
        try {
            if (arriba) {
                e.put("ip_salida", ahora.ip);
                e.put("pais", ahora.pais);
                e.put("warp", ahora.warp);
                e.put("colo", ahora.colo);
                e.put("verificado", verificado);
                almacen.guardarEstado(e);
            }
            return new JSONObject().put("activo", arriba).put("verificado", verificado).put("ip", ahora.ip)
                    .put("pais", ahora.pais).put("warp", ahora.warp).put("ip_previa", previa);
        } catch (Exception ex) {
            return new JSONObject();
        }
    }

    /** Cifras en vivo: tiempo conectado, datos, latencia y último apretón de manos. */
    public JSONObject estadisticas(boolean medirLatencia) {
        JSONObject e = almacen.estado();
        boolean arriba = activo();
        JSONObject r = new JSONObject();
        try {
            r.put("activo", arriba);
            r.put("verificado", arriba && e.optBoolean("verificado"));
            long desde = e.optLong("desde", 0);
            r.put("segundos_conectado", arriba && desde > 0 ? (System.currentTimeMillis() - desde) / 1000 : 0);
            r.put("ip_salida", e.opt("ip_salida"));
            r.put("ip_previa", e.opt("ip_previa"));
            r.put("pais_salida", e.opt("pais"));
            r.put("warp", e.opt("warp"));
            r.put("colo", e.opt("colo"));
            if (arriba) {
                Statistics st = backend.getStatistics(tunel);
                if (st != null) {
                    r.put("recibidos_bytes", st.totalRx());
                    r.put("enviados_bytes", st.totalTx());
                    long hs = 0;
                    for (Key k : st.peers()) {
                        Statistics.PeerStats ps = st.peer(k);
                        if (ps != null) hs = Math.max(hs, ps.latestHandshakeEpochMillis());
                    }
                    if (hs > 0) {
                        long hace = (System.currentTimeMillis() - hs) / 1000;
                        r.put("ultimo_handshake_hace_s", hace);
                        r.put("handshake_ok", hace < 180);
                    } else {
                        r.put("handshake_ok", false);
                    }
                }
                if (medirLatencia) {
                    Long lat = Internet.latenciaMs();
                    if (lat != null) { e.put("latencia_ms", lat); almacen.guardarEstado(e); }
                }
            }
            r.put("latencia_ms", e.opt("latencia_ms"));
            JSONObject perfil = almacen.perfil();
            r.put("proveedor", perfil == null ? JSONObject.NULL : perfil.optString("proveedor"));
        } catch (Exception ignored) {}
        return r;
    }

    /**
     * Nivel de anonimato del túnel actual (mismo motor que el PC). Con {@code repetir} vuelve a medir
     * IPv6 y DNS. En Android el kill switch lo activa la persona en Ajustes (VPN siempre activa +
     * bloquear conexiones sin VPN): la app no puede leerlo, así que se muestra como recomendación.
     */
    public Anonimato.Resultado anonimato(boolean repetir) {
        JSONObject e = almacen.estado();
        JSONObject perfil = almacen.perfil();
        boolean arriba = activo();
        if (arriba && repetir) {
            Internet.Traza t = Internet.traza(6000);
            Boolean[] f = Internet.fugas(e.optString("ip6_previa", null), e.optString("dns_previo", null),
                    Internet.ipv6Publica(), Internet.resolutorDns());
            try {
                e.put("ipv6_fuga", f[0] == null ? JSONObject.NULL : f[0]);
                e.put("dns_fuga", f[1] == null ? JSONObject.NULL : f[1]);
                if (t.ip != null) {
                    e.put("ip_salida", t.ip);
                    e.put("pais", t.pais == null ? JSONObject.NULL : t.pais);
                    String previa = e.optString("ip_previa", "");
                    e.put("verificado", (!previa.isEmpty() && !"null".equals(previa) && !t.ip.equals(previa)) || t.porWarp());
                }
            } catch (Exception ignored) {}
            almacen.guardarEstado(e);
        }
        Boolean ip6 = arriba && e.has("ipv6_fuga") && !e.isNull("ipv6_fuga") ? e.optBoolean("ipv6_fuga") : null;
        Boolean dns = arriba && e.has("dns_fuga") && !e.isNull("dns_fuga") ? e.optBoolean("dns_fuga") : null;
        String pais = arriba && !e.isNull("pais") ? e.optString("pais", null) : null;
        return Anonimato.evaluar(arriba && e.optBoolean("verificado"), ip6, dns, null,
                perfil != null ? perfil.optString("proveedor", null) : null,
                perfil != null ? perfil.optString("nombre", null) : null, pais, true);
    }

    /** Con servidor propio, informa el resultado a la cuenta. El servidor exige que la IP haya cambiado. */
    private void reportar(JSONObject perfil, String estado, String ipSalida, Boolean ipCambio, long latencia, String motivo) {
        if (!"servidor".equals(perfil.optString("proveedor")) || !Sesion.de(ctx).iniciada()) return;
        String sesionId = perfil.optString("sesion_id", "");
        if (sesionId.isEmpty()) return;
        try {
            JSONObject cuerpo = new JSONObject().put("sesion_id", sesionId).put("estado", estado)
                    .put("latencia_ms", Math.min(latencia, 60000));
            if (ipSalida != null) cuerpo.put("ip_salida", ipSalida);
            // Anonimato: solo se informa SI la IP cambió; la IP real nunca sale del teléfono.
            if (ipCambio != null) cuerpo.put("ip_cambio", ipCambio);
            if (motivo != null) cuerpo.put("motivo", motivo);
            Sesion.de(ctx).obj("POST", "/vpn/tunel", cuerpo);
        } catch (Exception ignored) {}
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
