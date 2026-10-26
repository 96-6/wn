package app.wn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import app.wn.audio.NoisePreset;
import java.util.Locale;

/** Static, truthful view of the profile, not a pretend live audio analyser. */
final class SpectrumView extends View {
    private static final int[] COLORS = {0xffb78136, 0xffed5f48, 0xffff8054, 0xffd5dc4c, 0xff8dd77c,
            0xff64e6c5, 0xff53cfe1, 0xff778bff, 0xffc77ae8, 0xffe2a0f2};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bandRect = new RectF();
    private NoisePreset preset;
    public SpectrumView(Context context) { this(context, null); }
    SpectrumView(Context context, NoisePreset preset) {
        super(context); setProfile(preset); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    void setProfile(NoisePreset value) {
        preset = value;
        setContentDescription(value == null ? "No spectral profile" : "Frequency profile: " + value.name);
        invalidate();
    }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (preset == null) return;
        float left = dp(12), right = getWidth() - dp(12), top = dp(14), bottom = getHeight() - dp(25);
        if (preset.isOctaveProfile()) {
            double[] values = preset.octaveLevels();
            String[] labels = {"31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
            for (int i = 0; i < 10; i++) {
                float x = left + i * (right - left) / 9, y = bottom - (float) values[i] * (bottom - top);
                paint.setColor(0xff304049); paint.setStrokeWidth(dp(5)); paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(x, top, x, bottom, paint);
                paint.setColor(COLORS[i]); paint.setStrokeWidth(dp(3));
                canvas.drawLine(x, y, x, bottom, paint);
                paint.setStyle(Paint.Style.FILL); paint.setColor(0xff142028); canvas.drawCircle(x, y, dp(7), paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(COLORS[i]);
                canvas.drawCircle(x, y, dp(6), paint); paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(9)); paint.setColor(0xff9fb0b9);
                canvas.drawText(labels[i], x, getHeight() - dp(5), paint);
            }
        } else {
            paint.setColor(0xff263740); paint.setStrokeWidth(dp(1)); canvas.drawLine(left, bottom, right, bottom, paint);
            for (NoisePreset.Band band : preset.frequencyBands()) {
                float x1 = left + (float) (Math.log(band.lowHz / 20) / Math.log(1100)) * (right - left);
                float x2 = left + (float) (Math.log(band.highHz / 20) / Math.log(1100)) * (right - left);
                float y = bottom - (float) band.level * (bottom - top);
                bandRect.set(x1, y, Math.max(x1 + dp(2), x2), bottom);
                paint.setColor(0x3372e8d0); canvas.drawRoundRect(bandRect, dp(3), dp(3), paint);
                paint.setColor(0xff72e8d0); paint.setStrokeWidth(dp(2)); canvas.drawLine(x1, y, x2, y, paint);
            }
            paint.setColor(0xff9fb0b9); paint.setTextSize(dp(10));
            paint.setTextAlign(Paint.Align.LEFT); canvas.drawText("20 Hz", left, getHeight() - dp(5), paint);
            paint.setTextAlign(Paint.Align.RIGHT); canvas.drawText("22 kHz", right, getHeight() - dp(5), paint);
            paint.setTextAlign(Paint.Align.CENTER); canvas.drawText("Selected bands only", getWidth() / 2f, getHeight() - dp(5), paint);
        }
    }
}
