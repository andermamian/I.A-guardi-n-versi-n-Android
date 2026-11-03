package co.guardiania.app.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Detección de phishing por características léxicas, EN EL TELÉFONO. Port fiel de
 * app/ai/url_risk.py del servidor (misma versión de motor, mismos puntajes): la prueba
 * RiesgoUrlTest compara ambos con los mismos enlaces.
 *
 * <p>Por qué local: revisar un enlace que llegó por WhatsApp no debería requerir enviarlo a ningún
 * servidor, ni tener conexión. Señales (Sahingoz et al., 2019, ajustadas a fraudes en Colombia):
 * host IP, punycode, exceso de subdominios, TLD abusados, marca fuera de su dominio oficial,
 * palabras de robo de credenciales, «@», HTTP sin TLS, longitud y entropía anómalas.
 */
public final class RiesgoUrl {

    public static final String VERSION_MOTOR = "urlrisk-1.2.0";

    static final Map<String, String> MARCAS = new LinkedHashMap<>();
    static {
        String[][] m = {
                {"bancolombia", "bancolombia.com"}, {"davivienda", "davivienda.com"}, {"bbva", "bbva.com.co"},
                {"nequi", "nequi.com.co"}, {"daviplata", "daviplata.com"}, {"bancodebogota", "bancodebogota.com"},
                {"dian", "dian.gov.co"}, {"simit", "fcm.org.co"}, {"servientrega", "servientrega.com"},
                {"interrapidisimo", "interrapidisimo.com"}, {"paypal", "paypal.com"}, {"google", "google.com"},
                {"microsoft", "microsoft.com"}, {"apple", "apple.com"}, {"whatsapp", "whatsapp.com"},
                {"netflix", "netflix.com"}, {"mercadolibre", "mercadolibre.com.co"}, {"facebook", "facebook.com"},
                {"instagram", "instagram.com"},
        };
        for (String[] par : m) MARCAS.put(par[0], par[1]);
    }
    static final Set<String> TLD_SOSPECHOSOS = new HashSet<>(Arrays.asList("xyz", "top", "click", "zip", "mov",
            "icu", "buzz", "rest", "cfd", "sbs", "live", "shop", "support", "online", "site", "fun", "gq", "tk", "ml"));
    static final Set<String> SUFIJOS_COMPUESTOS = new HashSet<>(Arrays.asList("com.co", "gov.co", "edu.co", "org.co",
            "net.co", "com.mx", "com.br", "co.uk", "com.ar"));
    static final Set<String> SENUELOS = new HashSet<>(Arrays.asList("login", "verify", "verificar", "seguro", "secure",
            "update", "actualizar", "cuenta", "account", "banking", "confirm", "confirmar", "password", "clave",
            "desbloquear", "suspendida", "premio", "multa", "pago", "wallet", "otp", "token"));
    static final Map<String, String[]> CATEGORIAS = new LinkedHashMap<>();
    static {
        CATEGORIAS.put("adultos", new String[]{"porn", "xxx", "sex", "adult"});
        CATEGORIAS.put("apuestas", new String[]{"bet", "casino", "apuesta", "poker", "slots"});
        CATEGORIAS.put("juegos", new String[]{"game", "juego", "roblox", "steam", "epicgames"});
        CATEGORIAS.put("redes_sociales", new String[]{"facebook", "instagram", "tiktok", "twitter", "x.com", "snapchat"});
    }
    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern URL_EN_TEXTO = Pattern.compile(
            "https?://\\S+|\\b[\\w-]+\\.(?:com|co|net|org|xyz|top|click|info|online)\\S*");

    /** Resultado del análisis. veredicto: permitir | advertir | bloquear. */
    public static final class Veredicto {
        public final String host, dominio, veredicto, categoria;
        public final int puntaje;
        public final List<Motivo> motivos;
        Veredicto(String host, String dominio, int puntaje, String veredicto, String categoria, List<Motivo> motivos) {
            this.host = host; this.dominio = dominio; this.puntaje = puntaje; this.veredicto = veredicto;
            this.categoria = categoria; this.motivos = motivos;
        }
        public String etiqueta() {
            switch (veredicto) {
                case "bloquear": return "Parece fraudulento";
                case "advertir": return "Tiene señales sospechosas";
                default: return "No muestra señales de riesgo";
            }
        }
    }

    public static final class Motivo {
        public final int puntos;
        public final String motivo;
        Motivo(int puntos, String motivo) { this.puntos = puntos; this.motivo = motivo; }
    }

    private RiesgoUrl() {}

