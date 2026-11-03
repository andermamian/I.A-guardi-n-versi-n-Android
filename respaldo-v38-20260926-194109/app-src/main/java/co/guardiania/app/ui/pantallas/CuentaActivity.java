package co.guardiania.app.ui.pantallas;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import co.guardiania.app.core.Hilos;
import co.guardiania.app.data.Vinculo;
import co.guardiania.app.net.ConexionPc;
import co.guardiania.app.net.DescubrirPc;

import java.util.ArrayList;
import java.util.List;
import co.guardiania.app.net.Sesion;
import co.guardiania.app.ui.kit.Ui;

/**
 * Inicio de sesión, registro y verificación en dos pasos, todo con la sesión de la cuenta (email y
 * contraseña, los mismos del PC). Es la puerta a las funciones que van por cuenta y no por
 * dispositivo: asistente, VPN, emergencias y seguridad. El rastreo antirrobo NO necesita esto:
 * funciona con el token del dispositivo aunque nadie inicie sesión.
 */
public class CuentaActivity extends AppCompatActivity {

    private Sesion sesion;
    private LinearLayout raiz;
    private String retoMfa, emailPendiente;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sesion = Sesion.de(this);
        raiz = Ui.pagina(this);
        setContentView(Ui.desplazable(this, raiz));
        mostrarLogin();
    }

    private void mostrarLogin() {
        raiz.removeAllViews();
        raiz.addView(Ui.titulo(this, "Inicia sesión"));
        raiz.addView(Ui.parrafo(this, "Usa tu correo y contraseña de GuardianIA, los mismos del PC."));

        LinearLayout t = Ui.tarjeta(this);
        EditText email = Ui.campo(this, "Correo", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS | InputType.TYPE_CLASS_TEXT);
        EditText pass = Ui.campoClave(this, "Contraseña");
        t.addView(email);
        t.addView(pass);
        TextView msg = Ui.parrafo(this, "");
        View entrar = Ui.boton(this, "Entrar", Ui.Estilo.PRIMARIO);
        t.addView(entrar);
        t.addView(msg);
        TextView manual = enlaceManual(msg);
        t.addView(manual);
        raiz.addView(t);

        View crear = Ui.boton(this, "Crear una cuenta nueva", Ui.Estilo.FANTASMA);
        crear.setOnClickListener(v -> mostrarRegistro());
        raiz.addView(crear);

        entrar.setOnClickListener(v -> {
            String e = email.getText().toString().trim(), c = pass.getText().toString();
            if (e.isEmpty() || c.isEmpty()) { msg.setText("Escribe correo y contraseña."); return; }
            entrar.setEnabled(false);
            manual.setVisibility(View.GONE);
            conPc(msg, () -> {
                msg.setText("Entrando…");
                Hilos.tarea(() -> Sesion.de(this).login(e, c), r -> {
                    if (r.has("challenge_id")) { retoMfa = r.optString("challenge_id"); emailPendiente = e; mostrarMfa(); }
                    else terminar();
                }, ex -> { entrar.setEnabled(true); msg.setText(ex.getMessage()); });
            }, () -> { entrar.setEnabled(true); manual.setVisibility(View.VISIBLE); });
        });
    }

    /**
     * Resuelve el PC sin preguntar nada: usa el guardado si responde; si no (primera vez o cambió la
     * IP), lo busca en la red local. Solo si no lo encuentra aparece la opción de escribir la IP.
     */
    private void conPc(TextView msg, Runnable listo, Runnable fallo) {
        Vinculo v = new Vinculo(this);
        msg.setText(v.tieneServidor() ? "Conectando con tu PC…" : "Buscando tu PC en la red…");
        Hilos.<List<ConexionPc.Preparada>>tarea(() -> {
            if (ConexionPc.responde(v.servidor(), v.ca())) return new ArrayList<>();   // vacío = el guardado sirve
            Hilos.ui(() -> msg.setText("Buscando tu PC en la red…"));
            String caGuardada = v.ca().isEmpty() ? null : co.guardiania.app.net.ApiClient.huellaCa(v.ca());
            List<ConexionPc.Preparada> validos = new ArrayList<>();
            for (String url : DescubrirPc.buscar()) {
                try {
                    ConexionPc.Preparada pc = ConexionPc.preparar(url, null);
                    // Si este teléfono ya confiaba en un PC, solo se acepta ese mismo PC (misma CA) con otra IP.
                    if (caGuardada != null && v.estaVinculado() && !caGuardada.equals(pc.huella)) continue;
                    validos.add(pc);
                } catch (Exception ignored) {}
            }
            if (validos.isEmpty()) {
                throw new ConexionPc.Problema("No encontré tu PC en esta red. Revisa que GuardianIA esté abierto en el PC "
                        + "(«python -m app.servidor») y que el teléfono esté en el mismo WiFi.", null);
            }
            return validos;
        }, lista -> {
            if (lista.isEmpty()) { listo.run(); return; }
            if (lista.size() == 1) { usarPc(lista.get(0)); listo.run(); return; }
            CharSequence[] nombres = new CharSequence[lista.size()];
            for (int i = 0; i < lista.size(); i++) nombres[i] = lista.get(i).servidor.replace("https://", "");
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Hay varios PC con GuardianIA")
                    .setItems(nombres, (d, w) -> { usarPc(lista.get(w)); listo.run(); })
                    .setNegativeButton("Cancelar", (d, w) -> fallo.run())
                    .setCancelable(false)
                    .show();
        }, e -> { msg.setText(e.getMessage()); fallo.run(); });
    }

    private void usarPc(ConexionPc.Preparada pc) {
        new Vinculo(this).guardarServidor(pc.servidor, pc.caPem);
        Sesion.olvidar();
    }

    /** Respaldo, visible solo si la búsqueda automática falla: escribir la IP a mano. */
    private TextView enlaceManual(TextView msg) {
        TextView enlace = Ui.pequeno(this, "¿No encuentra tu PC? Escribir su dirección");
        enlace.setTextColor(Ui.ACENTO);
        enlace.setPadding(0, Ui.dp(this, 10), 0, 0);
        enlace.setVisibility(View.GONE);
        enlace.setOnClickListener(x -> {
            EditText ip = Ui.campo(this, "IP del PC (ej. 192.168.1.5)", InputType.TYPE_TEXT_VARIATION_URI);
            LinearLayout caja = Ui.columna(this);
            int p = Ui.dp(this, 20);
            caja.setPadding(p, Ui.dp(this, 8), p, 0);
            caja.addView(ip);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Dirección del PC")
                    .setMessage("Es la que muestra la consola del PC («En la red: https://…»).")
                    .setView(caja)
                    .setPositiveButton("Conectar", (d, w) -> {
                        msg.setText("Conectando de forma segura con el PC…");
                        Hilos.tarea(() -> ConexionPc.preparar(ip.getText().toString(), null), pc -> {
                            new androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle("Confirma el certificado del PC")
                                    .setMessage("En la consola del PC aparece «Huella de la CA». Debe empezar así:\n\n"
                                            + pc.huellaCorta() + "\n\n¿Coincide?")
                                    .setPositiveButton("Sí, coincide", (d2, w2) -> {
                                        usarPc(pc);
                                        enlace.setVisibility(View.GONE);
                                        msg.setText("PC listo. Toca «Entrar».");
                                    })
                                    .setNegativeButton("No coincide", (d2, w2) -> msg.setText("Cancelado."))
                                    .show();
                        }, e -> msg.setText(e.getMessage()));
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
        return enlace;
    }

    private void mostrarRegistro() {
        raiz.removeAllViews();
        raiz.addView(Ui.barraSuperior(this, "Crear cuenta"));
        raiz.addView(Ui.parrafo(this, "La cuenta guarda tus contactos de emergencia y tus "
                + "conversaciones (cifradas). La contraseña debe tener 12+ caracteres."));
        LinearLayout t = Ui.tarjeta(this);
        EditText nombre = Ui.campo(this, "Nombre completo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText email = Ui.campo(this, "Correo", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS | InputType.TYPE_CLASS_TEXT);
        EditText pass = Ui.campoClave(this, "Contraseña (12+ caracteres)");
        t.addView(nombre); t.addView(email); t.addView(pass);
        TextView msg = Ui.parrafo(this, "");
        View btn = Ui.boton(this, "Crear cuenta", Ui.Estilo.PRIMARIO);
        t.addView(btn); t.addView(msg);
        TextView manual = enlaceManual(msg);
        t.addView(manual);
        raiz.addView(t);
        Ui.en(raiz, Ui.boton(this, "Ya tengo cuenta", Ui.Estilo.FANTASMA)).setOnClickListener(v -> { raiz.removeAllViews(); mostrarLogin(); });
        btn.setOnClickListener(v -> {
            String n = nombre.getText().toString().trim(), e = email.getText().toString().trim(), c = pass.getText().toString();
            if (n.length() < 3 || e.isEmpty() || c.isEmpty()) { msg.setText("Completa nombre, correo y contraseña."); return; }
            btn.setEnabled(false);
            conPc(msg, () -> {
                msg.setText("Creando la cuenta…");
                Hilos.tarea(() -> Sesion.de(this).registrar(n, e, c, false), r -> terminar(),
                        ex -> { btn.setEnabled(true); msg.setText(ex.getMessage()); });
            }, () -> { btn.setEnabled(true); manual.setVisibility(View.VISIBLE); });
        });
    }

    private void mostrarMfa() {
        raiz.removeAllViews();
        raiz.addView(Ui.titulo(this, "Verificación en dos pasos"));
        raiz.addView(Ui.parrafo(this, "Escribe el código de tu app autenticadora."));
        LinearLayout t = Ui.tarjeta(this);
        EditText codigo = Ui.campo(this, "Código de 6 dígitos", InputType.TYPE_CLASS_NUMBER);
        t.addView(codigo);
        TextView msg = Ui.parrafo(this, "");
        View btn = Ui.boton(this, "Verificar", Ui.Estilo.PRIMARIO);
        t.addView(btn); t.addView(msg);
        raiz.addView(t);
        btn.setOnClickListener(v -> {
            btn.setEnabled(false); msg.setText("Verificando…");
            Hilos.tarea(() -> Sesion.de(this).loginMfa(retoMfa, codigo.getText().toString().trim(), emailPendiente),
                    r -> terminar(), ex -> { btn.setEnabled(true); msg.setText(ex.getMessage()); });
        });
    }

    private void terminar() {
        // Al iniciar sesión, se intenta traer la red de intención más reciente (silencioso).
        Hilos.fondo(() -> co.guardiania.app.ai.Asistente.actualizarRed(getApplicationContext()));
        setResult(RESULT_OK);
        finish();
    }
}
