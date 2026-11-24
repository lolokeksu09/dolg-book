package dev.ventry.raschet;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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

/** Four first-launch pages: Ventry introduction followed by three interactive product lessons. */
public final class IntroActivity extends Activity {
    private static final String PREFS = "raschet_intro";
    private static final String KEY_COMPLETED = "completed";
    private static final String KEY_VENTRY_SHOWN = "ventry_shown";
    private static final int BG = LedgerTheme.BG;
    private static final int PANEL = LedgerTheme.PANEL;
    private static final int TEXT = LedgerTheme.TEXT;
    private static final int MUTED = LedgerTheme.MUTED;
    private static final int ACCENT = LedgerTheme.ACCENT;
    private static final int INK = LedgerTheme.INK;
    private static final int BORDER = LedgerTheme.BORDER;

    private static final String[] TITLES = {
            "Знакомьтесь, Ventry",
            "Записывайте долги быстро",
            "Принимайте оплаты без путаницы",
            "Ваша книга всегда под рукой"
    };
    private static final String[] DESCRIPTIONS = {
            "Ventry — проект цифровых продуктов для предпринимателей и небольших команд.",
            "Добавьте клиента и запишите покупку в долг за пару касаний.",
            "Принимайте полные и частичные оплаты. Остаток пересчитывается автоматически.",
            "Записи остаются на устройстве. Сохраняйте JSON-копии, чтобы не потерять книгу."
    };
    private static final int[] ICONS = {
            R.drawable.ic_ventry, R.drawable.ic_nav_people, R.drawable.ic_nav_history, R.drawable.ic_intro_backup
    };
    private int step;
    private LinearLayout root;
    private FrameLayout previewHost;
    // First-use practice state is memory-only: never written to LedgerStore or exports.
    private boolean exampleClientAdded;
    private boolean exampleDebtAdded;
    private int examplePaid;
    private boolean exampleBackupExpanded;
    private boolean brandDetailsExpanded;
    // Existing installations that finished the former three-step onboarding see
    // only the new brand introduction once; real ledger data is untouched.
    private boolean brandOnlyForExisting;