    /** Extrae hasta 3 enlaces de un texto libre (mensaje del chat). */
    public static List<String> enlacesEn(String texto) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = URL_EN_TEXTO.matcher(texto);
        while (m.find() && out.size() < 3) out.add(m.group());
        return out;
    }

    public static String dominioRegistrable(String host) {
        String[] p = host.split("\\.");
        if (p.length >= 3 && SUFIJOS_COMPUESTOS.contains(p[p.length - 2] + "." + p[p.length - 1])) {
            return p[p.length - 3] + "." + p[p.length - 2] + "." + p[p.length - 1];
        }
        if (p.length >= 2) return p[p.length - 2] + "." + p[p.length - 1];
        return host;
    }

    static double entropia(String s) {
        if (s.isEmpty()) return 0;
        Map<Character, Integer> c = new HashMap<>();
        for (char ch : s.toCharArray()) c.merge(ch, 1, Integer::sum);
        double h = 0;
        for (int n : c.values()) {
            double p = (double) n / s.length();
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    public static Veredicto evaluar(String url) {
        String raw = url.trim();
        if (!raw.contains("://")) raw = "http://" + raw;
        int iEsquema = raw.indexOf("://");
        String esquema = raw.substring(0, iEsquema).toLowerCase(Locale.ROOT);
        String resto = raw.substring(iEsquema + 3);
        int fin = resto.length();
        for (char ch : new char[]{'/', '?', '#'}) {
            int i = resto.indexOf(ch);
            if (i >= 0 && i < fin) fin = i;
        }
        String netloc = resto.substring(0, fin);
        String cola = resto.substring(fin);
        String hostPuerto = netloc.contains("@") ? netloc.substring(netloc.lastIndexOf('@') + 1) : netloc;
        String host;
        if (hostPuerto.startsWith("[")) {
            int cierre = hostPuerto.indexOf(']');
            host = cierre > 0 ? hostPuerto.substring(1, cierre) : hostPuerto;
        } else {
            int dp = hostPuerto.indexOf(':');
            host = dp >= 0 ? hostPuerto.substring(0, dp) : hostPuerto;
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) throw new IllegalArgumentException("URL inválida");
        int iFrag = cola.indexOf('#');
        if (iFrag >= 0) cola = cola.substring(0, iFrag);
        int iQuery = cola.indexOf('?');
        String ruta = iQuery >= 0 ? cola.substring(0, iQuery) : cola;
        String query = iQuery >= 0 ? cola.substring(iQuery + 1) : "";

        String dominio = dominioRegistrable(host);
        String categoria = null;
        for (Map.Entry<String, String[]> e : CATEGORIAS.entrySet()) {
            for (String pista : e.getValue()) if (host.contains(pista)) { categoria = e.getKey(); break; }
            if (categoria != null) break;
        }

        List<Motivo> motivos = new ArrayList<>();
        if (IPV4.matcher(host).matches() || host.contains(":")) {
            motivos.add(new Motivo(30, "Usa una dirección IP en lugar de un nombre de dominio"));
        }
        if (host.contains("xn--")) motivos.add(new Motivo(30, "Dominio con caracteres internacionales que pueden imitar otra marca"));
        if (netloc.contains("@")) motivos.add(new Motivo(25, "Contiene «@», un truco para ocultar el destino real"));
        if ("http".equals(esquema)) motivos.add(new Motivo(10, "Conexión sin cifrar (HTTP)"));
        String[] etiquetas = host.split("\\.");
        if (etiquetas.length - dominio.split("\\.").length >= 3) motivos.add(new Motivo(12, "Demasiados subdominios"));
        String tld = etiquetas[etiquetas.length - 1];
        if (TLD_SOSPECHOSOS.contains(tld)) motivos.add(new Motivo(12, "Extensión ." + tld + " muy usada en fraudes"));
        Set<String> tokens = new HashSet<>(Arrays.asList(host.split("[.\\-]")));
        String sinGuiones = host.replace("-", "");
        for (Map.Entry<String, String> e : MARCAS.entrySet()) {
            String marca = e.getKey(), oficial = e.getValue();
            boolean mencionada = tokens.contains(marca) || (marca.length() >= 8 && sinGuiones.contains(marca));
            if (mencionada && !(host.equals(oficial) || host.endsWith("." + oficial))) {
                motivos.add(new Motivo(40, "Menciona «" + marca + "» pero no es el sitio oficial (" + oficial + ")"));
                break;
            }
        }
        String rutaQ = (ruta + "?" + query).toLowerCase(Locale.ROOT);
        TreeSet<String> senuelos = new TreeSet<>();
        for (String w : SENUELOS) if (rutaQ.contains(w) || host.contains(w)) senuelos.add(w);
        if (!senuelos.isEmpty()) {
            List<String> primeros = new ArrayList<>(senuelos).subList(0, Math.min(4, senuelos.size()));
            motivos.add(new Motivo(Math.min(20, 7 * senuelos.size()),
                    "Palabras típicas de robo de credenciales: " + String.join(", ", primeros)));
        }
        int guiones = 0;
        for (char ch : host.toCharArray()) if (ch == '-') guiones++;
        if (guiones >= 3) motivos.add(new Motivo(8, "Muchos guiones en el dominio"));
        if (raw.length() > 120) motivos.add(new Motivo(6, "URL inusualmente larga"));
        String primera = etiquetas.length > 2 ? etiquetas[0] : dominio.split("\\.")[0];
        if (primera.length() >= 12 && entropia(primera) > 3.6) motivos.add(new Motivo(10, "Nombre de dominio con apariencia aleatoria"));

        int puntaje = 0;
        for (Motivo m : motivos) puntaje += m.puntos;
        puntaje = Math.min(100, puntaje);
        String veredicto = puntaje >= 50 ? "bloquear" : puntaje >= 25 ? "advertir" : "permitir";
        motivos.sort((a, b) -> b.puntos - a.puntos);   // estable, como sorted() de Python
        return new Veredicto(host, dominio, puntaje, veredicto, categoria, motivos);
    }
}
