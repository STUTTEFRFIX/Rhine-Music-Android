package com.rhine.music;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.Map;

/**
 * Small HTTP helpers shared by the downloader, the Bilibili resolver and the
 * cache importer.
 *
 * <p>Redirects are followed by hand instead of relying on
 * {@link HttpURLConnection#setInstanceFollowRedirects(boolean)} so that the
 * final URL, per request headers and Range requests stay under our control.
 * Every call is bounded by connect/read timeouts because all of this runs on a
 * background thread that must never wedge the app.</p>
 */
final class Net {

    /** A current mobile browser UA; Bilibili's CDN rejects the app UA. */
    static final String UA_DESKTOP =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    /** Used for plain direct downloads that do not care about the client. */
    static final String UA_APP = "RhineMusic/1.1 (Android)";
    static final String BILI_REFERER = "https://www.bilibili.com/";

    private Net() {
    }

    /**
     * Opens {@code url} and follows up to {@code maxRedirects} 3xx responses.
     *
     * @param finalUrl optional one-element array filled with the last URL
     */
    static HttpURLConnection connect(String url, String method, String referer, String userAgent,
                                     int connectTimeout, int readTimeout, int maxRedirects,
                                     Map<String, String> extraHeaders, String[] finalUrl) throws IOException {
        String current = url;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent",
                    userAgent == null || userAgent.isEmpty() ? UA_APP : userAgent);
            if (referer != null && !referer.isEmpty()) {
                connection.setRequestProperty("Referer", referer);
            }
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }
            int status;
            try {
                status = connection.getResponseCode();
            } catch (IOException failure) {
                connection.disconnect();
                throw failure;
            }
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("服务器重定向但没有提供 Location");
                }
                current = new URL(new URL(current), location.trim()).toString();
                continue;
            }
            if (finalUrl != null && finalUrl.length > 0) finalUrl[0] = current;
            return connection;
        }
        throw new IOException("重定向次数过多");
    }

    static byte[] readBytes(HttpURLConnection connection, int maxBytes) throws IOException {
        InputStream input = connection.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (maxBytes > 0 && out.size() > maxBytes) {
                    throw new IOException("响应超过 " + maxBytes + " 字节");
                }
            }
            return out.toByteArray();
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // Already closing.
            }
        }
    }

    static String readText(HttpURLConnection connection, int maxBytes) throws IOException {
        byte[] bytes = readBytes(connection, maxBytes);
        String charset = "UTF-8";
        String contentType = connection.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.US).contains("charset=")) {
            int marker = contentType.toLowerCase(Locale.US).indexOf("charset=");
            String value = contentType.substring(marker + "charset=".length()).trim();
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) value = value.substring(0, semicolon);
            value = value.trim().replace("\"", "");
            if (!value.isEmpty()) charset = value;
        }
        try {
            return new String(bytes, charset);
        } catch (Exception unsupported) {
            return new String(bytes, "UTF-8");
        }
    }

    /** GET with the standard 15s/60s timeouts and optional headers. */
    static byte[] fetchBytes(String url, String referer, String userAgent, int maxBytes,
                             int connectTimeout, int readTimeout,
                             Map<String, String> extraHeaders, String[] finalUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = connect(url, "GET", referer, userAgent, connectTimeout, readTimeout, 6,
                    extraHeaders, finalUrl);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("服务器返回 " + status);
            }
            return readBytes(connection, maxBytes);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static String fetchText(String url, String referer, String userAgent, int maxBytes,
                            int connectTimeout, int readTimeout, String[] finalUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = connect(url, "GET", referer, userAgent, connectTimeout, readTimeout, 6,
                    null, finalUrl);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("服务器返回 " + status);
            }
            return readText(connection, maxBytes);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Extracts {@code filename}/{@code filename*} from a Content-Disposition header. */
    static String headerFilename(String disposition) {
        if (disposition == null || disposition.isEmpty()) return "";
        String lower = disposition.toLowerCase(Locale.US);
        int extended = lower.indexOf("filename*=");
        if (extended >= 0) {
            String value = disposition.substring(extended + "filename*=".length()).trim();
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) value = value.substring(0, semicolon);
            value = value.trim();
            if (value.length() > 2 && (value.charAt(0) == '\'' || value.charAt(0) == '"')) {
                if (value.charAt(0) == '"') {
                    value = value.substring(1, value.length() - 1);
                }
            }
            int firstQuote = value.indexOf('\'');
            if (firstQuote >= 0) {
                int secondQuote = value.indexOf('\'', firstQuote + 1);
                if (secondQuote > firstQuote) value = value.substring(secondQuote + 1);
            }
            try {
                return URLDecoder.decode(value, "UTF-8").trim();
            } catch (Exception ignored) {
                return value.trim();
            }
        }
        int plain = lower.indexOf("filename=");
        if (plain >= 0) {
            String value = disposition.substring(plain + "filename=".length()).trim();
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) value = value.substring(0, semicolon);
            value = value.trim();
            if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                value = value.substring(1, value.length() - 1);
            }
            return value.trim();
        }
        return "";
    }

    static String nameFromUrl(String url) {
        if (url == null) return "";
        String value = url;
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    /** File name sanitizer shared by every download path. */
    static String sanitizeFileName(String rawName, String url) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) name = nameFromUrl(url);
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
                || lower.endsWith(".aac") || lower.endsWith(".mp4");
        if (!audio) name = name + ".mp3";
        return name;
    }

    static String unescapeHtml(String value) {
        if (value == null || value.isEmpty()) return "";
        String text = value;
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ");
        return text;
    }
}