    public static boolean isCompleted(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_COMPLETED, false)
                && context.getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean(KEY_VENTRY_SHOWN, false);
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
        brandOnlyForExisting = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_COMPLETED, false);
        if (state != null) {
            step = brandOnlyForExisting ? 0 :
                    Math.max(0, Math.min(TITLES.length - 1, state.getInt("step", 0)));
            exampleClientAdded = state.getBoolean("exampleClientAdded");
            exampleDebtAdded = state.getBoolean("exampleDebtAdded");
            examplePaid = Math.max(0, Math.min(1200, state.getInt("examplePaid", 0)));
            exampleBackupExpanded = state.getBoolean("exampleBackupExpanded");
            brandDetailsExpanded = state.getBoolean("brandDetailsExpanded");
        }
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
        out.putBoolean("exampleClientAdded", exampleClientAdded);
        out.putBoolean("exampleDebtAdded", exampleDebtAdded);
        out.putInt("examplePaid", examplePaid);
        out.putBoolean("exampleBackupExpanded", exampleBackupExpanded);
        out.putBoolean("brandDetailsExpanded", brandDetailsExpanded);
        super.onSaveInstanceState(out);
    }

    private void renderStep() {
        root.removeAllViews();

        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(24), dp(22), dp(24), dp(8));
        ImageView mark = new ImageView(this);
        mark.setImageResource(step == 0 ? R.drawable.ic_ventry : R.drawable.ic_book);
        mark.setContentDescription(null);
        heading.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView brand = label(step == 0 ? "Ventry" : "Расчёт", 19, TEXT, true);
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
        content.setPadding(dp(16), dp(8), dp(16), dp(8));

        FrameLayout glyphFrame = new FrameLayout(this);
        glyphFrame.setBackground(shape(PANEL, 10));
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(ICONS[step]);
        if (step != 0) glyph.setColorFilter(ACCENT);
        glyph.setContentDescription(null);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER);
        glyphFrame.addView(glyph, glyphParams);
        content.addView(glyphFrame, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView number = label(step == 0 ? "ЦИФРОВЫЕ ПРОДУКТЫ" :
                "УРОК " + step + " ИЗ 3", 12, ACCENT, true);
        number.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams noParams = new LinearLayout.LayoutParams(-1, -2);
        noParams.topMargin = dp(8);
        content.addView(number, noParams);

        TextView title = label(TITLES[step], 24, TEXT, true);
        title.setMaxLines(3);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(4);
        content.addView(title, titleParams);

        TextView description = label(DESCRIPTIONS[step], 15, MUTED, false);
        description.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = dp(8);
        content.addView(description, descParams);

        previewHost = new FrameLayout(this);
        previewHost.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, -2);
        previewParams.topMargin = dp(10);
        content.addView(previewHost, previewParams);
        updatePracticePreview();
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(24), dp(12), dp(24), dp(20));
        String nextLabel = brandOnlyForExisting ? "Открыть книгу" :
                step == TITLES.length - 1 ? "Начать работу" :
                step == 0 ? "Как это работает" : "Далее";
        TextView next = button(nextLabel, true);
        next.setContentDescription(nextLabel);
        next.setOnClickListener(v -> {
            if (brandOnlyForExisting) {
                finishOnboarding();
            } else if (step < TITLES.length - 1) {
                step++;
                renderStep();
            } else {
                finishOnboarding();
            }
        });
        footer.addView(next, new LinearLayout.LayoutParams(-1, dp(48)));

        if (step < TITLES.length - 1) {
            TextView skip = button("Пропустить", false);
            skip.setContentDescription("Пропустить знакомство");
            LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(-1, dp(48));
            skipParams.topMargin = dp(6);
            footer.addView(skip, skipParams);
            skip.setOnClickListener(v -> finishOnboarding());
        }
        root.addView(footer);
    }

    /** Rebuild just the example card so taps never reset the scroll or step state. */
    private void updatePracticePreview() {
        if (previewHost == null) return;
        previewHost.removeAllViews();
        LinearLayout practice;
        if (step == 0) practice = ventryIntroduction();
        else if (step == 1) practice = debtPractice();
        else if (step == 2) practice = paymentPractice();
        else practice = backupPractice();
        previewHost.addView(practice, new FrameLayout.LayoutParams(-1, -2));
    }

    /** Brand introduction uses real Ventry identity, not a simulated accounting entry. */
    private LinearLayout ventryIntroduction() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(19), dp(20), dp(19));
        GradientDrawable bg = shape(PANEL, 22);
        bg.setStroke(dp(1), BORDER);
        card.setBackground(bg);

        TextView eyebrow = label("ПЕРВЫЙ ПРОДУКТ VENTRY", 11, ACCENT, true);
        eyebrow.setLetterSpacing(0.1f);
        card.addView(eyebrow);

        TextView productName = label("Расчёт", 26, TEXT, true);
        LinearLayout.LayoutParams productMargin = new LinearLayout.LayoutParams(-1, -2);
        productMargin.topMargin = dp(9);
        card.addView(productName, productMargin);
        practiceText(card, "Долговая книга для небольшого магазина.", 15, TEXT, 5);
        practiceText(card, "Клиенты, долги, частичные оплаты и история — в одном приложении. " +
                "Эта версия работает без интернета и хранит записи на устройстве.", 13, MUTED, 11);
        practiceButton(card, brandDetailsExpanded ? "Скрыть возможности" :
                "Посмотреть возможности", false,
                () -> brandDetailsExpanded = !brandDetailsExpanded);
        if (brandDetailsExpanded) {
            practiceText(card, "• Записывайте покупки в долг", 14, TEXT, 12);
            practiceText(card, "• Принимайте оплаты и проверяйте остаток", 14, TEXT, 7);
            practiceText(card, "• Сохраняйте резервную JSON-копию", 14, TEXT, 7);
        }
        return card;
    }

    private LinearLayout practiceCard(String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = shape(PANEL, 22);
        background.setStroke(dp(1), BORDER);
        card.setBackground(background);
        card.addView(label(heading, 17, TEXT, true));
        TextView note = label("ИНТЕРАКТИВНЫЙ ПРИМЕР · НЕ СОХРАНЯЕТСЯ", 10, MUTED, true);
        note.setLetterSpacing(0.055f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(5);
        card.addView(note, noteParams);
        return card;
    }

    private void practiceText(LinearLayout card, String value, int textSize, int color, int top) {
        TextView text = label(value, textSize, color, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        card.addView(text, params);
    }

    private void practiceButton(LinearLayout card, String name, boolean primary, Runnable action) {
        TextView button = label(name, 14, primary ? INK : ACCENT, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable background = shape(primary ? ACCENT : PANEL, 13);
        if (!primary) background.setStroke(dp(1), BORDER);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x4489bda2),
                background, null));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(name);
        button.setOnClickListener(v -> {
            action.run();
            updatePracticePreview();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        card.addView(button, params);
    }

    private LinearLayout debtPractice() {
        LinearLayout card = practiceCard("Попробуйте записать долг");
        practiceText(card, exampleClientAdded ? "Покупатель · пример" : "Список пока пуст",
                14, MUTED, 12);
        if (exampleDebtAdded) {
            practiceText(card, "Долг: 1 200 ₽", 24, ACCENT, 5);
            practiceText(card, "Покупка записана. Перейдите к оплатам на следующем шаге.",
                    12, MUTED, 8);
            practiceButton(card, "Повторить пример", false, () -> {
                exampleClientAdded = false;
                exampleDebtAdded = false;
            });
        } else if (exampleClientAdded) {
            practiceText(card, "Клиент добавлен · остаток 0 ₽", 16, TEXT, 5);
            practiceButton(card, "Записать долг · 1 200 ₽", true,
                    () -> exampleDebtAdded = true);
        } else {
            practiceText(card, "Добавьте покупателя, затем его покупку.", 13, TEXT, 5);
            practiceButton(card, "Добавить пример клиента", true,
                    () -> exampleClientAdded = true);
        }
        return card;
    }

    private LinearLayout paymentPractice() {
        LinearLayout card = practiceCard("Попробуйте принять оплату");
        int remaining = 1200 - examplePaid;
        practiceText(card, "Остаток долга", 13, MUTED, 12);
        practiceText(card, money(remaining), 27, remaining == 0 ? ACCENT : TEXT, 2);
        practiceText(card, "Уже оплачено: " + money(examplePaid), 13, MUTED, 5);
        if (remaining > 0) {
            practiceButton(card, "Внести 400 ₽", true,
                    () -> examplePaid = Math.min(1200, examplePaid + 400));
            practiceButton(card, "Погасить полностью", false, () -> examplePaid = 1200);
        } else {
            practiceText(card, "Долг погашен. Остаток обновился автоматически.",
                    13, ACCENT, 12);
            practiceButton(card, "Повторить пример", false, () -> examplePaid = 0);
        }
        return card;
    }

    private LinearLayout backupPractice() {
        LinearLayout card = practiceCard("Что сохраняется в копии?");
        practiceText(card, "raschet-backup.json", 17, ACCENT, 17);
        practiceText(card, "Вся книга в одном файле. Нажмите, чтобы изучить состав.",
                13, MUTED, 5);
        practiceButton(card, exampleBackupExpanded ? "Свернуть состав" :
                "Показать состав JSON", true,
                () -> exampleBackupExpanded = !exampleBackupExpanded);
        if (exampleBackupExpanded) {
            practiceText(card, "Клиенты и контакты", 14, TEXT, 15);
            practiceText(card, "Долги, оплаты и отмены", 14, TEXT, 8);
            practiceText(card, "Суммы, даты и история операций", 14, TEXT, 8);
            practiceText(card, "Здесь файл не создаётся. Настоящий экспорт — в Настройках. " +
                    "JSON не зашифрован: храните копию в безопасном месте.",
                    12, MUTED, 14);
        }
        return card;
    }

    private String money(int rubles) {
        if (rubles == 1200) return "1 200 ₽";
        return rubles + " ₽";
    }

    private void finishOnboarding() {
        // Commit before navigation: an interrupted process must not replay onboarding.
        boolean saved = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_COMPLETED,true)
                .putBoolean(KEY_VENTRY_SHOWN,true).commit();
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
