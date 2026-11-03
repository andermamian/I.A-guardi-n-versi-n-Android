package co.guardiania.app.data;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Cifra las credenciales (token del dispositivo y refresh token) con una clave AES-256 que vive en
 * el <b>Android Keystore</b>: la clave nunca sale del hardware seguro del teléfono y no se puede
 * copiar, ni siquiera con acceso root al almacenamiento de la app.
 *
 * <p>Por qué no basta SharedPreferences privado: protege contra otras apps, pero una copia del
 * almacenamiento (root, copia de seguridad, forense de un teléfono robado) deja los tokens en claro.
 * Con esto, esa copia solo contiene texto cifrado inútil fuera de este equipo.
 *
 * <p>Formato: "k1:" + base64(iv(12) || AES-GCM(texto)). Un valor sin prefijo es de la versión 1.x
 * (en claro): se lee igual y se vuelve a guardar cifrado (migración transparente).
 */
public final class CajaFuerte {

    private static final String TAG = "GuardianIA/Caja";
    private static final String ALIAS = "guardiania_credenciales_v1";
    private static final String PREFIJO = "k1:";

    private CajaFuerte() {}

    public static boolean estaCifrado(String v) { return v != null && v.startsWith(PREFIJO); }

    public static String cifrar(String texto) {
        if (texto == null || texto.isEmpty()) return texto;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, clave());
            byte[] ct = c.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            byte[] iv = c.getIV();
            byte[] todo = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, todo, 0, iv.length);
            System.arraycopy(ct, 0, todo, iv.length, ct.length);
            return PREFIJO + Base64.encodeToString(todo, Base64.NO_WRAP);
        } catch (Exception e) {
            // Keystore roto (pasa en algunos emuladores): se guarda como antes antes que perder el vínculo.
            Log.e(TAG, "No se pudo cifrar con el Keystore", e);
            return texto;
        }
    }

    public static String descifrar(String guardado) {
        if (!estaCifrado(guardado)) return guardado;
        try {
            byte[] todo = Base64.decode(guardado.substring(PREFIJO.length()), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, clave(), new GCMParameterSpec(128, todo, 0, 12));
            byte[] pt = c.doFinal(todo, 12, todo.length - 12);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Clave borrada (p. ej. se quitó el bloqueo de pantalla en algunos equipos): hay que revincular.
            Log.e(TAG, "No se pudo descifrar la credencial", e);
            return "";
        }
    }

    private static SecretKey clave() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Sin exigir desbloqueo del usuario: el rastreo debe funcionar con la pantalla bloqueada.
                .setUserAuthenticationRequired(false)
                .build());
        return kg.generateKey();
    }
}
