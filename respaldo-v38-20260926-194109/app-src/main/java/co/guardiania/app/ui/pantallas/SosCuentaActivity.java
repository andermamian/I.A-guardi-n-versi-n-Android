package co.guardiania.app.ui.pantallas;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.seguridad.AvisoSos;
import co.guardiania.app.seguridad.Emergencia;
import co.guardiania.app.seguridad.SmsSos;
import co.guardiania.app.ui.kit.Ui;

/**
 * Cuenta regresiva del SOS del panel rápido: 5 segundos con un botón «Cancelar» grande (por si se tocó
 * sin querer). Se muestra sobre la pantalla de bloqueo. Después envía el SOS por la SIM y muestra el
 * estado real de cada contacto, con «Estoy bien» y «Llamar al 123».
 */
public class SosCuentaActivity extends AppCompatActivity {

    private static final int SEGUNDOS = 5;
    private TextView titulo, cuenta, estado;
    private View cancelar;
    private LinearLayout acciones;
    private int quedan = SEGUNDOS;
    private boolean cancelado = false, enviado = false;

    private final ActivityResultLauncher<String[]> permisos = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> enviar());   // si lo niega, se intenta por internet

    private final Runnable tic = new Runnable() {
        @Override public void run() {
            if (cancelado) return;
            quedan--;
            if (quedan <= 0) { prepararEnvio(); return; }
            cuenta.setText(String.valueOf(quedan));
            vibrar(120);
            Hilos.uiLuego(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout raiz = Ui.pagina(this);
        raiz.setGravity(Gravity.CENTER_HORIZONTAL);
        raiz.setBackgroundColor(Ui.FONDO);
        titulo = Ui.titulo(this, "Enviando SOS en…");
        titulo.setGravity(Gravity.CENTER);
        raiz.addView(titulo);
        cuenta = Ui.cifra(this, String.valueOf(SEGUNDOS), Ui.PELIGRO);
        cuenta.setTextSize(96);
        cuenta.setGravity(Gravity.CENTER);
        raiz.addView(cuenta);
        estado = Ui.parrafo(this, "Tus contactos de emergencia recibirán un SMS con tu ubicación.");
        estado.setGravity(Gravity.CENTER);
        raiz.addView(estado);
        cancelar = Ui.boton(this, "Cancelar", Ui.Estilo.SECUNDARIO);
        cancelar.setMinimumHeight(Ui.dp(this, 72));
        cancelar.setOnClickListener(v -> { cancelado = true; finish(); });
        raiz.addView(cancelar);
        acciones = Ui.columna(this);
        raiz.addView(acciones);
        setContentView(Ui.desplazable(this, raiz));
        vibrar(300);
        Hilos.uiLuego(tic, 1000);
    }

    private void prepararEnvio() {
        if (enviado) return;
        enviado = true;
        cancelar.setVisibility(View.GONE);
        cuenta.setVisibility(View.GONE);
        titulo.setText("SOS en curso");
        estado.setText("Obteniendo tu ubicación y enviando…");
        if (!SmsSos.tienePermiso(this)) {
            permisos.launch(new String[]{Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE});
        } else {
            enviar();
        }
    }

    private void enviar() {
        Emergencia.sos(this, "Botón SOS del panel rápido", envio -> {
            AvisoSos.mostrar(this);                                   // queda el aviso con «Estoy bien»
            estado.setText(SmsSos.resumen(envio));
            SmsSos.observar(e -> estado.setText(SmsSos.resumen(e)));
            mostrarAcciones(envio.optString("incidente_id"));
        }, e -> {
            titulo.setText("El aviso no salió");
            estado.setText(e.getMessage());
            mostrarAcciones(null);
        });
    }

    private void mostrarAcciones(@Nullable String incidente) {
        acciones.removeAllViews();
        View llamar = Ui.boton(this, "Llamar al 123", Ui.Estilo.PELIGRO);
        llamar.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:123"))); } catch (Exception ignored) {}
        });
        acciones.addView(llamar);
        View bien = Ui.boton(this, "Estoy bien", Ui.Estilo.SECUNDARIO);
        bien.setOnClickListener(v -> Emergencia.estoyBien(this, incidente, r -> {
            Ui.aviso(this, r.optInt("sms") > 0 ? "Avisando a tus contactos que estás bien…" : "Alerta cancelada.");
            finish();
        }, err -> Ui.aviso(this, err.getMessage())));
        acciones.addView(bien);
        View cerrar = Ui.boton(this, "Cerrar", Ui.Estilo.FANTASMA);
        cerrar.setOnClickListener(v -> finish());
        acciones.addView(cerrar);
    }

    private void vibrar(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        else v.vibrate(ms);
    }

    @Override
    protected void onDestroy() {
        cancelado = true;                 // si se cierra durante la cuenta regresiva, NO se envía
        Hilos.cancelar(tic);
        SmsSos.observar(null);
        super.onDestroy();
    }
}
