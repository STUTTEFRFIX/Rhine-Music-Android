package com.rhine.music;

import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Loopback-only HTTP service for the bundled single-page app and the local music
 * API. It mirrors the routes of the Windows local server so the web frontend is
 * byte-for-byte the same application; only the storage back end differs.
 */
public final class LocalServer {

    private static final String TAG = "RhineServer";
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final Map<String, String> MIME = new HashMap<String, String>();

    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("htm", "text/html; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("js", "text/javascript; charset=utf-8");
        MIME.put("mjs", "text/javascript; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("webmanifest", "application/manifest+json");
        MIME.put("webp", "image/webp");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("txt", "text/plain; charset=utf-8");
        MIME.put("pdf", "application/pdf");
        MIME.put("xml", "application/xml; charset=utf-8");
        MIME.put("woff2", "font/woff2");
        MIME.put("woff", "font/woff");
        MIME.put("ttf", "font/ttf");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("m4a", "audio/mp4");
        MIME.put("mp4", "audio/mp4");
        MIME.put("ogg", "audio/ogg");
        MIME.put("opus", "audio/ogg");
        MIME.put("flac", "audio/flac");
        MIME.put("wav", "audio/wav");
        MIME.put("glb", "model/gltf-binary");
        MIME.put("gltf", "model/gltf+json");
        MIME.put("wasm", "application/wasm");
    }

    private final AssetManager assets;
    private final MusicLibrary library;
    private final PlaylistStore playlists;
    private final ServerSocket socket;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    public LocalServer(AssetManager assets, MusicLibrary library, PlaylistStore playlists) throws IOException {
        this.assets = assets;
        this.library = library;
        this.playlists = playlists;
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket bound;
        try {
            bound = new ServerSocket(8766, 64, loopback);
        } catch (IOException busy) {
            bound = new ServerSocket(0, 64, loopback);
        }
        this.socket = bound;
    }

    public int port() {
        return socket.getLocalPort();
    }

    public void start() {
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!socket.isClosed()) {
                    try {
                        final Socket client = socket.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(client);
                            }
                        });
                    } catch (IOException closed) {
                        return;
                    } catch (RejectedExecutionException rejected) {
                        return;
                    }
                }
            }
        }, "rhine-http");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public void close() {
        pool.shutdownNow();
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(20000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            if (lines.length == 0) return;
            String[] parts = lines[0].split(" ");
            if (parts.length < 2) {
                writeJson(out, 400, error("请求格式不正确"));
                return;
            }
            String method = parts[0];
            String target = parts[1];
            Map<String, String> headers = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                            lines[i].substring(colon + 1).trim());
                }
            }
            int query = target.indexOf('?');
            String queryString = "";
            if (query >= 0) {
                queryString = target.substring(query + 1);
                target = target.substring(0, query);
            }
            String path;
            try {
                path = URLDecoder.decode(target, "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException badPath) {
                writeJson(out, 400, error("无效路径"));
                return;
            }
            if (path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0) {
                writeJson(out, 403, error("无效路径"));
                return;
            }
            int contentLength = 0;
            try {
                contentLength = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0");
            } catch (NumberFormatException ignored) {
                contentLength = 0;
            }
            byte[] body = new byte[0];
            if (contentLength > 0) {
                if (contentLength > MAX_BODY_BYTES) {
                    writeJson(out, 413, error("请求体过大"));
                    return;
                }
                body = readBody(in, contentLength);
            }
            if (path.startsWith("/api/")) {
                handleApi(method, path, queryString, headers, body, out);
            } else {
                serveStatic(method, path, out);
            }
        } catch (IOException ignored) {
            // Client disappeared; nothing useful to recover.
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    // ------------------------------------------------------------------- API

    private void handleApi(String method, String path, String query, Map<String, String> headers,
                           byte[] body, OutputStream out) throws IOException {
        boolean get = "GET".equals(method) || "HEAD".equals(method);
        if (path.equals("/api/health") && get) {
            JSONObject json = new JSONObject();
            try {
                json.put("service", "rhine-local-music");
                json.put("platform", "android");
            } catch (Exception ignored) {
                // Literal keys only.
            }
            writeJson(out, 200, json);
            return;
        }
        if (path.equals("/api/library") && get) {
            writeJson(out, 200, library.snapshot());
            return;
        }
        if (path.equals("/api/library/scan") && "POST".equals(method)) {
            library.requestScan();
            writeJson(out, 202, library.snapshot());
            return;
        }
        if ((path.equals("/api/config") || path.equals("/api/library/config"))) {
            if (get) {
                writeJson(out, 200, library.configJson());
                return;
            }
            if ("POST".equals(method)) {
                writeJson(out, 200, library.updateConfig(parseJson(body)));
                return;
            }
        }
        if (path.equals("/api/genre-rules")) {
            if (get) {
                writeJson(out, 200, library.genreRulesJson());
                return;
            }
            if ("POST".equals(method)) {
                writeJson(out, 200, library.updateGenreRules(parseJson(body)));
                return;
            }
        }
        if (path.equals("/api/library/enrich") && "POST".equals(method)) {
            writeJson(out, 400, error("在线资料库尚未配置：手机版音乐仍可本地播放。"));
            return;
        }
        if (path.equals("/api/library/introductions") && "POST".equals(method)) {
            writeJson(out, 202, library.snapshot());
            return;
        }
        if (path.equals("/api/playlists")) {
            if (get) {
                JSONObject json = new JSONObject();
                try {
                    json.put("version", 1);
                    json.put("playlists", playlists.list());
                } catch (Exception ignored) {
                    // Literal keys only.
                }
                writeJson(out, 200, json);
                return;
            }
            if ("POST".equals(method)) {
                JSONObject value = parseJson(body);
                String name = value == null ? "" : value.optString("name", "").trim();
                if (name.isEmpty()) {
                    writeJson(out, 400, error("name 必须是文本"));
                    return;
                }
                JSONObject json = new JSONObject();
                try {
                    json.put("playlist", playlists.create(name));
                } catch (Exception ignored) {
                    // Literal keys only.
                }
                writeJson(out, 201, json);
                return;
            }
        }
        if (path.startsWith("/api/playlists/")) {
            String rest = path.substring("/api/playlists/".length());
            String[] segments = rest.split("/");
            String playlistId = segments.length > 0 ? segments[0] : "";
            if (segments.length == 1) {
                if ("PATCH".equals(method)) {
                    JSONObject value = parseJson(body);
                    String name = value == null ? "" : value.optString("name", "").trim();
                    if (name.isEmpty()) {
                        writeJson(out, 400, error("name 必须是文本"));
                        return;
                    }
                    JSONObject playlist = playlists.rename(playlistId, name);
                    if (playlist == null) {
                        writeJson(out, 404, error("歌单不存在"));
                        return;
                    }
                    JSONObject json = new JSONObject();
                    try {
                        json.put("playlist", playlist);
                    } catch (Exception ignored) {
                        // Literal keys only.
                    }
                    writeJson(out, 200, json);
                    return;
                }
                if ("DELETE".equals(method)) {
                    if (!playlists.delete(playlistId)) {
                        writeJson(out, 404, error("歌单不存在"));
                        return;
                    }
                    JSONObject json = new JSONObject();
                    try {
                        json.put("ok", true);
                    } catch (Exception ignored) {
                        // Literal keys only.
                    }
                    writeJson(out, 200, json);
                    return;
                }
            }
            if (segments.length == 2 && "tracks".equals(segments[1])) {
                JSONObject value = parseJson(body);
                if ("POST".equals(method)) {
                    JSONArray ids = value == null ? null : value.optJSONArray("trackIds");
                    if (ids == null) {
                        writeJson(out, 400, error("trackIds 必须是数组"));
                        return;
                    }
                    JSONObject summary = playlists.addTracks(playlistId, ids);
                    JSONObject playlist = playlists.find(playlistId);
                    if (summary == null || playlist == null) {
                        writeJson(out, 404, error("歌单不存在"));
                        return;
                    }
                    JSONObject json = new JSONObject();
                    try {
                        json.put("added", summary.optInt("added"));
                        json.put("skipped", summary.optInt("skipped"));
                        json.put("playlist", playlist);
                    } catch (Exception ignored) {
                        // Literal keys only.
                    }
                    writeJson(out, 200, json);
                    return;
                }
                if ("PUT".equals(method)) {
                    JSONArray ids = value == null ? null : value.optJSONArray("itemIds");
                    if (ids == null) {
                        writeJson(out, 400, error("itemIds 必须是数组"));
                        return;
                    }
                    JSONObject playlist = playlists.reorder(playlistId, ids);
                    if (playlist == null) {
                        writeJson(out, 404, error("歌单不存在"));
                        return;
                    }
                    JSONObject json = new JSONObject();
                    try {
                        json.put("playlist", playlist);
                    } catch (Exception ignored) {
                        // Literal keys only.
                    }
                    writeJson(out, 200, json);
                    return;
                }
            }
            if (segments.length == 3 && "tracks".equals(segments[1]) && "DELETE".equals(method)) {
                JSONObject playlist = playlists.removeTrack(playlistId, segments[2]);
                if (playlist == null) {
                    writeJson(out, 404, error("歌单不存在"));
                    return;
                }
                JSONObject json = new JSONObject();
                try {
                    json.put("playlist", playlist);
                } catch (Exception ignored) {
                    // Literal keys only.
                }
                writeJson(out, 200, json);
                return;
            }
        }
        if (path.startsWith("/api/audio/")) {
            String trackId = path.substring("/api/audio/".length());
            if (!get || trackId.isEmpty()) {
                writeJson(out, 405, error("不支持的请求"));
                return;
            }
            serveAudio(headers, trackId, out, "HEAD".equals(method));
            return;
        }
        if (path.startsWith("/api/artwork/")) {
            String albumId = path.substring("/api/artwork/".length());
            if (!get) {
                writeJson(out, 405, error("不支持的请求"));
                return;
            }
            serveArtwork(albumId, out, "HEAD".equals(method));
            return;
        }
        if (path.startsWith("/api/lyrics/")) {
            String trackId = path.substring("/api/lyrics/".length());
            if (!get) {
                writeJson(out, 405, error("不支持的请求"));
                return;
            }
            writeJson(out, 200, library.lyrics(trackId));
            return;
        }
        if (path.equals("/api/foobar/status") && get) {
            JSONObject json = new JSONObject();
            try {
                json.put("configured", false);
                json.put("connected", false);
                json.put("note", "手机版不提供 foobar2000 / Beefweb 连接。");
            } catch (Exception ignored) {
                // Literal keys only.
            }
            writeJson(out, 200, json);
            return;
        }
        writeJson(out, 404, error("API 不存在或请求方法不支持"));
    }

    private void serveAudio(Map<String, String> headers, String trackId, OutputStream out, boolean headOnly) throws IOException {
        Map<String, String> requestHeaders = headers;
        MusicLibrary.Track track = library.track(trackId);
        if (track == null) {
            writeJson(out, 404, error("索引中没有此文件"));
            return;
        }
        long total = track.size;
        long start = 0;
        long end = total > 0 ? total - 1 : 0;
        boolean partial = false;
        String range = requestHeaders.get("range");
        if (range != null && total > 0) {
            long[] parsed = parseRange(range, total);
            if (parsed == null) {
                StringBuilder response = new StringBuilder();
                response.append("HTTP/1.1 416 Range Not Satisfiable\r\n");
                response.append("Content-Range: bytes */").append(total).append("\r\n");
                response.append("Content-Length: 0\r\nConnection: close\r\n\r\n");
                out.write(response.toString().getBytes("UTF-8"));
                out.flush();
                return;
            }
            start = parsed[0];
            end = parsed[1];
            partial = true;
        }
        long length = total > 0 ? end - start + 1 : 0;
        StringBuilder response = new StringBuilder(256);
        response.append("HTTP/1.1 ").append(partial ? 206 : 200).append(partial ? " Partial Content" : " OK").append("\r\n");
        response.append("Content-Type: ").append(mimeForAudio(track)).append("\r\n");
        response.append("Accept-Ranges: bytes\r\n");
        response.append("Cache-Control: private, no-cache\r\n");
        response.append("X-Content-Type-Options: nosniff\r\n");
        response.append("Connection: close\r\n");
        if (total > 0) {
            if (partial) response.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
            response.append("Content-Length: ").append(length).append("\r\n");
        } else {
            response.append("Content-Length: 0\r\n");
        }
        response.append("\r\n");
        out.write(response.toString().getBytes("UTF-8"));
        if (headOnly || length <= 0) {
            out.flush();
            return;
        }
        MusicLibrary.AudioSource source = library.openAudio(trackId, start);
        try {
            byte[] buffer = new byte[65536];
            long remaining = length;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int count = source.stream.read(buffer, 0, want);
                if (count <= 0) break;
                out.write(buffer, 0, count);
                remaining -= count;
            }
            out.flush();
        } finally {
            source.close();
        }
    }

    private void serveArtwork(String albumId, OutputStream out, boolean headOnly) throws IOException {
        File artwork = library.artworkFor(albumId);
        if (artwork == null || !artwork.isFile()) {
            writeJson(out, 404, error("此专辑没有封面"));
            return;
        }
        byte[] bytes = MusicLibrary.readBytes(artwork);
        if (bytes == null) {
            writeJson(out, 404, error("此专辑没有封面"));
            return;
        }
        StringBuilder response = new StringBuilder(256);
        response.append("HTTP/1.1 200 OK\r\n");
        response.append("Content-Type: ").append(mimeOf(artwork.getName())).append("\r\n");
        response.append("Cache-Control: private, max-age=3600\r\n");
        response.append("Content-Length: ").append(bytes.length).append("\r\n");
        response.append("Connection: close\r\n\r\n");
        out.write(response.toString().getBytes("UTF-8"));
        if (!headOnly) out.write(bytes);
        out.flush();
    }

    private static long[] parseRange(String header, long size) {
        if (header == null) return null;
        String value = header.trim();
        if (!value.startsWith("bytes=")) return null;
        value = value.substring("bytes=".length());
        if (value.contains(",")) return null;
        int dash = value.indexOf('-');
        if (dash < 0) return null;
        try {
            String first = value.substring(0, dash).trim();
            String second = value.substring(dash + 1).trim();
            long start;
            long end;
            if (first.isEmpty()) {
                long suffix = Long.parseLong(second);
                if (suffix <= 0) return null;
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(first);
                end = second.isEmpty() ? size - 1 : Long.parseLong(second);
                if (start >= size || end < start) return null;
                end = Math.min(end, size - 1);
            }
            return new long[]{start, end};
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    // ---------------------------------------------------------------- static

    private void serveStatic(String method, String path, OutputStream out) throws IOException {
        boolean headOnly = "HEAD".equals(method);
        if (!"GET".equals(method) && !headOnly) {
            writeJson(out, 405, error("只接受 GET/HEAD"));
            return;
        }
        if (path.isEmpty() || "/".equals(path)) path = "/index.html";
        else if (path.endsWith("/")) path = path + "index.html";
        byte[] bytes;
        try {
            InputStream in = assets.open("www" + path);
            bytes = MusicLibrary.readAll(in);
            in.close();
        } catch (IOException missing) {
            byte[] message = ("Not found: " + path).getBytes("UTF-8");
            StringBuilder response = new StringBuilder(128);
            response.append("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\n");
            response.append("Content-Length: ").append(message.length).append("\r\nConnection: close\r\n\r\n");
            out.write(response.toString().getBytes("UTF-8"));
            if (!headOnly) out.write(message);
            out.flush();
            return;
        }
        StringBuilder response = new StringBuilder(256);
        response.append("HTTP/1.1 200 OK\r\n");
        response.append("Content-Type: ").append(mimeOf(path)).append("\r\n");
        response.append("Content-Length: ").append(bytes.length).append("\r\n");
        response.append("Cache-Control: ").append(path.startsWith("/assets/") ? "public, max-age=86400" : "no-cache").append("\r\n");
        response.append("X-Content-Type-Options: nosniff\r\n");
        response.append("Connection: close\r\n\r\n");
        out.write(response.toString().getBytes("UTF-8"));
        if (!headOnly) out.write(bytes);
        out.flush();
    }

    // ---------------------------------------------------------------- wire

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        int matched = 0;
        int value;
        while ((value = in.read()) != -1) {
            buffer.write(value);
            if (buffer.size() > MAX_HEADER_BYTES) return null;
            if (value == '\n' && (matched == 2 || matched == 3)) return buffer.toString("UTF-8");
            if (value == '\r') matched = matched == 0 || matched == 2 ? matched + 1 : 2;
            else if (value == '\n') matched = matched == 1 ? 2 : 0;
            else matched = 0;
        }
        return buffer.size() == 0 ? null : buffer.toString("UTF-8");
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(body, offset, length - offset);
            if (count <= 0) break;
            offset += count;
        }
        return body;
    }

    private static JSONObject parseJson(byte[] body) {
        if (body == null || body.length == 0) return new JSONObject();
        try {
            return new JSONObject(new String(body, "UTF-8"));
        } catch (Exception invalid) {
            return null;
        }
    }

    private static JSONObject error(String message) {
        JSONObject json = new JSONObject();
        try {
            json.put("error", message);
        } catch (Exception ignored) {
            // Literal keys only.
        }
        return json;
    }

    private static void writeJson(OutputStream out, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes("UTF-8");
        StringBuilder response = new StringBuilder(192);
        response.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        response.append("Content-Type: application/json; charset=utf-8\r\n");
        response.append("Cache-Control: no-store\r\n");
        response.append("Access-Control-Allow-Origin: *\r\n");
        response.append("Content-Length: ").append(bytes.length).append("\r\n");
        response.append("Connection: close\r\n\r\n");
        out.write(response.toString().getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private static String mimeForAudio(MusicLibrary.Track track) {
        String extension = track.format == null ? "" : track.format.toLowerCase(Locale.US);
        String type = MIME.get(extension);
        return type != null ? type : "application/octet-stream";
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
        String type = MIME.get(extension);
        return type != null ? type : "application/octet-stream";
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 202: return "Accepted";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            default: return "Error";
        }
    }
}
