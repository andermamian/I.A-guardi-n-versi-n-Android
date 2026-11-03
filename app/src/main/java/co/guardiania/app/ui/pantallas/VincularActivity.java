package co.guardiania.app.ui.pantallas;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.data.Vinculo;
import co.guardiania.app.net.ApiClient;
import co.guardiania.app.net.ConexionPc;
import co.guardiania.app.service.TrackingService;
import co.guardiania.app.ui.ScanActivity;
import co.guardiania.app.ui.kit.Ui;

/**
 * Vincula ESTE teléfono a una cuenta con el código de 6 dígitos (o el QR) que muestra el PC/web en
 * Dispositivos. Guarda el token del dispositivo (cifrado) y la CA para el HTTPS con pinning.
 *
 * <p>Novedad de seguridad v2: si el QR de la app trae la huella de la CA, se descarga el certificado
 * y se COMPARA con esa huella antes de fijarlo. Así, aunque alguien en el mismo WiFi intente colar su
 * propia CA durante la vinculación, la app lo detecta (el QR se leyó de la pantalla del PC, fuera de
 * su alcance).
 */
public class VincularActivity extends AppCompatActivity {

    private Vinculo vinculo;
    private EditText inServidor, inCodigo, inNombre;
    private TextView mensaje;
    private LinearLayout raiz;

