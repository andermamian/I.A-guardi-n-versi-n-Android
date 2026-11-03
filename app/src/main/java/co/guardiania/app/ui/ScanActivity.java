package co.guardiania.app.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Size;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Escanea el QR que muestra el PC en Dispositivos. Reconoce dos formatos:
 *
 * <ul>
 *   <li><b>guardiania://vincular?servidor=...&codigo=...</b> — el QR "para la app": trae la dirección
 *       del PC y el código juntos, así el usuario no teclea nada.</li>
 *   <li><b>https://IP:8443/vincular?codigo=...</b> — el QR "web": trae la dirección (hasta /vincular)
 *       y el código. También sirve, se extrae el origen como servidor.</li>
 * </ul>
 *
 * Devuelve servidor y código a MainActivity por el Intent de resultado. La cámara solo se usa para
 * leer el QR; no se guarda ninguna imagen.
 */
@ExperimentalGetImage
public class ScanActivity extends AppCompatActivity {

    public static final String EXTRA_SERVIDOR = "servidor";
    public static final String EXTRA_CODIGO = "codigo";
    /** Huella SHA-256 de la CA (hex) que trae el QR de la app; null en el QR web. */
    public static final String EXTRA_HUELLA_CA = "huella_ca";
    private static final int REQ_CAMARA = 20;

    private PreviewView vista;
    private ExecutorService analizador;
    private BarcodeScanner scanner;
    private boolean entregado = false;   // evita entregar el mismo QR muchas veces

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vista = new PreviewView(this);
        setContentView(vista);
        setTitle("Escanea el QR del PC");
        analizador = Executors.newSingleThreadExecutor();
        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMARA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_CAMARA && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara();
        } else {
            Toast.makeText(this, "Sin cámara no se puede escanear. Escribe el código a mano.",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void iniciarCamara() {
        ListenableFuture<ProcessCameraProvider> fut = ProcessCameraProvider.getInstance(this);
        fut.addListener(() -> {
            try {
                ProcessCameraProvider provider = fut.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(vista.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analizador, this::analizar);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo abrir la cámara: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analizar(ImageProxy proxy) {
        if (entregado || proxy.getImage() == null) {
            proxy.close();
            return;
        }
        InputImage img = InputImage.fromMediaImage(proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        scanner.process(img)
                .addOnSuccessListener(codigos -> {
                    for (Barcode b : codigos) {
                        String texto = b.getRawValue();
                        if (texto != null && procesar(texto)) break;
                    }
                })
                .addOnCompleteListener(t -> proxy.close());
    }

    /** Extrae servidor + código del QR. Devuelve true si era un QR válido de GuardianIA. */
    private boolean procesar(String texto) {
        try {
            Uri u = Uri.parse(texto);
            String servidor, codigo = u.getQueryParameter("codigo");
            if ("guardiania".equals(u.getScheme())) {
                servidor = u.getQueryParameter("servidor");            // QR de la app
            } else if (texto.startsWith("http")) {
                // QR web: https://IP:8443/vincular?codigo=... → el servidor es el origen.
                servidor = u.getScheme() + "://" + u.getAuthority();
            } else {
                return false;
            }
            if (servidor == null || codigo == null || codigo.length() != 6) return false;

            entregado = true;
            android.content.Intent res = new android.content.Intent();
            res.putExtra(EXTRA_SERVIDOR, servidor);
            res.putExtra(EXTRA_CODIGO, codigo);
            String ca = u.getQueryParameter("ca");
            if (ca != null && ca.matches("[0-9a-fA-F]{64}")) res.putExtra(EXTRA_HUELLA_CA, ca.toLowerCase());
            setResult(RESULT_OK, res);
            runOnUiThread(this::finish);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        if (analizador != null) analizador.shutdown();
        if (scanner != null) scanner.close();
        super.onDestroy();
    }
}
