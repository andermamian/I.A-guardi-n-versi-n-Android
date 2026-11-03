package co.guardiania.app.ui.kit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Botón de pánico que se activa al MANTENER presionado 2 segundos (igual que la web): un anillo se
 * llena mientras se sostiene y el teléfono vibra al completarse. Así no se dispara en el bolsillo.
 *
 * <p>Accesibilidad: con TalkBack no se puede «sostener» igual, así que la acción de accesibilidad
 * (doble toque) dispara {@link #alConfirmarAccesible}, que debe pedir confirmación en un diálogo.
 * Misma decisión que la web: evitar falsos positivos sin excluir a quien usa lector de pantalla.
 */
public class BotonSos extends View {

    public interface Accion { void ejecutar(); }

    private static final long DURACION_MS = 2000;
    private final Paint fondo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint anillo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pista = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arco = new RectF();
    private long inicio = 0;
    private boolean disparado = false;
    private Accion alCompletar, alConfirmarAccesible;

    public BotonSos(Context c) {
        super(c);
        fondo.setColor(Ui.conAlfa(Ui.PELIGRO, 0.18f));
        anillo.setColor(Ui.PELIGRO);
        anillo.setStyle(Paint.Style.STROKE);
        anillo.setStrokeCap(Paint.Cap.ROUND);
        anillo.setStrokeWidth(Ui.dp(c, 7));
        pista.set(anillo);
        pista.setColor(Ui.conAlfa(Ui.PELIGRO, 0.25f));
        texto.setColor(Ui.PELIGRO);
        texto.setTextAlign(Paint.Align.CENTER);
        texto.setTypeface(Typeface.DEFAULT_BOLD);
        texto.setTextSize(Ui.dp(c, 34));
        sub.setColor(Ui.TEXTO2);
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setTextSize(Ui.dp(c, 12));
        setContentDescription("Botón SOS. Mantén presionado dos segundos para avisar a tus contactos de emergencia.");
        setFocusable(true);
        setClickable(true);
    }

    public void alCompletar(Accion a) { alCompletar = a; }
    public void alConfirmarAccesible(Accion a) { alConfirmarAccesible = a; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                inicio = SystemClock.uptimeMillis();
                disparado = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                postInvalidateOnAnimation();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                inicio = 0;
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !disparado) performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;   // un toque corto NO dispara el SOS
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "avisar a mis contactos"));
    }

    @Override
    public boolean performAccessibilityAction(int action, android.os.Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_CLICK && alConfirmarAccesible != null) {
            alConfirmarAccesible.ejecutar();
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int lado = Ui.dp(getContext(), 170);
        setMeasuredDimension(resolveSize(lado, wSpec), resolveSize(lado, hSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - anillo.getStrokeWidth();
        c.drawCircle(cx, cy, r, fondo);
        arco.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(arco, 0, 360, false, pista);
        float progreso = 0;
        if (inicio > 0) {
            progreso = Math.min(1f, (SystemClock.uptimeMillis() - inicio) / (float) DURACION_MS);
            if (progreso >= 1f && !disparado) {
                disparado = true;
                inicio = 0;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (alCompletar != null) alCompletar.ejecutar();
            } else if (!disparado) {
                postInvalidateOnAnimation();
            }
        }
        c.drawArc(arco, -90, 360 * progreso, false, anillo);
        c.drawText("SOS", cx, cy + texto.getTextSize() / 3, texto);
        c.drawText(progreso > 0 ? "Sigue presionando…" : "Mantén 2 segundos", cx, cy + texto.getTextSize() / 3 + Ui.dp(getContext(), 22), sub);
    }
}
