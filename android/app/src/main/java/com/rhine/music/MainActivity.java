package com.rhine.music;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Single-activity WebView shell for Rhine Music on Android.
 *
 * <p>The web application itself is unchanged: it is served from {@code assets/www}
 * by a loopback server, which also exposes the local music API. Native touches
 * are deliberately small — storage access, downloads, system bars and a fast
 * back gesture — so every screen behaves like the desktop build.</p>
 */
public class MainActivity extends Activity {

    private static final String TAG = "RhineMain";
    private static final int REQUEST_TREE = 1001;
    private static final int REQUEST_AUDIO_PERMISSION = 1002;
    private static final int BACKGROUND_DAY = 0xFFE8E5E1;
    private static final String DEMO_VERSION = "1.0.0";

    private FrameLayout root;
    private WebView webView;
    private LocalServer server;
    private MusicLibrary library;
    private PlaylistStore playlists;
    private Vibrator vibrator;
    private View failurePanel;
    private volatile boolean downloading;
    private volatile HttpURLConnection downloadConnection;
    private volatile Thread downloadThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        library = new MusicLibrary(this);
        library.load();
        playlists = new PlaylistStore(new File(getFilesDir(), "music-data"), library);
        installDemo();
        library.markDemoInstalled(true);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && !vibrator.hasVibrator()) vibrator = null;

        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND_DAY);

        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND_DAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        configure(webView.getSettings());
        // Keep remote debugging available for local verification and support; the
        // socket is only reachable through adb on the device's own WebView.
        WebView.setWebContentsDebuggingEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return intercept(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectShellScript();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showFailure("页面加载失败：" + describe(error));
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                restartWebView();
                return true;
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "RhineNative");
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        applySystemBars("day");
        startSite();
        library.requestScan();
        maybeAskAudioPermission();
    }

    /** Boot the loopback server and point the WebView at it. Safe to call again. */
    private void startSite() {
        if (failurePanel != null) {
            root.removeView(failurePanel);
            failurePanel = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
        try {
            server = new LocalServer(getAssets(), library, playlists);
            server.start();
            webView.loadUrl("http://127.0.0.1:" + server.port() + "/");
        } catch (Exception failure) {
            showFailure("本地服务启动失败：" + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
        }
    }

    private void restartWebView() {
        if (root == null) return;
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND_DAY);
        configure(webView.getSettings());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return intercept(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectShellScript();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                restartWebView();
                return true;
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "RhineNative");
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        startSite();
    }

    // ------------------------------------------------------------- permissions

    private void maybeAskAudioPermission() {
        String permission = audioPermission();
        if (permission == null) return;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            dispatchEvent("rhine:permission", "{\"granted\":true}");
            return;
        }
        try {
            requestPermissions(new String[]{permission}, REQUEST_AUDIO_PERMISSION);
        } catch (Exception ignored) {
            // Some vendor ROMs reject the request; the demo library still works.
        }
    }

    private String audioPermission() {
        if (Build.VERSION.SDK_INT >= 33) return Manifest.permission.READ_MEDIA_AUDIO;
        if (Build.VERSION.SDK_INT >= 23) return Manifest.permission.READ_EXTERNAL_STORAGE;
        return null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_AUDIO_PERMISSION) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        dispatchEvent("rhine:permission", "{\"granted\":" + granted + "}");
        if (granted) library.requestScan();
    }

    // --------------------------------------------------------------- SAF import

    private void pickMusicFolder() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    startActivityForResult(intent, REQUEST_TREE);
                } catch (ActivityNotFoundException missing) {
                    toast("此设备没有文件选择器");
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null) return;
        Uri treeUri = data.getData();
        if (treeUri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception failure) {
            Log.w(TAG, "cannot persist folder permission", failure);
        }
        String label = folderLabel(treeUri);
        library.addSafRoot(treeUri.toString(), label);
        library.requestScan();
        toast("已导入「" + label + "」，正在扫描");
        dispatchEvent("rhine:native", "{\"type\":\"imported\"}");
    }

    private String folderLabel(Uri treeUri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
            Cursor cursor = getContentResolver().query(documentUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        String name = cursor.getString(0);
                        if (name != null && !name.trim().isEmpty()) return name.trim();
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception ignored) {
            // Fall through to a generic label.
        }
        return "导入文件夹";
    }

    // ---------------------------------------------------------------- download

    private void downloadAudio(final String rawUrl, final String rawName) {
        if (downloading) {
            toast("已有下载任务正在进行");
            return;
        }
        final String url = rawUrl == null ? "" : rawUrl.trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            dispatchEvent("rhine:download", "{\"type\":\"error\",\"error\":\"只支持 http/https 音频直链\"}");
            return;
        }
        downloading = true;
        downloadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                FileOutputStream output = null;
                File target = null;
                try {
                    String name = sanitizeFileName(rawName, url);
                    target = uniqueFile(library.downloadDir(), name);
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    downloadConnection = connection;
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", "RhineMusic/1.0 (Android)");
                    int status = connection.getResponseCode();
                    if (status < 200 || status >= 300) {
                        throw new IOException("服务器返回 " + status);
                    }
                    long total = connection.getContentLengthLong();
                    InputStream input = connection.getInputStream();
                    output = new FileOutputStream(target);
                    byte[] buffer = new byte[65536];
                    long received = 0;
                    long lastReport = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (!downloading) throw new IOException("下载已取消");
                        output.write(buffer, 0, count);
                        received += count;
                        long now = System.currentTimeMillis();
                        if (now - lastReport > 200 || received == total) {
                            lastReport = now;
                            dispatchDownload("progress", url, received, total, "");
                        }
                    }
                    output.getFD().sync();
                    output.close();
                    output = null;
                    dispatchDownload("done", url, received, total, "");
                    rescanAndNotify();
                } catch (Exception failure) {
                    if (output != null) {
                        try {
                            output.close();
                        } catch (IOException ignored) {
                            // Already failing.
                        }
                    }
                    if (target != null && target.isFile() && !target.delete()) {
                        Log.w(TAG, "cannot delete partial download");
                    }
                    dispatchDownload("error", url, 0, 0, failure.getMessage() == null ? "下载失败" : failure.getMessage());
                } finally {
                    if (connection != null) connection.disconnect();
                    downloadConnection = null;
                    downloading = false;
                    downloadThread = null;
                }
            }
        }, "rhine-download");
        downloadThread.start();
    }

    private void rescanAndNotify() {
        library.requestScan();
        dispatchEvent("rhine:native", "{\"type\":\"library-changed\"}");
    }

    private static String sanitizeFileName(String rawName, String url) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            int slash = url.lastIndexOf('/');
            name = slash >= 0 ? url.substring(slash + 1) : "audio";
            int query = name.indexOf('?');
            if (query >= 0) name = name.substring(0, query);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001f]", "_").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) name = "audio.mp3";
        if (name.length() > 96) {
            String extension = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) extension = name.substring(dot);
            name = name.substring(0, 96 - extension.length()) + extension;
        }
        String lower = name.toLowerCase(Locale.US);
        boolean audio = lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".flac")
                || lower.endsWith(".ogg") || lower.endsWith(".opus") || lower.endsWith(".wav")
                || lower.endsWith(".aac");
        if (!audio) name = name + ".mp3";
        return name;
    }

    private static File uniqueFile(File directory, String name) {
        File target = new File(directory, name);
        if (!target.exists()) return target;
        String stem = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int index = 2; index < 1000; index++) {
            File candidate = new File(directory, stem + " (" + index + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, System.currentTimeMillis() + extension);
    }

    private void cancelDownload() {
        downloading = false;
        HttpURLConnection connection = downloadConnection;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
                // Already closed.
            }
        }
    }

    // ---------------------------------------------------------------- demo data

    /** Copies the bundled demo album out of the APK once per version. */
    private void installDemo() {
        File marker = new File(library.demoDir(), ".version");
        try {
            if (marker.isFile() && DEMO_VERSION.equals(MusicLibrary.readText(marker).trim())) return;
            copyAssetDirectory("demo", library.demoDir());
            MusicLibrary.writeText(marker, DEMO_VERSION);
        } catch (Exception failure) {
            Log.w(TAG, "cannot install demo album", failure);
        }
    }

    private void copyAssetDirectory(String assetPath, File targetDir) throws IOException {
        String[] entries = getAssets().list(assetPath);
        if (entries == null || entries.length == 0) return;
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) throw new IOException("cannot create " + targetDir);
        for (String entry : entries) {
            String childAsset = assetPath + "/" + entry;
            String[] children = getAssets().list(childAsset);
            File target = new File(targetDir, entry);
            if (children != null && children.length > 0) {
                copyAssetDirectory(childAsset, target);
                continue;
            }
            InputStream input = null;
            OutputStream output = null;
            try {
                input = getAssets().open(childAsset);
                output = new FileOutputStream(target);
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            } finally {
                if (input != null) try { input.close(); } catch (IOException ignored) { }
                if (output != null) try { output.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ---------------------------------------------------------------- WebView

    private void configure(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
    }

    private void injectShellScript() {
        String script = "(function(){"
                + "if(!document.head||!document.documentElement)return;"
                + "document.documentElement.classList.add('rhine-android');"
                + "document.documentElement.setAttribute('data-native','android');"
                + "var style=document.getElementById('rhine-native-style');"
                + "if(!style){style=document.createElement('style');style.id='rhine-native-style';"
                + "style.textContent='.rhine-android{-webkit-tap-highlight-color:transparent}';"
                + "document.head.appendChild(style);}"
                + "document.dispatchEvent(new CustomEvent('rhine:native',{detail:{type:'ready'}}));"
                + "})();";
        webView.evaluateJavascript(script, null);
    }

    private boolean intercept(String url) {
        if (url == null) return true;
        if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // No browser available; stay where we are.
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        if (webView == null || failurePanel != null) {
            finish();
            return;
        }
        final boolean[] handled = new boolean[]{true};
        webView.evaluateJavascript(
                "(function(){try{return window.__rhineBack?String(window.__rhineBack()):'false'}catch(error){return 'false'}})()",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        boolean done = value != null && value.contains("true");
                        if (!done) finish();
                    }
                });
    }

    // -------------------------------------------------------------- system bar

    private void applySystemBars(String theme) {
        int background = BACKGROUND_DAY;
        boolean light = true;
        if ("night".equals(theme)) {
            background = 0xFF0A1220;
            light = false;
        } else if ("dusk".equals(theme)) {
            background = 0xFFCBD6DF;
            light = true;
        }
        Window window = getWindow();
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        light ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                controller.setSystemBarsAppearance(
                        light ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            int flags = light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
            if (light && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
        if (light) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.getDecorView().setSystemUiVisibility(
                        window.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }
        window.setNavigationBarDividerColor(background);
    }

    // ---------------------------------------------------------------- helpers

    private void dispatchDownload(String type, String url, long received, long total, String error) {
        JSONObject detail = new JSONObject();
        try {
            detail.put("type", type);
            detail.put("url", url);
            detail.put("received", received);
            detail.put("total", total);
            if (error != null && !error.isEmpty()) detail.put("error", error);
        } catch (Exception ignored) {
            // Literal keys only.
        }
        dispatchEvent("rhine:download", detail.toString());
    }

    private void dispatchEvent(final String name, final String detailJson) {
        if (webView == null) return;
        final String script = "(function(){try{document.dispatchEvent(new CustomEvent('"
                + name + "',{detail:" + detailJson + "}))}catch(error){}})();";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) webView.evaluateJavascript(script, null);
            }
        });
    }

    private void toast(String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showFailure(String message) {
        if (failurePanel != null || root == null) return;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = dp(28);
        panel.setPadding(side, dp(110), side, side);
        panel.setBackgroundColor(BACKGROUND_DAY);

        TextView title = new TextView(this);
        title.setText("无法打开离线界面");
        title.setTextSize(20f);
        title.setTextColor(0xFF2B2724);

        TextView detail = new TextView(this);
        detail.setText(message + "\n\n站点资源内置于安装包，通常重新尝试即可恢复；"
                + "若仍失败，请卸载后安装最新的安装包。");
        detail.setTextSize(13f);
        detail.setLineSpacing(dp(4), 1f);
        detail.setTextColor(0xFF6B625A);
        detail.setPadding(0, dp(16), 0, dp(24));

        Button retry = new Button(this);
        retry.setText("重新尝试");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startSite();
                library.requestScan();
            }
        });

        panel.addView(title);
        panel.addView(detail);
        panel.addView(retry);
        failurePanel = panel;
        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private static String describe(WebResourceError error) {
        if (error == null) return "未知错误";
        CharSequence description = error.getDescription();
        return (description == null ? "" : description.toString()) + " (" + error.getErrorCode() + ")";
    }

    @Override
    protected void onDestroy() {
        cancelDownload();
        if (webView != null) {
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.loadUrl("about:blank");
            webView.removeJavascriptInterface("RhineNative");
            webView.destroy();
            webView = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
        super.onDestroy();
    }

    // --------------------------------------------------------------- bridge

    private final class NativeBridge {

        @JavascriptInterface
        public String platform() {
            return "android";
        }

        @JavascriptInterface
        public String version() {
            return DEMO_VERSION;
        }

        @JavascriptInterface
        public void pickMusicFolder() {
            MainActivity.this.pickMusicFolder();
        }

        @JavascriptInterface
        public void requestAudioPermission() {
            maybeAskAudioPermission();
        }

        @JavascriptInterface
        public boolean hasAudioPermission() {
            String permission = audioPermission();
            return permission == null || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void downloadAudio(String url, String name) {
            MainActivity.this.downloadAudio(url, name);
        }

        @JavascriptInterface
        public void cancelDownload() {
            MainActivity.this.cancelDownload();
        }

        @JavascriptInterface
        public void rescan() {
            library.requestScan();
            toast("正在重新扫描音乐");
        }

        @JavascriptInterface
        public void toast(String message) {
            MainActivity.this.toast(message == null ? "" : message);
        }

        @JavascriptInterface
        public void setTheme(String theme) {
            applySystemBars(theme);
        }

        @JavascriptInterface
        public void setKeepAwake(final boolean awake) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (awake) {
                        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                }
            });
        }

        @JavascriptInterface
        public void vibrate() {
            final Vibrator device = vibrator;
            if (device == null || !device.hasVibrator()) return;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.vibrate(VibrationEffect.createOneShot(8, 70));
                } else {
                    device.vibrate(8);
                }
            } catch (Exception ignored) {
                // Rate limited; the tap still works.
            }
        }

        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }
    }
}
