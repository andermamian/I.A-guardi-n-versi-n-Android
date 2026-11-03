package co.guardiania.app.ai;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Lee archivos de prueba desde los recursos de test o desde assets de la app. */
final class Recursos {
    private Recursos() {}

    static String leer(String... rutas) throws Exception {
        for (String r : rutas) {
            File f = new File(r);
            if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            java.io.InputStream is = Recursos.class.getClassLoader().getResourceAsStream(r);
            if (is != null) try (is) { return new String(is.readAllBytes(), StandardCharsets.UTF_8); }
        }
        throw new IllegalStateException("No se encontró ninguno de: " + String.join(", ", rutas));
    }
}
