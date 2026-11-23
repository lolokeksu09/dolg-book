package dev.ventry.raschet;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(0xff0c1310);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        page.addView(content);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(40);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(0xfff1f3ea);
        content.addView(title);

        TextView description = new TextView(this);
        description.setText(R.string.book_description);
        description.setTextSize(18);
        description.setTextColor(0xffc0d8c7);
        content.addView(description);

        Button open = new Button(this);
        open.setText(R.string.open_book);
        open.setAllCaps(false);
        open.setOnClickListener(view -> openBook());
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-2, -2);
        action.topMargin = dp(16);
        content.addView(open, action);

        status = new TextView(this);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setTextColor(0xffc0d8c7);
        status.setVisibility(TextView.GONE);
        content.addView(status);
        if (!hasServer()) {
            open.setEnabled(false);
            showStatus(R.string.address_missing);
        }

        setContentView(page);
    }

    private boolean hasServer() {
        return !"raschet.example.test".equals(Uri.parse(getString(R.string.book_url)).getHost());
    }

    private void openBook() {
        if (!hasServer()) return;
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.book_url)));
        open.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(open);
            status.setVisibility(TextView.GONE);
        } catch (ActivityNotFoundException error) {
            showStatus(R.string.browser_missing);
        }
    }

    private void showStatus(int message) {
        status.setText(message);
        status.setVisibility(TextView.VISIBLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
