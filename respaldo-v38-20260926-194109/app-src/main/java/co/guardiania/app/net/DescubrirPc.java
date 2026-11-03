package co.guardiania.app.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Encuentra solo el PC de GuardianIA en la red local, para que iniciar sesión sea solo correo y
 * contraseña.
 *
 * <p>Revisa cada red local del teléfono (el WiFi y también el punto de acceso, si el teléfono lo
 * comparte): prueba el puerto 8443 en las direcciones de esa red y se queda solo con los equipos cuyo
 * certificado lo emitió una CA de GuardianIA. Una impresora o un router con el 8443 abierto no pasa
 * ese filtro. Tarda unos segundos; se usa solo cuando el PC guardado no responde.
 *
 * <p>No depende de Android: se prueba en la JVM.
 */
public final class DescubrirPc {

    private static final int HILOS = 64;
    private static final int ESPERA_MS = 400;

    private DescubrirPc() {}

    /** Direcciones https://IP:8443 de los PC de GuardianIA encontrados (normalmente uno). */
    public static List<String> buscar() {
        return buscar(redesLocales(), ConexionPc.PUERTO_HTTPS);
    }

    static List<String> buscar(List<String> candidatas, int puerto) {
        ExecutorService pool = Executors.newFixedThreadPool(HILOS);
        List<Future<String>> tareas = new ArrayList<>();
        for (String ip : candidatas) {
            tareas.add(pool.submit(() -> puertoAbierto(ip, puerto) && esGuardianIa(ip, puerto)
                    ? "https://" + ip + ":" + puerto : null));
        }
        List<String> encontrados = new ArrayList<>();
        for (Future<String> f : tareas) {
            try {
                String r = f.get(8, TimeUnit.SECONDS);
                if (r != null) encontrados.add(r);
            } catch (Exception ignored) {}
        }
        pool.shutdownNow();
        return encontrados;
    }

    /** Todas las direcciones de las redes /24 privadas del teléfono (sin la suya propia). */
    static List<String> redesLocales() {
        Set<String> ips = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    ips.addAll(vecinas(a.getHostAddress()));
                }
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(ips);
    }

    /** Las 253 vecinas de una IP en su /24 (las redes de casa y de punto de acceso). */
    static List<String> vecinas(String ip) {
        List<String> r = new ArrayList<>();
        int p = ip.lastIndexOf('.');
        if (p < 0) return r;
        String base = ip.substring(0, p + 1);
        for (int i = 1; i < 255; i++) {
            String c = base + i;
            if (!c.equals(ip)) r.add(c);
        }
        return r;
    }

    static boolean puertoAbierto(String ip, int puerto) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, puerto), ESPERA_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** ¿El certificado del equipo lo emitió una CA de GuardianIA? */
    static boolean esGuardianIa(String ip, int puerto) {
        try {
            X509Certificate hoja = ConexionPc.certificadoDelServidor(ip, puerto);
            return hoja.getIssuerX500Principal().getName().contains("GuardianIA");
        } catch (Exception e) {
            return false;
        }
    }
}
