package co.guardiania.app.ui.pantallas;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.net.Sesion;
import co.guardiania.app.ui.kit.Ui;

/**
 * Contactos de emergencia: a quién avisa el SOS, en orden de prioridad. El servidor los normaliza
 * (números colombianos reciben +57) y los prueba con un SMS claramente marcado como PRUEBA.
 */
public class ContactosActivity extends AppCompatActivity {

    private LinearLayout lista;
    private android.widget.TextView estadoSms;
    private View botonPermiso;
    private LinearLayout filaSim;
    private final androidx.activity.result.ActivityResultLauncher<String[]> permisoSms = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(), ok -> pintarEstadoSms());

    /** Deja claro si el SOS puede salir por SMS desde la SIM (sin internet). */
    private void pintarEstadoSms() {
        boolean ok = co.guardiania.app.seguridad.SmsSos.tienePermiso(this);
        estadoSms.setText(ok ? "SOS por SMS activo: tus contactos reciben el aviso aunque no tengan la app y aunque "
                + "no tengas internet (solo necesitas señal celular)."
                : "Falta permitir los SMS: sin eso, el SOS solo sale por internet.");
        estadoSms.setTextColor(ok ? Ui.OK : Ui.AVISO);
        botonPermiso.setVisibility(ok ? View.GONE : View.VISIBLE);
        pintarSim();
    }

    /** Muestra la SIM elegida («Automática», «SIM 2 · Tigo») en la fila. */
    private void pintarSim() {
        if (filaSim == null) return;
        int elegida = co.guardiania.app.seguridad.AgendaSos.simSos(this);
        String texto = "Automática (la SIM predeterminada para SMS)";
        for (co.guardiania.app.seguridad.SmsSos.SimInfo si : co.guardiania.app.seguridad.SmsSos.simsActivas(this)) {
            if (si.id == elegida) texto = si.nombre;
        }
        if (elegida >= 0 && texto.startsWith("Automática")) texto = "SIM elegida (no detectada ahora)";
        if (elegida >= 0) texto += co.guardiania.app.seguridad.AgendaSos.respaldoOtraSim(this)
                ? " · si falla, prueba la otra" : " · solo esta SIM";
        // itemLista: el segundo TextView es el subtítulo.
        java.util.List<android.widget.TextView> tv = new java.util.ArrayList<>();
        juntarTextos(filaSim, tv);
        if (tv.size() >= 2) tv.get(1).setText(texto);
    }

    private static void juntarTextos(android.view.ViewGroup g, java.util.List<android.widget.TextView> r) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof android.widget.TextView) r.add((android.widget.TextView) v);
            else if (v instanceof android.view.ViewGroup) juntarTextos((android.view.ViewGroup) v, r);
        }
    }

    /** Elegir con qué SIM sale el SOS. Necesita el permiso «Teléfono» para ver las SIM. */
    private void elegirSim() {
        java.util.List<co.guardiania.app.seguridad.SmsSos.SimInfo> sims = co.guardiania.app.seguridad.SmsSos.simsActivas(this);
        if (sims.isEmpty()) {
            if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Ui.aviso(this, "Permite «Teléfono» para ver tus SIM.");
                permisoSms.launch(new String[]{android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_PHONE_STATE});
            } else {
                Ui.aviso(this, "No se detectó ninguna SIM activa.");
            }
            return;
        }
        CharSequence[] opciones = new CharSequence[sims.size() + 1];
        opciones[0] = "Automática (la SIM predeterminada para SMS)";
        int actual = 0, elegida = co.guardiania.app.seguridad.AgendaSos.simSos(this);
        for (int i = 0; i < sims.size(); i++) {
            opciones[i + 1] = sims.get(i).nombre;
            if (sims.get(i).id == elegida) actual = i + 1;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("¿Con qué SIM se envía el SOS?")
                .setSingleChoiceItems(opciones, actual, (d, w) -> {
                    d.dismiss();
                    if (w == 0) {
                        co.guardiania.app.seguridad.AgendaSos.setSimSos(this, -1);
                        pintarSim();
                        return;
                    }
                    co.guardiania.app.seguridad.AgendaSos.setSimSos(this, sims.get(w - 1).id);
                    if (sims.size() < 2) { pintarSim(); return; }
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Si " + sims.get(w - 1).nombre + " falla…")
                            .setMessage("¿Intentar con la otra SIM? (Recomendado: en una emergencia, lo importante es que el aviso llegue.)")
                            .setPositiveButton("Sí, probar la otra", (d2, w2) -> { co.guardiania.app.seguridad.AgendaSos.setRespaldoOtraSim(this, true); pintarSim(); })
                            .setNegativeButton("No, solo esta SIM", (d2, w2) -> { co.guardiania.app.seguridad.AgendaSos.setRespaldoOtraSim(this, false); pintarSim(); })
                            .show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    protected void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        LinearLayout raiz = Ui.columna(this);
        raiz.setBackgroundColor(Ui.FONDO);
        raiz.addView(Ui.barraSuperior(this, "Contactos de emergencia"));
        LinearLayout cont = Ui.pagina(this);
        cont.addView(Ui.parrafo(this, "A quién avisamos con tu ubicación cuando activas el SOS. Registra al menos dos."));
        Ui.en(cont, Ui.boton(this, "Agregar un contacto", Ui.Estilo.PRIMARIO)).setOnClickListener(v -> dialogoNuevo());
        Ui.en(cont, Ui.boton(this, "Probar mis contactos (SMS)", Ui.Estilo.SECUNDARIO)).setOnClickListener(v -> probar());

        // SOS por SMS desde la SIM: permiso y SIM elegida.
        cont.addView(Ui.seccion(this, "SOS por SMS"));
        estadoSms = Ui.pequeno(this, "");
        cont.addView(estadoSms);
        botonPermiso = Ui.boton(this, "Permitir que el SOS envíe SMS", Ui.Estilo.PRIMARIO);
        botonPermiso.setOnClickListener(v -> permisoSms.launch(new String[]{android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.READ_PHONE_STATE}));
        cont.addView(botonPermiso);
        filaSim = Ui.itemLista(this, co.guardiania.app.R.drawable.ic_ajustes, "SIM para el SOS", "Automática", v -> elegirSim());
        cont.addView(filaSim);
        pintarEstadoSms();

        cont.addView(Ui.seccion(this, "Mis contactos"));
        lista = Ui.columna(this);
        cont.addView(lista);
        raiz.addView(Ui.desplazable(this, cont));
        setContentView(raiz);
        cargar();
    }

    private void cargar() {
        Hilos.tarea(() -> Sesion.de(this).obj("GET", "/emergency/contacts", null), r -> {
            lista.removeAllViews();
            JSONArray cs = r.optJSONArray("contactos");
            co.guardiania.app.seguridad.AgendaSos.guardar(this, cs);   // copia local: el SOS funciona sin internet
            if (cs == null || cs.length() == 0) { lista.addView(Ui.parrafo(this, "Aún no tienes contactos.")); return; }
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                LinearLayout item = Ui.itemLista(this, co.guardiania.app.R.drawable.ic_contactos,
                        c.optString("name"), c.optString("phone_e164") + " · prioridad " + c.optInt("priority"), null);
                lista.addView(item);
            }
        }, e -> Ui.aviso(this, e.getMessage()));
    }

    private void dialogoNuevo() {
        LinearLayout form = Ui.columna(this);
        int p = Ui.dp(this, 8);
        form.setPadding(p * 3, p, p * 3, 0);
        EditText nombre = Ui.campo(this, "Nombre", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText tel = Ui.campo(this, "Celular (ej. 300 123 4567)", InputType.TYPE_CLASS_PHONE);
        form.addView(nombre); form.addView(tel);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Nuevo contacto")
                .setView(form)
                .setPositiveButton("Guardar", (d, w) -> {
                    Hilos.tarea(() -> {
                        JSONObject cuerpo = new JSONObject();
                        cuerpo.put("name", nombre.getText().toString().trim());
                        cuerpo.put("phone", tel.getText().toString().trim());
                        return Sesion.de(this).obj("POST", "/emergency/contacts", cuerpo);
                    }, r -> cargar(), e -> Ui.aviso(this, e.getMessage()));
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void probar() {
        Hilos.tarea(() -> Sesion.de(this).obj("POST", "/emergency/contacts/test", new JSONObject()),
                r -> Ui.aviso(this, r.optString("mensaje", "Mensaje de prueba enviado")),
                e -> Ui.aviso(this, e.getMessage()));
    }
}
