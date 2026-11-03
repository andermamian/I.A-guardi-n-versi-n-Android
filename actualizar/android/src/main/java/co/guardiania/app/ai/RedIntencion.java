package co.guardiania.app.ai;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * La red neuronal de intención del servidor (app/ml/asistente.py), ejecutándose EN EL TELÉFONO.
 *
 * <p>Qué hace: dice qué quiere la persona («crear_archivo», «codigo», «seguridad», «salud»,
 * «dispositivo», «emergencia», «charla», «conocimiento») aunque escriba con errores: «asme una
 * tabla de notaz», «q hago si me robaron el celu». No escribe la respuesta: orienta al asistente.
 *
 * <p>Cómo: exactamente la misma matemática que Python, para que ambos lados den el mismo resultado
 * (la prueba RedIntencionTest lo verifica contra salidas reales del servidor):
 * <ul>
 *   <li>n-gramas de caracteres de 3 y 4 letras, con hashing FNV-1a de 32 bits a 256 dimensiones y
 *       signo por el bit 16 (el truco estándar para repartir colisiones), normalizado L2;</li>
 *   <li>estandarización (x − μ) / σ con los parámetros del entrenamiento;</li>
 *   <li>MLP 256 → 96 → 48 → 8 con ReLU y softmax estable.</li>
 * </ul>
 * Son ~30 000 multiplicaciones: menos de un milisegundo en cualquier teléfono. Sin conexión, sin
 * enviar el texto a ningún lado. Los pesos vienen en assets/intencion.json y se actualizan solos
 * desde el servidor (/v1/ml/intencion/movil) cuando el administrador reentrena la red.
 */
public final class RedIntencion {

    private static final String TAG = "GuardianIA/Red";
    private static final String ARCHIVO = "intencion.json";
    private static volatile RedIntencion cargada;

    public final String version;
    public final String[] intenciones;
    private final int dimensiones;
    private final int[] ngramas;
    private final double[] mu, sigma;
    private final double[][][] W;   // W[capa][entrada][salida]
    private final double[][] b;

    /** Resultado: intención ganadora y su probabilidad. */
    public static final class Prediccion {
        public final String intencion;
        public final double confianza;
        public final double[] probabilidades;
        Prediccion(String i, double c, double[] p) { intencion = i; confianza = c; probabilidades = p; }
    }

    public RedIntencion(String json) throws Exception {
        JSONObject m = new JSONObject(json);
        if (!"guardian-mlp-1".equals(m.optString("formato"))) throw new IllegalArgumentException("Formato de red desconocido");
        version = m.optString("version");
        JSONArray ints = m.getJSONArray("intenciones");
        intenciones = new String[ints.length()];
        for (int i = 0; i < ints.length(); i++) intenciones[i] = ints.getString(i);
        dimensiones = m.getInt("dimensiones");
        JSONArray ng = m.getJSONArray("ngramas");
        ngramas = new int[ng.length()];
        for (int i = 0; i < ng.length(); i++) ngramas[i] = ng.getInt(i);
        mu = vector(m.getJSONArray("mu"));
        sigma = vector(m.getJSONArray("sigma"));
        JSONArray ws = m.getJSONArray("W"), bs = m.getJSONArray("b");
        W = new double[ws.length()][][];
        b = new double[bs.length()][];
        for (int c = 0; c < ws.length(); c++) {
            JSONArray filas = ws.getJSONArray(c);
            W[c] = new double[filas.length()][];
            for (int i = 0; i < filas.length(); i++) W[c][i] = vector(filas.getJSONArray(i));
            b[c] = vector(bs.getJSONArray(c));
        }
        if (mu.length != dimensiones || W[0].length != dimensiones || b[b.length - 1].length != intenciones.length) {
            throw new IllegalArgumentException("Dimensiones de la red inconsistentes");
        }
    }

