package app.wn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class Ui {
    static final int BG = 0xff0c1318, CARD = 0xff162128, INK = 0xffeaf4f6, MUTED = 0xffa5b7bf, ACCENT = 0xff72e8d0;
    private Ui() {}
    static int dp(Activity a, float value) { return Math.round(value * a.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Activity a) { LinearLayout v = new LinearLayout(a); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout row(Activity a) { LinearLayout v = new LinearLayout(a); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    static TextView text(Activity a, String value, int size, int color, boolean bold) {
        TextView t = new TextView(a); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return t;
    }
    static GradientDrawable shape(Activity a, int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(a, 12)); return d;
    }
    static Button button(Activity a, String label, boolean primary) {
        Button b = new Button(a); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(primary ? BG : INK);
        b.setMinHeight(dp(a, 48)); b.setMinimumHeight(dp(a, 48)); b.setPadding(dp(a, 8), 0, dp(a, 8), 0);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape(a, primary ? ACCENT : 0xff20313a), null));
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(a, 48))); return b;
    }
    static void add(Activity a, LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams lp = view.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) view.getLayoutParams() : new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(a, margin); parent.addView(view, lp);
    }
    static LinearLayout card(Activity a, LinearLayout parent) {
        LinearLayout c = column(a); c.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 12));
        c.setBackground(shape(a, CARD)); add(a, parent, c, 14); return c;
    }
    static LinearLayout screen(Activity a, String title, boolean back) {
        LinearLayout root = column(a); root.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(a); scroll.setFillViewport(true);
        LinearLayout body = column(a); body.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 24));
        scroll.addView(body); root.addView(scroll); a.setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
        LinearLayout heading = row(a);
        if (back) {
            Button button = button(a, "‹", false); button.setContentDescription("Back"); button.setOnClickListener(v -> a.finish());
            heading.addView(button, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        }
        TextView name = text(a, title, 24, INK, true); name.setPadding(back ? dp(a, 12) : 0, 0, 0, 0);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); body.addView(heading);
        return body;
    }
    static void error(Activity a, String title, String message) {
        if (!a.isDestroyed() && !a.isFinishing()) new AlertDialog.Builder(a).setTitle(title).setMessage(message == null ? "Please try again." : message)
                .setPositiveButton("OK", null).show();
    }
}

