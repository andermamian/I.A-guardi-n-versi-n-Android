package co.guardiania.app.vpn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Anonimato de la VPN: qué tan anónimo queda el usuario y cómo cerrar las fugas. Copia exacta de
 * app/ai/anonimato.py (mismos pesos, textos y redondeo; lo comprueba AnonimatoTest).
 *
 * <p>Una VPN cambia la IP, pero eso no basta. Aquí se revisa lo que de verdad delata a una persona:
 * IP, fuga de IPv6, fuga de DNS, kill switch, jurisdicción de salida (5/9/14 Ojos), quién opera el túnel
 * y si GuardianIA guarda registros (no los guarda).
 */
public final class Anonimato {

    public static final String MOTOR = "anonimato-1.0.0";
    static final Set<String> CINCO_OJOS = Set.of("US", "GB", "CA", "AU", "NZ");
    static final Set<String> NUEVE_OJOS = union(CINCO_OJOS, "DK", "FR", "NL", "NO");
    static final Set<String> CATORCE_OJOS = union(NUEVE_OJOS, "DE", "BE", "IT", "SE", "ES");
    /** DNS privado si un .conf no trae DNS: Quad9 (fundación suiza sin fines de lucro). */
    public static final String DNS_PRIVADO = "9.9.9.9, 149.112.112.112, 2620:fe::fe";

    public static final List<String> LIMITES = Collections.unmodifiableList(Arrays.asList(
            "Si inicia sesión en Google, Facebook, su banco u otra cuenta, ese sitio sabe quién es, con o sin VPN.",
            "El navegador puede identificarle por su «huella» (tamaño de pantalla, fuentes, extensiones). "
                    + "Use el modo privado y pocos complementos.",
            "Algunos navegadores filtran la IP real por WebRTC: desactívelo o use Firefox con "
                    + "media.peerconnection.enabled=false.",
            "Para anonimato fuerte (periodismo, activismo) use Tor Browser: una VPN confía en un solo operador; "
                    + "Tor reparte la confianza entre varios."));

    private Anonimato() {}

    private static Set<String> union(Set<String> base, String... mas) {
        java.util.HashSet<String> s = new java.util.HashSet<>(base);
        s.addAll(Arrays.asList(mas));
        return Collections.unmodifiableSet(s);
    }

    // ─────────────────────────── jurisdicción y operador
    public static final class Juicio {
        public final String pais, alianza, texto;
        public final double puntaje;
        Juicio(String pais, String alianza, double puntaje, String texto) {
            this.pais = pais; this.alianza = alianza; this.puntaje = puntaje; this.texto = texto;
        }
    }

    public static Juicio jurisdiccion(String pais) {
        String p = pais == null ? "" : pais.toUpperCase(Locale.ROOT);
        if (p.isEmpty()) return new Juicio(null, "desconocida", 0.5, "País de salida desconocido");
        if (CINCO_OJOS.contains(p)) return new Juicio(p, "5 Ojos", 0.1,
                p + " pertenece a los 5 Ojos: sus agencias comparten datos de vigilancia");
        if (NUEVE_OJOS.contains(p)) return new Juicio(p, "9 Ojos", 0.3, p + " pertenece a los 9 Ojos (intercambio de inteligencia)");
        if (CATORCE_OJOS.contains(p)) return new Juicio(p, "14 Ojos", 0.5, p + " pertenece a los 14 Ojos (intercambio de inteligencia)");
        return new Juicio(p, "ninguna", 1.0, p + " no pertenece a las alianzas 5/9/14 Ojos");
    }


    public static Juicio operador(String proveedor, String nombre) {
        String n = nombre == null ? "" : nombre.toLowerCase(Locale.ROOT);
        if ("tor".equals(proveedor)) return new Juicio(null, null, 1.0, "Tor: su tráfico pasa por 3 relevos y ninguno "
                + "conoce a la vez quién es usted y qué visita.");
        if ("warp".equals(proveedor)) return new Juicio(null, null, 0.35, "Cloudflare WARP cifra su tráfico, pero Cloudflare "
                + "conoce su IP real y la salida suele quedar en su mismo país: protege su privacidad en la red, no le hace anónimo.");
        if ("importado".equals(proveedor) && (n.contains("proton") || n.contains("mullvad") || n.contains("ivpn"))) {
            return new Juicio(null, null, 1.0, "Proveedor con política de no registros auditada públicamente.");
        }
        if ("importado".equals(proveedor)) return new Juicio(null, null, 0.7,
                "Proveedor importado: revise que tenga política de no registros auditada.");
        if ("servidor".equals(proveedor)) return new Juicio(null, null, 0.6, "Servidor propio: usted controla los registros, "
                + "pero si lo contrató a su nombre, la IP de salida puede rastrearse hasta usted.");
        return new Juicio(null, null, 0.0, "Sin túnel configurado.");
    }

