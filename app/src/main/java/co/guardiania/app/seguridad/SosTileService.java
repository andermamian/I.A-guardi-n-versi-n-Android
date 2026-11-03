package co.guardiania.app.seguridad;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import co.guardiania.app.ui.pantallas.SosCuentaActivity;

/**
 * Botón «SOS» en el panel rápido de Android (el menú que se despliega desde arriba, junto al WiFi y la
 * linterna). Funciona sin desbloquear el teléfono y sin abrir la app: abre una cuenta regresiva de 5 s
 * con «Cancelar» y después envía el SOS por la SIM.
 */
public class SosTileService extends TileService {

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean listo = AgendaSos.cuantos(this) > 0;
        t.setState(listo ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("SOS");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(listo ? "Avisar a mis contactos" : "Falta configurar");
        t.updateTile();
    }

    @Override
    public void onClick() {
        Intent i = new Intent(this, SosCuentaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 exige PendingIntent aquí (con Intent lanza una excepción).
            startActivityAndCollapse(PendingIntent.getActivity(this, 20, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
