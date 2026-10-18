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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private static final int REQUEST_BILI_TREE = 1003;
    private static final int BACKGROUND_DAY = 0xFFE8E5E1;
    private static final String APP_VERSION = "1.1.0";
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
    private volatile boolean biliIngesting;
    private volatile boolean suGranted;
    private volatile String lastBiliPath = "";
    private volatile String lastBiliPackages = "[]";
    private volatile boolean stagedBiliStarted;

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
                maybeStartStagedIngest();
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
                maybeStartStagedIngest();
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

    /** SAF route for devices without root: pick an exported Bilibili cache folder. */
    private void pickBiliFolder() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    startActivityForResult(intent, REQUEST_BILI_TREE);
                } catch (ActivityNotFoundException missing) {
                    dispatchBili("error", 0, 0, 0, "此设备没有文件选择器，请用 ADB 把缓存推入后重启应用");
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri treeUri = data.getData();
        if (treeUri == null) return;
        if (requestCode != REQUEST_TREE && requestCode != REQUEST_BILI_TREE) return;
        try {
            getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception failure) {
            Log.w(TAG, "cannot persist folder permission", failure);
        }
        if (requestCode == REQUEST_TREE) {
            String label = folderLabel(treeUri);
            library.addSafRoot(treeUri.toString(), label);
            library.requestScan();
            toast("已导入「" + label + "」，正在扫描");
            dispatchEvent("rhine:native", "{\"type\":\"imported\"}");
            return;
        }
        final Uri biliUri = treeUri;
        final String label = folderLabel(treeUri);
        if (!beginBiliImport("正在读取「" + label + "」")) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File inbox = library.biliInboxDir();
                    BiliCache.clearDirectory(inbox);
                    dispatchBili("progress", 0, 0, 0, "正在复制「" + label + "」中的 entry.json / audio.m4s");
                    int files = BiliCache.copySafTree(MainActivity.this, biliUri, inbox);
                    if (files == 0) {
                        dispatchBili("error", 0, 0, 0,
                                "所选目录中没有 entry.json / audio.m4s。请选择 B 站缓存目录（download 或其子目录）。");
                        return;
                    }
                    lastBiliPath = treePath(biliUri);
                    runBiliIngest(inbox);
                } catch (Throwable failure) {
                    dispatchBili("error", 0, 0, 0, describeFailure(failure));
                } finally {
                    endBiliImport();
                }
            }
        }, "rhine-bili-saf").start();
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

    /** Best-effort user-visible path for a SAF tree (for biliStatus.manualPath). */
    private String treePath(Uri treeUri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (documentId.startsWith("primary:")) {
                return "/sdcard/" + documentId.substring("primary:".length());
            }
            if (documentId.startsWith("home:")) {
                return "/sdcard/Documents/" + documentId.substring("home:".length());
            }
        } catch (Exception ignored) {
            // Fall through to the display name.
        }
        return folderLabel(treeUri);
    }

    // ---------------------------------------------------------------- download

    private void downloadAudio(final String rawUrl, final String rawName) {
        downloadAudio(rawUrl, rawName, "");
    }

    private void downloadAudio(final String rawUrl, final String rawName, final String rawReferer) {
        if (downloading) {
            toast("已有下载任务正在进行");
            return;
        }
        final String url = rawUrl == null ? "" : rawUrl.trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            dispatchDownload("error", url, 0, 0, "只支持 http/https 音频直链");
            return;
        }
        final String referer = rawReferer == null ? "" : rawReferer.trim();
        downloading = true;
        downloadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String finalUrl = url;
                File target = null;
                File part = null;
                boolean success = false;
                String errorMessage = "下载失败";
                try {
                    String provisional = Net.sanitizeFileName(rawName, url);
                    target = uniqueFile(library.downloadDir(), provisional);
                    part = new File(target.getParentFile(), target.getName() + ".part");
                    for (int attempt = 0; attempt < 2 && !success; attempt++) {
                        if (!downloading) {
                            errorMessage = "下载已取消";
                            break;
                        }
                        HttpURLConnection connection = null;
                        FileOutputStream output = null;
                        try {
                            long existing = part.isFile() ? part.length() : 0;
                            Map<String, String> extra = new java.util.HashMap<String, String>();
                            if (existing > 0) extra.put("Range", "bytes=" + existing + "-");
                            String userAgent = referer.contains("bilibili.com") ? Net.UA_DESKTOP : Net.UA_APP;
                            String[] resolved = new String[]{url};
                            connection = Net.connect(url, "GET", referer, userAgent,
                                    15000, 60000, 6, extra, resolved);
                            downloadConnection = connection;
                            finalUrl = resolved[0];
                            int status = connection.getResponseCode();
                            if (status == 416 && existing > 0) {
                                // Stale range: restart from byte 0 once.
                                connection.disconnect();
                                connection = null;
                                if (part.exists() && !part.delete()) {
                                    throw new IOException("无法清理断点文件");
                                }
                                existing = 0;
                                extra.clear();
                                resolved[0] = url;
                                connection = Net.connect(url, "GET", referer, userAgent,
                                        15000, 60000, 6, extra, resolved);
                                downloadConnection = connection;
                                finalUrl = resolved[0];
                                status = connection.getResponseCode();
                            }
                            if (status < 200 || status >= 300) {
                                throw new IOException("服务器返回 " + status);
                            }
                            if (rawName == null || rawName.trim().isEmpty()) {
                                String disposition = connection.getHeaderField("Content-Disposition");
                                String fromHeader = Net.headerFilename(disposition);
                                if (!fromHeader.isEmpty()) {
                                    File renamed = uniqueFile(library.downloadDir(),
                                            Net.sanitizeFileName(fromHeader, finalUrl));
                                    if (!renamed.getAbsolutePath().equals(target.getAbsolutePath())) {
                                        File moved = new File(renamed.getParentFile(),
                                                renamed.getName() + ".part");
                                        if (part.isFile() && part.renameTo(moved)) {
                                            target = renamed;
                                            part = moved;
                                        } else if (!part.isFile()) {
                                            target = renamed;
                                            part = moved;
                                        }
                                    }
                                }
                            }
                            boolean append = existing > 0 && status == 206;
                            long contentLength = connection.getContentLengthLong();
                            long total = contentLength > 0
                                    ? (append ? existing + contentLength : contentLength) : 0;
                            InputStream input = connection.getInputStream();
                            output = new FileOutputStream(part, append);
                            byte[] buffer = new byte[65536];
                            long received = append ? existing : 0;
                            long lastReport = 0;
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                if (!downloading) throw new IOException("下载已取消");
                                output.write(buffer, 0, count);
                                received += count;
                                long now = System.currentTimeMillis();
                                if (now - lastReport > 200 || (total > 0 && received >= total)) {
                                    lastReport = now;
                                    dispatchDownload("progress", finalUrl, received, total, "");
                                }
                            }
                            output.getFD().sync();
                            output.close();
                            output = null;
                            try {
                                input.close();
                            } catch (IOException ignored) {
                                // Body already finished.
                            }
                            if (target.exists() && !target.delete()) {
                                throw new IOException("无法覆盖同名文件");
                            }
                            if (!part.renameTo(target)) {
                                throw new IOException("无法保存下载文件");
                            }
                            success = true;
                            dispatchDownload("done", finalUrl, received, total, "");
                        } catch (Exception failure) {
                            errorMessage = failure.getMessage() == null ? "下载失败" : failure.getMessage();
                            if (!downloading) break;
                            if (attempt == 0) {
                                try {
                                    Thread.sleep(700);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        } finally {
                            if (output != null) try { output.close(); } catch (IOException ignored) { }
                            if (connection != null) connection.disconnect();
                            downloadConnection = null;
                        }
                    }
                    if (success) {
                        rescanAndNotify();
                    } else {
                        if (part != null && part.isFile() && part.length() == 0 && !part.delete()) {
                            Log.w(TAG, "cannot delete empty download stub");
                        }
                        dispatchDownload("error", finalUrl, 0, 0, errorMessage);
                    }
                } finally {
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

    // ------------------------------------------------------------ bilibili

    private String biliStatusJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("rootAvailable", BiliCache.suBinaryExists());
            json.put("suGranted", suGranted);
            String manualPath = lastBiliPath;
            if (manualPath == null || manualPath.isEmpty()) {
                manualPath = "/sdcard/Download/BiliCache";
            }
            json.put("manualPath", manualPath);
            json.put("entries", BiliCache.countEntries(library.biliInboxDir()));
            json.put("packages", new JSONArray(lastBiliPackages));
            json.put("supported", true);
        } catch (Exception ignored) {
            // Literal keys only; never fail the bridge.
        }
        return json.toString();
    }

    /** Checks {@code su -c id} on a background thread and reports rhine:root. */
    private void requestRoot() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                BiliCache.RootCheck check = BiliCache.checkRoot();
                suGranted = check.granted;
                if (check.granted) {
                    List<String> dirs = BiliCache.findCacheDirs();
                    lastBiliPackages = BiliCache.packagesJson(dirs).toString();
                    if (!dirs.isEmpty()) lastBiliPath = dirs.get(0);
                }
                dispatchRoot(check.granted, check.message);
            }
        }, "rhine-root").start();
    }

    /** Root import: su copy into files/bili-inbox, then the shared ingest pipeline. */
    private void importBilibiliCache() {
        if (!beginBiliImport("正在检查 Root 权限")) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BiliCache.RootCheck check = BiliCache.checkRoot();
                    suGranted = check.granted;
                    dispatchRoot(check.granted, check.message);
                    if (!check.granted) {
                        dispatchBili("error", 0, 0, 0, "未获得 Root 权限：" + check.message);
                        return;
                    }
                    dispatchBili("progress", 0, 0, 0, "正在定位 B 站缓存目录");
                    List<String> dirs = BiliCache.findCacheDirs();
                    lastBiliPackages = BiliCache.packagesJson(dirs).toString();
                    if (!dirs.isEmpty()) lastBiliPath = dirs.get(0);
                    if (dirs.isEmpty()) {
                        dispatchBili("error", 0, 0, 0,
                                "没有找到 B 站缓存目录。请确认 B 站客户端已经下载视频，"
                                        + "或改用「选择缓存目录」导入。");
                        return;
                    }
                    dispatchBili("progress", 0, 0, 0,
                            "正在复制 " + dirs.size() + " 个缓存目录到应用私有目录");
                    File inbox = library.biliInboxDir();
                    String stagingError = BiliCache.stageRootDirectories(
                            dirs, inbox, android.os.Process.myUid());
                    if (stagingError != null) {
                        dispatchBili("error", 0, 0, 0, stagingError);
                        return;
                    }
                    runBiliIngest(inbox);
                } catch (Throwable failure) {
                    dispatchBili("error", 0, 0, 0, describeFailure(failure));
                } finally {
                    endBiliImport();
                }
            }
        }, "rhine-bili-root").start();
    }

    /** Startup hook: import a previously staged inbox (ADB push + restart). */
    private void maybeStartStagedIngest() {
        if (stagedBiliStarted) return;
        final File inbox = library.biliInboxDir();
        if (!BiliCache.hasEntries(inbox)) return;
        stagedBiliStarted = true;
        if (!beginBiliImport("检测到已暂存的 B 站缓存，正在导入")) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runBiliIngest(inbox);
                } catch (Throwable failure) {
                    dispatchBili("error", 0, 0, 0, describeFailure(failure));
                } finally {
                    endBiliImport();
                }
            }
        }, "rhine-bili-staged").start();
    }

    private boolean beginBiliImport(String message) {
        synchronized (this) {
            if (biliIngesting) {
                toast("B 站缓存导入正在进行");
                return false;
            }
            biliIngesting = true;
        }
        dispatchBili("progress", 0, 0, 0, message == null ? "" : message);
        return true;
    }

    private void endBiliImport() {
        synchronized (this) {
            biliIngesting = false;
        }
    }

    /** Shared sink for Root, SAF and staged imports; runs on a worker thread. */
    private void runBiliIngest(File dir) {
        BiliCache.Result result = BiliCache.ingest(dir, library, new BiliCache.Listener() {
            @Override
            public void onProgress(int copied, int total, int converted, String message) {
                dispatchBili("progress", copied, total, converted, message);
            }
        });
        if (result.total == 0) {
            dispatchBili("error", 0, 0, 0, result.message);
            return;
        }
        if (result.copied == 0) {
            dispatchBili("error", result.copied, result.total, result.converted, result.message);
            return;
        }
        dispatchBili("done", result.copied, result.total, result.converted, result.message);
        rescanAndNotify();
    }

    private void resolveLink(final String rawUrl) {
        final String url = rawUrl == null ? "" : rawUrl.trim();
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject result;
                try {
                    result = MediaResolver.resolve(url);
                } catch (Throwable failure) {
                    result = MediaResolver.error("audio", describeFailure(failure));
                }
                dispatchEvent("rhine:resolve", result.toString());
            }
        }, "rhine-resolve").start();
    }

    private void dispatchBili(String type, int copied, int total, int converted, String message) {
        JSONObject detail = new JSONObject();
        try {
            detail.put("type", type);
            detail.put("copied", copied);
            detail.put("total", total);
            detail.put("converted", converted);
            if (message != null && !message.isEmpty()) detail.put("message", message);
        } catch (Exception ignored) {
            // Literal keys only.
        }
        dispatchEvent("rhine:bili", detail.toString());
    }

    private void dispatchRoot(boolean granted, String message) {
        JSONObject detail = new JSONObject();
        try {
            detail.put("granted", granted);
            detail.put("message", message == null ? "" : message);
        } catch (Exception ignored) {
            // Literal keys only.
        }
        dispatchEvent("rhine:root", detail.toString());
    }

    private static String describeFailure(Throwable failure) {
        if (failure == null) return "未知错误";
        String message = failure.getMessage();
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName() : message;
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
            return APP_VERSION;
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
        public String biliStatus() {
            return MainActivity.this.biliStatusJson();
        }

        @JavascriptInterface
        public void requestRoot() {
            MainActivity.this.requestRoot();
        }

        @JavascriptInterface
        public void importBilibiliCache() {
            MainActivity.this.importBilibiliCache();
        }

        @JavascriptInterface
        public void pickBiliFolder() {
            MainActivity.this.pickBiliFolder();
        }

        @JavascriptInterface
        public void resolveLink(String url) {
            MainActivity.this.resolveLink(url);
        }

        @JavascriptInterface
        public void downloadResolved(String url, String name, String referer) {
            MainActivity.this.downloadAudio(url, name, referer);
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
