package dev.ventry.raschet;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.print.PrintManager;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public final class MainActivity extends Activity {
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/assets/index.html";
    private static final int OPEN_JSON = 1001;
    private static final int SAVE_COPY = 1002;
    private static final int MAX_COPY_CHARS = 8 * 1024 * 1024;

    private WebView webView;
    private android.webkit.ValueCallback<Uri[]> pendingFileChooser;
    private String pendingExportText;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff0b1512);
        getWindow().setNavigationBarColor(0xff0b1512);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0b1512);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(10));
        bar.setBackgroundColor(0xff0b1512);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextSize(23);
        title.setTextColor(Color.WHITE);
        brand.addView(title);
        TextView note = new TextView(this);
        note.setText(R.string.local_book);
        note.setTextSize(12);
        note.setTextColor(0xff9cbdac);
        brand.addView(note);
        bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        Button online = new Button(this);
        online.setAllCaps(false);
        online.setText(R.string.open_book);
        online.setTextSize(13);
        online.setTextColor(0xff10251b);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(0xffbbebd0);
        buttonBackground.setCornerRadius(dp(14));
        online.setBackground(buttonBackground);
        online.setMinHeight(dp(44));
        online.setOnClickListener(v -> openSharedBook());
        bar.addView(online, new LinearLayout.LayoutParams(-2, dp(44)));
        root.addView(bar);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true); // Required only for the bundled ledger.
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // User-selected JSON backup via document picker.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSafeBrowsingEnabled(true);
        webView.setBackgroundColor(0xfff5f6f1);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocalAsset(uri)) {
                    WebResourceResponse response = assetLoader.shouldInterceptRequest(uri);
                    return response != null ? response : deny();
                }
                // Bundled content is never allowed to fetch external scripts or resources.
                return deny();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocalAsset(uri)) return false;
                if (request.isForMainFrame() && "https".equals(uri.getScheme())) {
                    openExternal(uri);
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (APP_URL.equals(url)) {
                    view.evaluateJavascript("window.print=function(){RaschetAndroid.printPage();}", null);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                    android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
                pendingFileChooser = callback;
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("application/json");
                try {
                    startActivityForResult(picker, OPEN_JSON);
                } catch (ActivityNotFoundException failure) {
                    pendingFileChooser = null;
                    callback.onReceiveValue(null);
                    toast(R.string.pick_file_error);
                }
                return true;
            }
        });
        webView.addJavascriptInterface(new AndroidBridge(), "RaschetAndroid");
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView warning = new TextView(this);
        warning.setText(R.string.local_note);
        warning.setTextColor(0xffabc2b5);
        warning.setTextSize(11);
        warning.setGravity(Gravity.CENTER);
        warning.setPadding(dp(8), dp(7), dp(8), dp(8));
        root.addView(warning);
        setContentView(root);
        webView.loadUrl(APP_URL);
    }

    private boolean isLocalAsset(Uri uri) {
        return "https".equals(uri.getScheme())
                && APP_HOST.equals(uri.getHost())
                && uri.getPath() != null
                && uri.getPath().startsWith("/assets/");
    }

    private static WebResourceResponse deny() {
        return new WebResourceResponse("text/plain", "utf-8", 403,
                "Forbidden", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private void openExternal(Uri url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException failure) {
            toast(R.string.browser_missing);
        }
    }

    private void openSharedBook() {
        Uri destination = Uri.parse(getString(R.string.book_url));
        if ("raschet.example.test".equals(destination.getHost())) {
            toast(R.string.address_missing);
        } else {
            openExternal(destination);
        }
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public void saveText(String filename, String text, String mime) {
            if (text == null || text.length() > MAX_COPY_CHARS) {
                runOnUiThread(() -> toast(R.string.backup_size_error));
                return;
            }
            // Exactly two intended export types, never accept arbitrary file paths.
            String type = mime != null && "text/csv".equals(mime.split(";")[0]) ? "text/csv" : "application/json";
            String safeName = filename == null ? "raschet-backup.json"
                    : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (safeName.isEmpty() || safeName.length() > 120) safeName = "raschet-backup.json";
            final String exportName = safeName;
            runOnUiThread(() -> exportWithPicker(exportName, text, type));
        }

        @JavascriptInterface
        public void printPage() {
            runOnUiThread(() -> {
                PrintManager printer = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (printer != null) printer.print("Расчёт — выписка",
                        webView.createPrintDocumentAdapter("Расчёт — выписка"), null);
            });
        }
    }

    private void exportWithPicker(String filename, String text, String mime) {
        if (pendingExportText != null) return;
        pendingExportText = text;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        try {
            startActivityForResult(intent, SAVE_COPY);
        } catch (ActivityNotFoundException failure) {
            pendingExportText = null;
            toast(R.string.backup_error);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OPEN_JSON) {
            if (pendingFileChooser != null) {
                pendingFileChooser.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(result, data));
                pendingFileChooser = null;
            }
        } else if (request == SAVE_COPY) {
            String contents = pendingExportText;
            pendingExportText = null;
            if (result == RESULT_OK && data != null && data.getData() != null && contents != null) {
                try (OutputStream stream = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (stream == null) throw new IOException("No output stream");
                    stream.write(contents.getBytes(StandardCharsets.UTF_8));
                    toast(R.string.backup_saved);
                } catch (IOException | SecurityException failure) {
                    toast(R.string.backup_error);
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
        if (webView != null) {
            webView.removeJavascriptInterface("RaschetAndroid");
            webView.destroy();
        }
        super.onDestroy();
    }

    private void toast(int resource) {
        Toast.makeText(this, resource, Toast.LENGTH_LONG).show();
    }

    private int dp(int pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }
}
