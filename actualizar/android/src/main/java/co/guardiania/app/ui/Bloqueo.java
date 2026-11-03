package co.guardiania.app.ui;

import android.content.Context;
import android.os.Build;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import co.guardiania.app.data.Vinculo;

/**
 * Bloqueo biométrico opcional al abrir la app (huella o rostro). Una app que ve la ubicación del
 * teléfono, los signos vitales y las contraseñas merece una barrera más si alguien toma el celular
 * desbloqueado. Es opcional: se activa en Ajustes.
 */
public final class Bloqueo {

    public interface Resultado { void ok(); void cancelado(); }

    private Bloqueo() {}

    public static boolean disponible(Context ctx) {
        return BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static void pedir(FragmentActivity a, Resultado r) {
        if (!new Vinculo(a).biometria() || !disponible(a)) { r.ok(); return; }
        BiometricPrompt prompt = new BiometricPrompt(a, ContextCompat.getMainExecutor(a),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult res) { r.ok(); }
                    @Override public void onAuthenticationError(int code, CharSequence msg) { r.cancelado(); }
                });
        BiometricPrompt.PromptInfo.Builder b = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Desbloquear GuardianIA")
                .setSubtitle("Usa tu huella o rostro para abrir la app");
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            b.setNegativeButtonText("Cancelar");
        }
        prompt.authenticate(b.build());
    }
}
