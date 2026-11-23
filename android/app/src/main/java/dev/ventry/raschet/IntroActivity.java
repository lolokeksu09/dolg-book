package dev.ventry.raschet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Three native first-launch pages, shown only until the user finishes or skips them. */
public final class IntroActivity extends Activity {
    private static final String PREFS = "raschet_intro";
    private static final String KEY_COMPLETED = "completed";
    private static final int BG = 0xff090e0c;
    private static final int PANEL = 0xff17231c;
    private static final int TEXT = 0xfff4f7f3;
    private static final int MUTED = 0xffa5b9ab;
    private static final int ACCENT = 0xffb9efd0;
    private static final int INK = 0xff12271b;
    private static final int BORDER = 0xff314137;

    private static final String[] TITLES = {
            "Записывайте долги быстро",
            "Принимайте оплаты без путаницы",
            "Ваша книга всегда под рукой"
    };
    private static final String[] DESCRIPTIONS = {
            "Добавьте клиента и запишите покупку в долг за пару касаний.",
            "Принимайте полные и частичные оплаты. Остаток пересчитывается автоматически.",
            "Записи остаются на устройстве. Сохраняйте JSON-копии, чтобы не потерять книгу."
    };
    private static final int[] ICONS = {
            R.drawable.ic_nav_people, R.drawable.ic_nav_history, R.drawable.ic_intro_backup
    };
    private int step;
    private LinearLayout root;

    public static boolean isCompleted(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_COMPLETED, false);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (isCompleted(this)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        if (state != null) step = Math.max(0, Math.min(2, state.getInt("step", 0)));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        renderStep();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("step", step);
        super.onSaveInstanceState(out);
    }

    private void renderStep() {
        root.removeAllViews();

        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(24), dp(22), dp(24), dp(8));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_book);
        mark.setContentDescription(null);
        heading.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView brand = label("Расчёт", 19, TEXT, true);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(-2, -2);
        brandParams.leftMargin = dp(12);
        heading.addView(brand, brandParams);
        root.addView(heading);

        LinearLayout progress = row();
        progress.setPadding(dp(26), dp(22), dp(26), 0);
        for (int i = 0; i < TITLES.length; i++) {
            View segment = new View(this);
            segment.setBackground(shape(i <= step ? ACCENT : BORDER, 4));
            LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(0, dp(4), 1f);
            if (i > 0) bar.leftMargin = dp(8);
            progress.addView(segment, bar);
        }
        root.addView(progress);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(28), dp(30), dp(28), dp(18));

        FrameLayout glyphFrame = new FrameLayout(this);
        glyphFrame.setBackground(shape(PANEL, 30));
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(ICONS[step]);
        glyph.setColorFilter(ACCENT);
        glyph.setContentDescription(null);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER);
        glyphFrame.addView(glyph, glyphParams);
        content.addView(glyphFrame, new LinearLayout.LayoutParams(dp(112), dp(112)));

        TextView number = label("ШАГ " + (step + 1) + " ИЗ 3", 12, ACCENT, true);
        number.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams noParams = new LinearLayout.LayoutParams(-1, -2);
        noParams.topMargin = dp(36);
        content.addView(number, noParams);

        TextView title = label(TITLES[step], 31, TEXT, true);
        title.setMaxLines(3);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(12);
        content.addView(title, titleParams);

        TextView description = label(DESCRIPTIONS[step], 16, MUTED, false);
        description.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = dp(18);
        content.addView(description, descParams);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(24), dp(12), dp(24), dp(20));
        TextView next = button(step == 2 ? "Начать работу" : "Далее", true);
        next.setContentDescription(step == 2 ? "Начать работу" : "Далее");
        next.setOnClickListener(v -> {
            if (step < 2) {
                step++;
                renderStep();
            } else {
                finishOnboarding();
            }
        });
        footer.addView(next, new LinearLayout.LayoutParams(-1, dp(56)));

        if (step < 2) {
            TextView skip = button("Пропустить", false);
            skip.setContentDescription("Пропустить знакомство");
            LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(-1, dp(48));
            skipParams.topMargin = dp(6);
            footer.addView(skip, skipParams);
            skip.setOnClickListener(v -> finishOnboarding());
        }
        root.addView(footer);
    }

    private void finishOnboarding() {
        // Commit before navigation: an interrupted process must not replay onboarding.
        boolean saved = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_COMPLETED,true).commit();
        if (!saved) {
            Toast.makeText(this, "Не удалось сохранить настройку. Попробуйте ещё раз.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_FIRST_START, true);
        startActivity(open);
        finish();
    }

    @Override public void onBackPressed() {
        if (step > 0) {
            step--;
            renderStep();
        } else {
            super.onBackPressed();
        }
    }

    private TextView button(String text, boolean filled) {
        TextView label = label(text, 15, filled ? INK : MUTED, true);
        label.setGravity(Gravity.CENTER);
        label.setBackground(shape(filled ? ACCENT : BG, 15));
        label.setClickable(true);
        label.setFocusable(true);
        return label;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return tv;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