    private static double[] vector(JSONArray a) throws Exception {
        double[] v = new double[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = a.getDouble(i);
        return v;
    }

    // ─────────────────────────── vectorización (idéntica a vectorizar() en Python)
    static long fnv1a(String s) {
        long h = 0x811C9DC5L;
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            h = ((h ^ (x & 0xFF)) * 0x01000193L) & 0xFFFFFFFFL;
        }
        return h;
    }

    public double[] vectorizar(String texto) {
        double[] v = new double[dimensiones];
        String limpio = " " + Texto.normalizar(texto) + " ";
        if (limpio.length() <= 2) return v;
        for (int n : ngramas) {
            for (int i = 0; i + n <= limpio.length(); i++) {
                long h = fnv1a(limpio.substring(i, i + n));
                v[(int) (h % dimensiones)] += ((h >> 16) % 2 == 1) ? 1.0 : -1.0;
            }
        }
        double norma = 0;
        for (double x : v) norma += x * x;
        norma = Math.sqrt(norma);
        if (norma > 0) for (int i = 0; i < v.length; i++) v[i] /= norma;
        return v;
    }

    // ─────────────────────────── inferencia
    public Prediccion predecir(String texto) {
        double[] x = vectorizar(texto);
        double[] a = new double[x.length];
        for (int i = 0; i < x.length; i++) a[i] = (x[i] - mu[i]) / (sigma[i] == 0 ? 1 : sigma[i]);
        for (int c = 0; c < W.length; c++) {
            double[] z = b[c].clone();
            for (int i = 0; i < a.length; i++) {
                double ai = a[i];
                if (ai == 0) continue;
                double[] fila = W[c][i];
                for (int j = 0; j < z.length; j++) z[j] += ai * fila[j];
            }
            if (c < W.length - 1) for (int j = 0; j < z.length; j++) z[j] = Math.max(0, z[j]);   // ReLU
            a = z;
        }
        double max = Double.NEGATIVE_INFINITY;
        for (double z : a) max = Math.max(max, z);
        double suma = 0;
        for (int j = 0; j < a.length; j++) { a[j] = Math.exp(a[j] - max); suma += a[j]; }
        int mejor = 0;
        for (int j = 0; j < a.length; j++) { a[j] /= suma; if (a[j] > a[mejor]) mejor = j; }
        return new Prediccion(intenciones[mejor], a[mejor], a);
    }

    // ─────────────────────────── carga y actualización en Android
    /** La red del teléfono: la descargada del servidor si existe, si no la que viene en la app. */
    public static RedIntencion de(Context ctx) {
        RedIntencion r = cargada;
        if (r != null) return r;
        synchronized (RedIntencion.class) {
            if (cargada != null) return cargada;
            File propia = new File(ctx.getFilesDir(), ARCHIVO);
            try {
                if (propia.exists()) cargada = new RedIntencion(leer(new java.io.FileInputStream(propia)));
            } catch (Exception e) {
                Log.w(TAG, "Red descargada dañada; se usa la incluida", e);
                //noinspection ResultOfMethodCallIgnored
                propia.delete();
            }
            if (cargada == null) {
                try {
                    cargada = new RedIntencion(leer(ctx.getAssets().open(ARCHIVO)));
                } catch (Exception e) {
                    Log.e(TAG, "No se pudo cargar la red de intención", e);
                }
            }
            return cargada;
        }
    }

    /** Guarda una versión nueva (JSON del servidor) solo si es válida; la anterior sigue si falla. */
    public static boolean instalar(Context ctx, String json) {
        try {
            RedIntencion nueva = new RedIntencion(json);
            File tmp = new File(ctx.getFilesDir(), ARCHIVO + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) { fos.write(json.getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(new File(ctx.getFilesDir(), ARCHIVO))) return false;
            cargada = nueva;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Red recibida inválida; se conserva la actual", e);
            return false;
        }
    }

    static String leer(InputStream is) throws Exception {
        try (InputStream in = is) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }
}