    // ─────────────────────────── cerrar fugas en la configuración
    /** DNS privado si falta y ::/0 en AllowedIPs. Modifica los mapas y devuelve qué cambió. */
    public static List<String> endurecer(Map<String, String> interfaz, Map<String, String> peer) {
        List<String> cambios = new ArrayList<>();
        String dns = interfaz.get("DNS");
        if (dns == null || dns.isEmpty()) {
            interfaz.put("DNS", DNS_PRIVADO);
            cambios.add("dns_privado");
        }
        List<String> rutas = new ArrayList<>();
        for (String r : (peer.getOrDefault("AllowedIPs", "")).split(",")) if (!r.trim().isEmpty()) rutas.add(r.trim());
        if (!rutas.contains("::/0")) {
            rutas.add("::/0");
            peer.put("AllowedIPs", String.join(", ", rutas));
            cambios.add("ipv6_por_tunel");
        }
        return cambios;
    }

    // ─────────────────────────── evaluación
    public static final class Revision {
        public final String clave, estado, texto;
        public final int peso;
        public final double puntos;
        Revision(String clave, String estado, String texto, int peso, double puntos) {
            this.clave = clave; this.estado = estado; this.texto = texto; this.peso = peso; this.puntos = puntos;
        }
    }

    public static final class Resultado {
        public final int puntaje;
        public final String nivel;
        public final List<Revision> revisiones;
        public final List<String> consejos;
        Resultado(int puntaje, String nivel, List<Revision> revisiones, List<String> consejos) {
            this.puntaje = puntaje; this.nivel = nivel; this.revisiones = revisiones; this.consejos = consejos;
        }
    }

    /** Los Boolean pueden ser null = no se pudo comprobar (mismo significado que None en Python). */
    public static Resultado evaluar(boolean ipOculta, Boolean ipv6Fuga, Boolean dnsFuga, Boolean killSwitch,
                                    String proveedor, String nombre, String paisSalida, boolean servidorSinRegistros) {
        List<Revision> rs = new ArrayList<>();
        rev(rs, "ip", ipOculta ? "ok" : "falla", ipOculta ? "Su IP real está oculta"
                : "Su IP real sigue visible: el túnel no está funcionando", 20, ipOculta ? 1.0 : 0.0);
        if (ipv6Fuga == null) {
            rev(rs, "ipv6", "ok", "Sin fuga de IPv6 (su conexión no usa IPv6 o va por el túnel)", 15, 1.0);
        } else {
            rev(rs, "ipv6", ipv6Fuga ? "falla" : "ok", ipv6Fuga ? "Fuga de IPv6: su IPv6 real sale fuera del túnel"
                    : "Su IPv6 va por el túnel", 15, ipv6Fuga ? 0.0 : 1.0);
        }
        if (dnsFuga == null) {
            rev(rs, "dns", "aviso", "No se pudo comprobar el DNS", 15, 0.5);
        } else {
            rev(rs, "dns", dnsFuga ? "falla" : "ok", dnsFuga
                    ? "Posible fuga de DNS: sus consultas siguen yendo al mismo resolutor que sin VPN"
                    : "Sus consultas de DNS van por el túnel", 15, dnsFuga ? 0.0 : 1.0);
        }
        if (killSwitch == null) {
            rev(rs, "kill_switch", "aviso", "Active el bloqueo de tráfico sin VPN (kill switch) en los ajustes del sistema", 10, 0.3);
        } else {
            rev(rs, "kill_switch", killSwitch ? "ok" : "falla", killSwitch
                    ? "Si el túnel se cae, el tráfico se bloquea (kill switch)"
                    : "Si el túnel se cae, el tráfico saldría sin cifrar", 10, killSwitch ? 1.0 : 0.0);
        }
        Juicio j = jurisdiccion(paisSalida);
        rev(rs, "jurisdiccion", j.puntaje >= 0.99 ? "ok" : j.puntaje >= 0.3 ? "aviso" : "falla", j.texto, 10, j.puntaje);
        Juicio o = operador(proveedor, nombre);
        rev(rs, "operador", o.puntaje >= 0.99 ? "ok" : o.puntaje >= 0.3 ? "aviso" : "falla", o.texto, 25, o.puntaje);
        rev(rs, "registros", servidorSinRegistros ? "ok" : "falla", servidorSinRegistros
                ? "GuardianIA no guarda su IP real ni el historial de la VPN" : "El servidor guarda su IP real",
                5, servidorSinRegistros ? 1.0 : 0.0);

        double suma = 0;
        for (Revision r : rs) suma += r.puntos;
        int total = (int) RecomendadorVpn.redondear(suma, 0);
        if (!ipOculta) total = Math.min(total, 10);
        if (o.puntaje < 0.7) total = Math.min(total, 79);   // operador que conoce o puede rastrear su IP
        String nivel = total >= 80 ? "alto" : total >= 55 ? "medio" : "bajo";
        List<String> consejos = new ArrayList<>();
        for (Revision r : rs) if (!"ok".equals(r.estado)) consejos.add(r.texto);
        return new Resultado(total, nivel, rs, consejos);
    }

    private static void rev(List<Revision> rs, String clave, String estado, String texto, int peso, double logro) {
        rs.add(new Revision(clave, estado, texto, peso, RecomendadorVpn.redondear(peso * logro, 1)));
    }
}
