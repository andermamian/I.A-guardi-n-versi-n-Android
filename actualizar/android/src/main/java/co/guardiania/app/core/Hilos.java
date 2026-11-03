package co.guardiania.app.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hilos de la app en un solo lugar. Regla de oro de Android: la red y el disco NUNCA en el hilo
 * principal (congela la pantalla y el sistema mata la app con un ANR); la interfaz SOLO en él.
 *
 * <p>Un pool acotado (4 hilos) en lugar de un Executor por pantalla: antes cada Activity creaba el
 * suyo y, si la pantalla se cerraba a mitad de una petición, el hilo quedaba huérfano.
 */
public final class Hilos {

    private static final Handler PRINCIPAL = new Handler(Looper.getMainLooper());
    private static final AtomicInteger N = new AtomicInteger();
    private static final ExecutorService RED = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "guardian-red-" + N.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    private Hilos() {}

    /** Ejecuta fuera del hilo principal (red, disco, cálculo). */
    public static void fondo(Runnable r) { RED.execute(r); }

    /** Ejecuta en el hilo de la interfaz. */
    public static void ui(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else PRINCIPAL.post(r);
    }

    public static void uiLuego(Runnable r, long ms) { PRINCIPAL.postDelayed(r, ms); }

    public static void cancelar(Runnable r) { PRINCIPAL.removeCallbacks(r); }

    /** Tarea con resultado: el trabajo en fondo, el resultado o el error en la interfaz. */
    public interface Trabajo<T> { T hacer() throws Exception; }
    public interface Listo<T> { void ok(T valor); }
    public interface Fallo { void error(Exception e); }

    public static <T> void tarea(Trabajo<T> trabajo, Listo<T> listo, Fallo fallo) {
        fondo(() -> {
            try {
                T v = trabajo.hacer();
                ui(() -> listo.ok(v));
            } catch (Exception e) {
                Exception legible = legible(e);
                ui(() -> fallo.error(legible));
            }
        });
    }

    /**
     * Los errores de red crudos («Unable to parse TLS packet header», «Trust anchor…») no le dicen nada
     * a la persona. Se traducen aquí, una sola vez, para todas las pantallas. Los errores del servidor
     * (ApiException) y los ya explicados se dejan tal cual.
     */
    static Exception legible(Exception e) {
        if (e instanceof co.guardiania.app.net.ApiException || e instanceof co.guardiania.app.net.ConexionPc.Problema) return e;
        if (e instanceof java.io.IOException) {
            return new co.guardiania.app.net.ConexionPc.Problema(co.guardiania.app.net.ConexionPc.explicar(e), e);
        }
        return e;
    }
}
