package co.guardiania.app.vpn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VPN con IA: el mismo optimizador multicriterio explicable de app/ai/vpn_ai.py (vpn-ai-1.0.0),
 * ejecutado en el teléfono. Puntúa cada servidor 0-100 combinando latencia, carga, cercanía y
 * ajuste al propósito; los pesos cambian según lo que la persona quiere hacer, aprende la latencia
 * real con una media móvil (EWMA) y explica por qué recomienda.
 *
 * <p>El redondeo imita a {@code round()} de Python (mitad-a-par sobre el valor binario exacto) para
 * que el puntaje sea idéntico en el PC y en el celular.
 */
public final class RecomendadorVpn {

    public static final String VERSION_MOTOR = "vpn-ai-1.0.0";
    private static final double ALFA = 0.3;
    public static final Set<String> PRIVACIDAD = Set.of("CH", "IS", "PA", "RO", "SE");
    private static final Map<String, String> REGION = new HashMap<>();
    private static final Map<String, double[]> PESOS = new HashMap<>();   // latencia, carga, distancia, propósito

    static {
        for (String p : new String[]{"CO", "PE", "BR", "AR", "CL"}) REGION.put(p, "SA");
        for (String p : new String[]{"MX", "US", "CA", "PA"}) REGION.put(p, "NA");
        for (String p : new String[]{"ES", "DE", "FR", "GB", "NL", "CH", "SE", "RO", "IS"}) REGION.put(p, "EU");
        for (String p : new String[]{"JP", "SG", "IN"}) REGION.put(p, "AS");
        PESOS.put("general", new double[]{0.35, 0.30, 0.25, 0.10});
        PESOS.put("streaming", new double[]{0.25, 0.35, 0.15, 0.25});
        PESOS.put("gaming", new double[]{0.55, 0.25, 0.15, 0.05});
        PESOS.put("privacy", new double[]{0.15, 0.25, 0.05, 0.55});
        PESOS.put("download", new double[]{0.15, 0.45, 0.15, 0.25});
        // Anonimato: manda la jurisdicción de salida; la cercanía no suma.
        PESOS.put("anonimato", new double[]{0.10, 0.20, 0.00, 0.70});
    }

    public static final class Servidor {
        public final String id, pais, ciudad, protocolo;
        public final boolean premium, activo;
        public final double carga;
        public final int latenciaMs;

        public Servidor(String id, String pais, String ciudad, String protocolo, boolean premium, double carga,
                        int latenciaMs, boolean activo) {
            this.id = id; this.pais = pais; this.ciudad = ciudad; this.protocolo = protocolo;
            this.premium = premium; this.carga = carga; this.latenciaMs = latenciaMs; this.activo = activo;
        }
    }

    public static final class Puntuado {
        public final Servidor servidor;
        public final double puntaje, latencia, carga, cercania, ajuste;
        public final long latenciaEstimadaMs;

        Puntuado(Servidor s, double puntaje, double lat, double carga, double cer, double aj, long estimada) {
            this.servidor = s; this.puntaje = puntaje; this.latencia = lat; this.carga = carga;
            this.cercania = cer; this.ajuste = aj; this.latenciaEstimadaMs = estimada;
        }
    }

    public static final class Recomendacion {
        public final Servidor servidor;
        public final double puntaje;
        public final List<String> motivos;
        public final long latenciaEstimadaMs;

        Recomendacion(Servidor s, double p, List<String> m, long l) {
            servidor = s; puntaje = p; motivos = m; latenciaEstimadaMs = l;
        }
    }

    private RecomendadorVpn() {}

    static double redondear(double x, int decimales) {
        return new BigDecimal(x).setScale(decimales, RoundingMode.HALF_EVEN).doubleValue();
    }

    static double distancia(String paisUsuario, String paisServidor) {
        if (paisUsuario == null) return 0.6;
        if (paisUsuario.equals(paisServidor)) return 1.0;
        String a = REGION.get(paisUsuario), b = REGION.get(paisServidor);
        if (a == null ? b == null : a.equals(b)) return 0.75;
        return 0.35;
    }

    static double ajuste(String proposito, Servidor s, String paisDestino, String paisUsuario) {
        switch (proposito) {
            case "anonimato": {
                double base = Anonimato.jurisdiccion(s.pais).puntaje;
                return base * (paisUsuario != null && s.pais.equals(paisUsuario) ? 0.6 : 1.0);
            }
            case "privacy": return PRIVACIDAD.contains(s.pais) ? 1.0 : 0.4;
            case "streaming": return paisDestino != null && s.pais.equals(paisDestino) ? 1.0 : 0.5;
            case "gaming": return "wireguard".equals(s.protocolo) ? 0.9 : 0.5;
            default: return 0.7;
        }
    }

