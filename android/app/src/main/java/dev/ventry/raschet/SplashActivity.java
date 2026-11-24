package dev.ventry.raschet;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Short branded cold-launch screen; never accesses user records or the network. */
public final class SplashActivity extends Activity {
    private static final int BG = LedgerTheme.BG;
    private static final int TEXT = LedgerTheme.TEXT;
    private static final int MUTED = LedgerTheme.MUTED;
    private static final long DISPLAY_MS = 820L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean routed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        FrameLayout backdrop = new FrameLayout(this);
        backdrop.setBackgroundColor(BG);

        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER);
        center.setOrientation(LinearLayout.VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_book);
        logo.setContentDescription("Расчёт");
        center.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView name = new TextView(this);
        name.setText("Расчёт");
        name.setTextSize(26);
        name.setTextColor(TEXT);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
        nameParams.topMargin = dp(12);
        center.addView(name, nameParams);

        TextView tagline = new TextView(this);
        tagline.setText("Всё записано.");
        tagline.setTextSize(14);
        tagline.setTextColor(MUTED);
        tagline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams taglineParams = new LinearLayout.LayoutParams(-1, -2);
        taglineParams.topMargin = dp(7);
        center.addView(tagline, taglineParams);

        FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        pos.leftMargin = dp(20);
        pos.rightMargin = dp(20);
        backdrop.addView(center, pos);
        setContentView(backdrop);

        boolean motion = ValueAnimator.areAnimatorsEnabled();
        if (motion) {
            center.setAlpha(0f);
            center.setScaleX(0.965f);
            center.setScaleY(0.965f);
            center.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(440L).start();
        }
        handler.postDelayed(this::route, motion ? DISPLAY_MS : 240L);
    }

    private void route() {
        if (routed || isFinishing() || isDestroyed()) return;
        routed = true;
        Class<?> target = IntroActivity.isCompleted(this) ? MainActivity.class : IntroActivity.class;
        startActivity(new Intent(this, target));
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
