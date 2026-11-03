package co.guardiania.app.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.torproject.jni.TorService;

import co.guardiania.app.GuardianApp;
import co.guardiania.app.R;
import co.guardiania.app.ui.MainActivity;
import co.guardiania.app.vpn.TorInterno;

/**
 * Mantiene vivo el Tor integrado mientras la persona lo quiera encendido (aunque salga de la app),
 * para que Chrome y otras apps lo usen como proxy. Muestra el estado en una notificación fija.
 */
public class ServicioTor extends Service {

    private static final int NOTIF = 7601;
    private TorService tor;
    private boolean enlazado = false;

    private final BroadcastReceiver receptor = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String estado = i.getStringExtra(TorService.EXTRA_STATUS);
            int socks = tor != null ? tor.getSocksPort() : 0, http = tor != null ? tor.getHttpTunnelPort() : 0;
            TorInterno.actualizar(estado, socks, http);
            notificar(estado, http);
        }
    };

    private final ServiceConnection conexion = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            tor = ((TorService.LocalBinder) b).getService();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            tor = null;
            TorInterno.actualizar("OFF", 0, 0);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = construir("Conectando a la red Tor…", "Tres relevos cifrados en capas");
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF, n);
        if (!enlazado) {
            // El estado lo anuncia TorService a NUESTRA app (receptor privado, como el ejemplo oficial).
            ContextCompat.registerReceiver(this, receptor, new IntentFilter(TorService.ACTION_STATUS),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            enlazado = bindService(new Intent(this, TorService.class), conexion, BIND_AUTO_CREATE);
            TorInterno.actualizar("STARTING", 0, 0);
        }
        return START_STICKY;
    }

    private void notificar(String estado, int http) {
        String t = "ON".equals(estado) ? "Tor activo en el teléfono" : "STARTING".equals(estado) ? "Conectando a la red Tor…" : "Tor apagado";
        String d = "ON".equals(estado) ? "Proxy para Chrome: 127.0.0.1:" + http : "Tres relevos cifrados en capas";
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try { if (nm != null) nm.notify(NOTIF, construir(t, d)); } catch (SecurityException ignored) {}
    }

    private Notification construir(String titulo, String texto) {
        PendingIntent abrir = PendingIntent.getActivity(this, 40, new Intent(this, MainActivity.class)
                .putExtra("abrir", "vpn").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, GuardianApp.CANAL_TOR)
                .setSmallIcon(R.drawable.ic_escudo)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(abrir)
                .build();
    }

    @Override
    public void onDestroy() {
        if (enlazado) {
            try { unbindService(conexion); } catch (Exception ignored) {}
            try { unregisterReceiver(receptor); } catch (Exception ignored) {}
            enlazado = false;
        }
        stopService(new Intent(this, TorService.class));
        TorInterno.actualizar("OFF", 0, 0);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