    private final androidx.activity.result.ActivityResultLauncher<Intent> escaner =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    res -> {
                        if (res.getResultCode() == RESULT_OK && res.getData() != null) {
                            inServidor.setText(res.getData().getStringExtra(ScanActivity.EXTRA_SERVIDOR));
                            inCodigo.setText(res.getData().getStringExtra(ScanActivity.EXTRA_CODIGO));
                            vincular(res.getData().getStringExtra(ScanActivity.EXTRA_HUELLA_CA));
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vinculo = new Vinculo(this);
        construir();
        precargarDesdeIntent(getIntent());
    }

    private void construir() {
        raiz = Ui.pagina(this);
        raiz.addView(Ui.titulo(this, "Vincular este teléfono"));
        raiz.addView(Ui.parrafo(this, "Quedará conectado a tu cuenta para poder ubicarlo si se pierde o te lo roban. "
                + "En el PC o en la web, entra en Dispositivos y genera un código o un QR."));

        LinearLayout tarjeta = Ui.tarjeta(this);
        Ui.en(tarjeta, Ui.boton(this, "Escanear el QR del PC", Ui.Estilo.PRIMARIO))
                .setOnClickListener(v -> escaner.launch(new Intent(this, ScanActivity.class)));
        Ui.en(tarjeta, Ui.pequeno(this, "…o escríbelo a mano:")).setPadding(0, Ui.dp(this, 16), 0, 0);

        tarjeta.addView(etiqueta("Dirección del PC"));
        inServidor = Ui.campo(this, "192.168.1.5  (la IP que muestra el PC)", android.text.InputType.TYPE_TEXT_VARIATION_URI);
        if (!vinculo.servidor().isEmpty()) inServidor.setText(vinculo.servidor());
        tarjeta.addView(inServidor);

        tarjeta.addView(etiqueta("Código de 6 dígitos"));
        inCodigo = Ui.campo(this, "000000", android.text.InputType.TYPE_CLASS_NUMBER);
        tarjeta.addView(inCodigo);

        tarjeta.addView(etiqueta("Nombre para este teléfono"));
        inNombre = Ui.campo(this, "Mi teléfono", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        tarjeta.addView(inNombre);

        Ui.en(tarjeta, Ui.boton(this, "Vincular con el código", Ui.Estilo.SECUNDARIO))
                .setOnClickListener(v -> vincular(null));
        raiz.addView(tarjeta);

        mensaje = Ui.parrafo(this, "");
        Ui.margen(mensaje, 0, 16, 0, 0);
        raiz.addView(mensaje);

        setContentView(Ui.desplazable(this, raiz));
    }

    private TextView etiqueta(String s) {
        TextView t = Ui.pequeno(this, s);
        t.setTextColor(Ui.TEXTO);
        t.setPadding(0, Ui.dp(this, 14), 0, 0);
        return t;
    }

    private void precargarDesdeIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String codigo = intent.getData().getQueryParameter("codigo");
        if (codigo != null) inCodigo.setText(codigo);
        String servidor = intent.getData().getQueryParameter("servidor");
        if (servidor != null) inServidor.setText(servidor);
        String ca = intent.getData().getQueryParameter("ca");
        if (codigo != null && servidor != null) vincular(ca != null && ca.matches("[0-9a-fA-F]{64}") ? ca.toLowerCase() : null);
    }

    private void vincular(@Nullable String huellaEsperada) {
        String entrada = inServidor.getText().toString().trim();
        String codigo = inCodigo.getText().toString().trim().replaceAll("\\s", "");
        String nombre = inNombre.getText().toString().trim();
        if (nombre.isEmpty()) nombre = Build.MODEL;

        String servidor;
        try {
            servidor = ConexionPc.normalizar(entrada);
        } catch (IllegalArgumentException e) {
            mensaje.setText(e.getMessage());
            return;
        }
        inServidor.setText(servidor);   // se ve la dirección corregida (https y puerto 8443)
        if (codigo.length() != 6 || !TextUtils.isDigitsOnly(codigo)) {
            mensaje.setText("El código son 6 dígitos. Genéralo en el PC, en Dispositivos.");
            return;
        }

        mensaje.setText("Conectando de forma segura con el PC…");
        final String fCodigo = codigo, fNombre = nombre;
        Hilos.tarea(() -> ConexionPc.preparar(servidor, huellaEsperada), pc -> {
            if (pc.verificadaPorQr) {
                canjear(pc, fCodigo, fNombre);
                return;
            }
            // Escrita a mano: la persona compara la huella con la que muestra la consola del PC.
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Confirma el certificado del PC")
                    .setMessage("En la ventana negra donde corre GuardianIA aparece «Huella de la CA». Debe empezar así:\n\n"
                            + pc.huellaCorta() + "\n\n¿Coincide? Si no coincide, no continúes: alguien podría estar "
                            + "interceptando la red.")
                    .setPositiveButton("Sí, coincide", (d, w) -> canjear(pc, fCodigo, fNombre))
                    .setNegativeButton("No coincide", (d, w) -> mensaje.setText("Vinculación cancelada. Usa el QR del PC, que "
                            + "comprueba la huella solo."))
                    .show();
        }, e -> mensaje.setText(e.getMessage()));
    }

    private void canjear(ConexionPc.Preparada pc, String codigo, String nombre) {
        mensaje.setText("Vinculando…");
        Hilos.tarea(() -> {
            ApiClient api = new ApiClient(pc.servidor, pc.caPem);
            JSONObject cuerpo = new JSONObject();
            cuerpo.put("codigo", codigo);
            cuerpo.put("nombre", nombre);
            cuerpo.put("platform", "android");
            cuerpo.put("model", Build.MODEL);
            cuerpo.put("os_version", Build.VERSION.RELEASE);
            ApiClient.Resp r = api.post("/devices/vinculacion/canjear", cuerpo);
            if (!r.ok()) throw new co.guardiania.app.net.ApiException(r.code, r.json().optString("code"),
                    mensajeError(r), r.json());
            JSONObject j = r.json();
            vinculo.guardar(pc.servidor, j.getString("device_token"), pc.caPem, j.optString("nombre", nombre));
            vinculo.guardarDeviceId(j.optString("device_id", ""));
            return true;
        }, ok -> {
            mensaje.setText("¡Vinculado! Ahora concede los permisos para proteger el teléfono.");
            TrackingService.iniciar(this, null);
            setResult(RESULT_OK);
            finish();
        }, e -> mensaje.setText(e.getMessage()));
    }

    private String mensajeError(ApiClient.Resp r) {
        String detalle = r.json().optString("title", r.json().optString("detail", ""));
        if (!detalle.isEmpty()) return detalle;
        if (r.code == 400) return "El código no es válido, ya se usó o venció. Genera uno nuevo.";
        return "El servidor respondió " + r.code + ".";
    }
}
