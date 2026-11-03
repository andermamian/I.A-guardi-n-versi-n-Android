package co.guardiania.app.service;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Collections;

import co.guardiania.app.data.Vinculo;
import co.guardiania.app.net.ApiClient;

/**
 * Toma UNA foto con la cámara frontal, sin vista previa en pantalla, y la sube cifrada al servidor.
 * Solo se usa cuando el equipo está reportado como perdido/robado: sirve para ver quién lo tiene.
 *
 * <p>Límites honestos que impone Android, y que conviene que el dueño conozca:
 * <ul>
 *   <li>Requiere el permiso de <b>cámara</b>, concedido antes por el usuario.</li>
 *   <li>En Android 12+ puede encenderse el <b>indicador verde</b> de cámara en uso: la captura no es
 *       totalmente invisible. Es una protección del sistema que la app no puede desactivar.</li>
 *   <li>Algunas capas (incluida MIUI) restringen abrir la cámara con la pantalla apagada.</li>
 * </ul>
 * Por eso la foto es "mejor esfuerzo": cuando se logra, se sube; cuando no, el comando lo reporta.
 */
public class IntruderCamera {

    private static final String TAG = "GuardianIA/Cam";

    public interface Callback { void onResultado(boolean ok, String motivo); }

    private final Context ctx;
    private final Vinculo vinculo;

    public IntruderCamera(Context ctx, Vinculo vinculo) {
        this.ctx = ctx.getApplicationContext();
        this.vinculo = vinculo;
    }

    public void capturar(double lat, double lon, Callback cb) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) { cb.onResultado(false, "sin cámara"); return; }
        try {
            String frontal = camaraFrontal(cm);
            if (frontal == null) { cb.onResultado(false, "sin cámara frontal"); return; }

            HandlerThread hilo = new HandlerThread("cam");
            hilo.start();
            Handler h = new Handler(hilo.getLooper());

            ImageReader reader = ImageReader.newInstance(1280, 960, ImageFormat.JPEG, 1);
            reader.setOnImageAvailableListener(r -> {
                try (android.media.Image img = r.acquireLatestImage()) {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] jpeg = new byte[buf.remaining()];
                    buf.get(jpeg);
                    subir(jpeg, lat, lon, cb);
                } catch (Exception e) {
                    cb.onResultado(false, e.getMessage());
                } finally {
                    hilo.quitSafely();
                }
            }, h);

            abrir(cm, frontal, reader, h, cb);
        } catch (SecurityException e) {
            cb.onResultado(false, "falta permiso de cámara");
        } catch (Exception e) {
            cb.onResultado(false, e.getMessage());
        }
    }

    private String camaraFrontal(CameraManager cm) throws CameraAccessException {
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == CameraCharacteristics.LENS_FACING_FRONT) return id;
        }
        return null;
    }

    @SuppressWarnings("MissingPermission")   // el permiso se pidió antes; si falta, salta SecurityException
    private void abrir(CameraManager cm, String id, ImageReader reader, Handler h, Callback cb)
            throws CameraAccessException {
        cm.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camara) {
                try {
                    CaptureRequest.Builder req = camara.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    req.addTarget(reader.getSurface());
                    req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    camara.createCaptureSession(Collections.singletonList(reader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(@NonNull CameraCaptureSession sesion) {
                                    try {
                                        sesion.capture(req.build(), null, h);
                                    } catch (Exception e) {
                                        cb.onResultado(false, e.getMessage());
                                        camara.close();
                                    }
                                }
                                @Override
                                public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                                    cb.onResultado(false, "no se configuró la cámara");
                                    camara.close();
                                }
                            }, h);
                } catch (Exception e) {
                    cb.onResultado(false, e.getMessage());
                    camara.close();
                }
            }
            @Override public void onDisconnected(@NonNull CameraDevice c) { c.close(); }
            @Override public void onError(@NonNull CameraDevice c, int e) {
                cb.onResultado(false, "error de cámara " + e); c.close();
            }
        }, h);
    }

    private void subir(byte[] jpeg, double lat, double lon, Callback cb) {
        try {
            ApiClient api = new ApiClient(vinculo.servidor(), vinculo.ca());
            JSONObject cuerpo = new JSONObject();
            cuerpo.put("device_token", vinculo.token());
            cuerpo.put("imagen_b64", Base64.encodeToString(jpeg, Base64.NO_WRAP));
            cuerpo.put("motivo", "intento_desbloqueo");
            if (!Double.isNaN(lat)) cuerpo.put("lat", lat);
            if (!Double.isNaN(lon)) cuerpo.put("lon", lon);
            ApiClient.Resp r = api.post("/devices/self/foto", cuerpo);
            cb.onResultado(r.ok(), r.ok() ? null : ("subida falló " + r.code));
        } catch (Exception e) {
            cb.onResultado(false, e.getMessage());
        }
    }
}