    public static Puntuado puntuar(Servidor s, String proposito, String paisUsuario, String paisDestino,
                                   Map<String, Double> observada) {
        double[] w = PESOS.getOrDefault(proposito, PESOS.get("general"));
        Double obs = observada.get(s.id);
        double lat = obs != null ? obs : (s.latenciaMs != 0 ? s.latenciaMs : 80.0);
        double pl = Math.max(0.0, 1.0 - Math.min(lat, 400) / 400);
        double pc = Math.max(0.0, 1.0 - s.carga);
        double pd = distancia(paisUsuario, s.pais);
        double pp = ajuste(proposito, s, paisDestino, paisUsuario);
        double total = 100 * (w[0] * pl + w[1] * pc + w[2] * pd + w[3] * pp);
        return new Puntuado(s, redondear(total, 1), redondear(pl, 2), redondear(pc, 2), redondear(pd, 2),
                redondear(pp, 2), (long) redondear(lat, 0));
    }

    public static Recomendacion recomendar(List<Servidor> servidores, String proposito, String paisUsuario,
                                           String paisDestino, boolean permitirPremium, Map<String, Double> observada) {
        if (observada == null) observada = new HashMap<>();
        List<Puntuado> lista = new ArrayList<>();
        for (Servidor s : servidores) {
            if (s.activo && (permitirPremium || !s.premium)) lista.add(puntuar(s, proposito, paisUsuario, paisDestino, observada));
        }
        if (lista.isEmpty()) return null;
        lista.sort(Comparator.comparingDouble((Puntuado p) -> -p.puntaje));   // estable, como sorted() de Python
        Puntuado mejor = lista.get(0);
        List<String> motivos = new ArrayList<>();
        if (mejor.latenciaEstimadaMs <= 60) motivos.add("Baja latencia (~" + mejor.latenciaEstimadaMs + " ms)");
        if (mejor.servidor.carga < 0.4) motivos.add("Servidor poco ocupado");
        if (mejor.cercania >= 0.75 && !"anonimato".equals(proposito)) motivos.add("Cercano a su ubicación");
        if ("anonimato".equals(proposito)) {
            if ("ninguna".equals(Anonimato.jurisdiccion(mejor.servidor.pais).alianza)) {
                motivos.add("Fuera de las alianzas de vigilancia 5/9/14 Ojos (" + mejor.servidor.pais + ")");
            }
            if (paisUsuario != null && !mejor.servidor.pais.equals(paisUsuario)) {
                motivos.add("Sale por otro país: queda fuera de las leyes y del operador de su país");
            }
        }
        if ("privacy".equals(proposito) && PRIVACIDAD.contains(mejor.servidor.pais)) {
            motivos.add("Jurisdicción con buena protección de datos (" + mejor.servidor.pais + ")");
        }
        if ("streaming".equals(proposito) && paisDestino != null && mejor.servidor.pais.equals(paisDestino)) {
            motivos.add("Ubicado en " + paisDestino + " para el catálogo que busca");
        }
        if (motivos.isEmpty()) motivos.add("Mejor equilibrio general entre velocidad y carga");
        return new Recomendacion(mejor.servidor, mejor.puntaje, motivos, mejor.latenciaEstimadaMs);
    }

    public static List<Puntuado> ordenar(List<Servidor> servidores, String proposito, String paisUsuario,
                                         String paisDestino, Map<String, Double> observada, int limite) {
        if (observada == null) observada = new HashMap<>();
        List<Puntuado> lista = new ArrayList<>();
        for (Servidor s : servidores) if (s.activo) lista.add(puntuar(s, proposito, paisUsuario, paisDestino, observada));
        lista.sort(Comparator.comparingDouble((Puntuado p) -> -p.puntaje));
        return lista.subList(0, Math.min(limite, lista.size()));
    }

    /** Media móvil exponencial: el sistema aprende la latencia real que se observa. */
    public static double actualizarLatencia(Double previa, double muestraMs) {
        return previa == null ? muestraMs : redondear(ALFA * muestraMs + (1 - ALFA) * previa, 1);
    }

    public static List<String> saturados(List<Servidor> servidores) {
        List<String> r = new ArrayList<>();
        for (Servidor s : servidores) if (s.activo && s.carga >= 0.9) r.add(s.id);
        return r;
    }
}
