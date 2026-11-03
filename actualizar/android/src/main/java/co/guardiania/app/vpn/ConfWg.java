package co.guardiania.app.vpn;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuración de WireGuard: valida un .conf ajeno y arma el texto final del túnel.
 * Port directo de {@code importar_conf()} y {@code _texto_conf()} de app/local/vpn.py, con los mismos
 * mensajes y las mismas reglas de seguridad:
 * <ul>
 *   <li>Rechaza PreUp/PostUp/PreDown/PostDown (ejecutan comandos).</li>
 *   <li>Exige PrivateKey, Address, PublicKey y Endpoint, con claves en formato WireGuard.</li>
 *   <li>Exige que AllowedIPs incluya 0.0.0.0/0: si no, la IP pública no cambiaría y la VPN «mentiría».</li>
 * </ul>
 */
public final class ConfWg {

    /** Mismo patrón que _CLAVE_WG en Python: 32 bytes en base64. */
    public static final Pattern CLAVE = Pattern.compile("^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$");
    private static final Set<String> PROHIBIDAS = Set.of("preup", "postup", "predown", "postdown");
    public static final int MAX_BYTES = 20_000;

    /** Resultado de importar: la privada va aparte para guardarse cifrada, nunca junto al perfil. */
    public static final class Importada {
        public final String privada;
        public final Map<String, String> interfaz, peer;
        /** Fugas que se cerraron al importar («dns_privado», «ipv6_por_tunel»), como en el PC. */
        public final java.util.List<String> endurecido;

        Importada(String privada, Map<String, String> interfaz, Map<String, String> peer, java.util.List<String> endurecido) {
            this.privada = privada;
            this.interfaz = interfaz;
            this.peer = peer;
            this.endurecido = endurecido;
        }
    }

    public static final class Invalida extends Exception {
        public Invalida(String m) { super(m); }
    }

    private ConfWg() {}

    public static Importada importar(String texto) throws Invalida {
        if (texto.length() > MAX_BYTES) throw new Invalida("El archivo es demasiado grande para ser una configuración de WireGuard");
        String seccion = null;
        Map<String, String> interfaz = new LinkedHashMap<>(), peer = new LinkedHashMap<>();
        boolean otroPeer = false;
        for (String cruda : texto.split("\\r?\\n")) {
            int h = cruda.indexOf('#');
            String linea = (h >= 0 ? cruda.substring(0, h) : cruda).trim();
            if (linea.isEmpty()) continue;
            if (linea.startsWith("[")) {
                seccion = linea.replaceAll("^[\\[\\] ]+|[\\[\\] ]+$", "").toLowerCase(Locale.ROOT);
                // Solo el primer [Peer]: una VPN de salida tiene uno. Los demás se ignoran completos.
                otroPeer = "peer".equals(seccion) && !peer.isEmpty();
                continue;
            }
            int eq = linea.indexOf('=');
            if (eq < 0) throw new Invalida("Línea no válida: «" + recortar(linea, 40) + "»");
            String clave = linea.substring(0, eq).trim(), valor = linea.substring(eq + 1).trim();
            if (PROHIBIDAS.contains(clave.toLowerCase(Locale.ROOT))) {
                throw new Invalida("El archivo trae «" + clave + "», que ejecuta comandos en su equipo. Por seguridad no se "
                        + "acepta: quítelo y vuelva a importarlo.");
            }
            Map<String, String> destino = "interface".equals(seccion) ? interfaz : "peer".equals(seccion) ? peer : null;
            if (destino == null) throw new Invalida("El archivo no tiene las secciones [Interface] y [Peer]");
            if (destino == peer && otroPeer) continue;
            destino.put(clave, valor);
        }
        requerido("PrivateKey", interfaz);
        requerido("Address", interfaz);
        requerido("PublicKey", peer);
        requerido("Endpoint", peer);
        if (!CLAVE.matcher(interfaz.get("PrivateKey")).matches() || !CLAVE.matcher(peer.get("PublicKey")).matches()) {
            throw new Invalida("Las claves del archivo no tienen el formato de WireGuard");
        }
        peer.putIfAbsent("AllowedIPs", "0.0.0.0/0, ::/0");
        if (!peer.get("AllowedIPs").contains("0.0.0.0/0")) {
            throw new Invalida("El archivo no enruta todo el tráfico (AllowedIPs sin 0.0.0.0/0): su IP no cambiaría");
        }
        peer.putIfAbsent("PersistentKeepalive", "25");
        java.util.List<String> endurecido = Anonimato.endurecer(interfaz, peer);   // DNS privado + IPv6 por el túnel
        String privada = interfaz.remove("PrivateKey");
        return new Importada(privada, interfaz, peer, endurecido);
    }

    private static void requerido(String clave, Map<String, String> donde) throws Invalida {
        String v = donde.get(clave);
        if (v == null || v.isEmpty()) throw new Invalida("Falta «" + clave + "» en el archivo");
    }

    private static String recortar(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    /**
     * Texto final del túnel (igual a _texto_conf en Python). Si {@code excluirLan} es true, AllowedIPs
     * 0.0.0.0/0 se reemplaza por «todo internet menos las redes locales»: la IP pública sigue cambiando,
     * pero el teléfono puede seguir hablando con el PC de GuardianIA en el WiFi de la casa.
     */
    public static String texto(String privada, Map<String, String> interfaz, Map<String, String> peer, boolean excluirLan) {
        StringBuilder sb = new StringBuilder("[Interface]\nPrivateKey = ").append(privada).append('\n');
        for (Map.Entry<String, String> e : interfaz.entrySet()) {
            if (!e.getKey().equalsIgnoreCase("privatekey")) sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        sb.append("\n[Peer]\n");
        for (Map.Entry<String, String> e : peer.entrySet()) {
            String v = e.getValue();
            if (excluirLan && e.getKey().equalsIgnoreCase("AllowedIPs") && v.contains("0.0.0.0/0")) {
                v = v.replace("0.0.0.0/0", RedesPublicas.IPV4);
            }
            sb.append(e.getKey()).append(" = ").append(v).append('\n');
        }
        return sb.toString();
    }
}
