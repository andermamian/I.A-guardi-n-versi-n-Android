package co.guardiania.app.service;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Pantalla a pantalla completa que aparece sobre todo lo demás, incluso con el teléfono bloqueado.
 * Sirve para tres comandos: mostrar un mensaje del dueño, sonar la alarma "¡Aquí estoy!" y cubrir la
 * pantalla al bloquear. Se enciende la pantalla y se muestra encima del bloqueo, para que quien tenga
 * el teléfono vea el aviso de inmediato.
 */
public class AlertActivity extends Activity {

    private static final String EXTRA_TITULO = "titulo";
    private static final String EXTRA_TEXTO = "texto";
    private static final String EXTRA_CERRABLE = "cerrable";
    private static final String EXTRA_ALARMA = "alarma";
    private static final String EXTRA_SEG = "segundos";
    private static final String EXTRA_VOL_PREVIO = "vol_previo";

    private Handler handler;

    /** Mensaje o bloqueo del dueño: aviso sonoro propio de GuardianIA + pantalla sobre el bloqueo. */
    public static void mostrar(Context ctx, String titulo, String texto, boolean bloqueo) {
        Sirena.mostrarPantalla(ctx.getApplicationContext(), titulo, texto, false, !bloqueo);
    }

    /** Alarma «¡Aquí estoy!»: la sirena la reproduce el servicio; aquí solo se muestra la pantalla. */
    public static void sonar(Context ctx, int segundos, int volPrevio) {
        Sirena.sonar(ctx, segundos);
    }

    static Intent intent(Context ctx, String titulo, String texto, boolean cerrable, boolean alarma) {
        return base(ctx, titulo, texto).putExtra(EXTRA_CERRABLE, cerrable).putExtra(EXTRA_ALARMA, alarma);
    }

    private static Intent base(Context ctx, String titulo, String texto) {
        return new Intent(ctx, AlertActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TITULO, titulo)
                .putExtra(EXTRA_TEXTO, texto);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Aparecer sobre el bloqueo y encender la pantalla.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent in = getIntent();
        boolean cerrable = in.getBooleanExtra(EXTRA_CERRABLE, true);
        boolean alarma = in.getBooleanExtra(EXTRA_ALARMA, false);

        setContentView(construir(in.getStringExtra(EXTRA_TITULO), in.getStringExtra(EXTRA_TEXTO),
                cerrable, alarma, in.getIntExtra(EXTRA_VOL_PREVIO, -1)));

        if (alarma) {
            // La pantalla se cierra sola cuando la sirena termina (su duración la decide el dueño).
            handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!Sirena.sonando()) finish(); else handler.postDelayed(this, 1000);
                }
            }, 1000);
        }
        if (!cerrable) setFinishOnTouchOutside(false);
    }

    private LinearLayout construir(String titulo, String texto, boolean cerrable, boolean alarma, int volPrevio) {
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setGravity(Gravity.CENTER);
        raiz.setBackgroundColor(alarma ? 0xFFB91C1C : 0xFF04121E);
        int pad = (int) (28 * getResources().getDisplayMetrics().density);
        raiz.setPadding(pad, pad, pad, pad);

        TextView t = new TextView(this);
        t.setText(titulo);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(30f);
        t.setGravity(Gravity.CENTER);
        raiz.addView(t);

        TextView p = new TextView(this);
        p.setText(texto);
        p.setTextColor(0xFFEAF2FB);
        p.setTextSize(18f);
        p.setGravity(Gravity.CENTER);
        p.setPadding(0, pad / 2, 0, pad);
        raiz.addView(p);

        if (cerrable) {
            Button b = new Button(this);
            b.setText(alarma ? "Detener" : "Entendido");
            b.setOnClickListener(v -> { detenerSonido(volPrevio); finish(); });
            raiz.addView(b);
        }
        return raiz;
    }

    private void detenerSonido(int volPrevio) {
        if (getIntent().getBooleanExtra(EXTRA_ALARMA, false)) Sirena.detener(this);
    }

    @Override
    public void onBackPressed() {
        // Si es bloqueo (no cerrable), el botón atrás no la cierra.
        if (getIntent().getBooleanExtra(EXTRA_CERRABLE, true)) super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        // Cerrar la pantalla NO apaga la sirena: solo el botón «Detener» (o que se cumpla el tiempo).
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
