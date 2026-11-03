package co.guardiania.app.ui.kit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * Gráfica de línea mínima (evolución del pulso). Sin librerías: unas 60 líneas de Canvas pesan
 * menos que cualquier dependencia de gráficos, y bastan para 8-48 puntos.
 * Para lectores de pantalla, la descripción resume mínimo, máximo y último valor.
 */
public class Grafica extends View {

    private double[] valores = new double[0];
    private final Paint linea = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint relleno = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint punto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rejilla = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint etiqueta = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Grafica(Context c) {
        super(c);
        linea.setColor(Ui.ACENTO);
        linea.setStrokeWidth(Ui.dp(c, 2.5f));
        linea.setStyle(Paint.Style.STROKE);
        linea.setStrokeJoin(Paint.Join.ROUND);
        punto.setColor(Ui.ACENTO);
        rejilla.setColor(Ui.BORDE);
        rejilla.setStrokeWidth(Ui.dp(c, 1));
        etiqueta.setColor(Ui.TEXTO2);
        etiqueta.setTextSize(Ui.dp(c, 11));
    }

    public void setValores(double[] v, String unidad) {
        valores = v != null ? v : new double[0];
        if (valores.length > 0) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double x : valores) { min = Math.min(min, x); max = Math.max(max, x); }
            setContentDescription(String.format("Gráfica: mínimo %.0f, máximo %.0f, último %.0f %s",
                    min, max, valores[valores.length - 1], unidad));
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), pad = Ui.dp(getContext(), 8), izq = Ui.dp(getContext(), 30);
        for (int i = 0; i < 3; i++) {
            float y = pad + (h - 2 * pad) * i / 2f;
            canvas.drawLine(izq, y, w - pad, y, rejilla);
        }
        if (valores.length < 2) {
            canvas.drawText("Sin datos suficientes todavía", izq, h / 2f, etiqueta);
            return;
        }
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double x : valores) { min = Math.min(min, x); max = Math.max(max, x); }
        if (max - min < 10) { double m = (max + min) / 2; min = m - 5; max = m + 5; }
        canvas.drawText(String.valueOf(Math.round(max)), 0, pad + etiqueta.getTextSize() / 2, etiqueta);
        canvas.drawText(String.valueOf(Math.round(min)), 0, h - pad, etiqueta);
        Path p = new Path(), area = new Path();
        for (int i = 0; i < valores.length; i++) {
            float x = izq + (w - izq - pad) * i / (valores.length - 1f);
            float y = (float) (h - pad - (h - 2 * pad) * (valores[i] - min) / (max - min));
            if (i == 0) { p.moveTo(x, y); area.moveTo(x, h - pad); area.lineTo(x, y); }
            else { p.lineTo(x, y); area.lineTo(x, y); }
            if (i == valores.length - 1) {
                area.lineTo(x, h - pad);
                area.close();
                canvas.drawCircle(x, y, Ui.dp(getContext(), 4), punto);
            }
        }
        relleno.setShader(new LinearGradient(0, 0, 0, h, Ui.conAlfa(Ui.ACENTO, 0.35f), Ui.conAlfa(Ui.ACENTO, 0f), Shader.TileMode.CLAMP));
        canvas.drawPath(area, relleno);
        canvas.drawPath(p, linea);
    }
}
