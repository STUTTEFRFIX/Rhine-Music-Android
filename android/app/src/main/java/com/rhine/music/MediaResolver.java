package com.rhine.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a user supplied link into a downloadable audio stream.
 *
 * <p>Two shapes are supported: a direct audio URL (known extension, or a
 * {@code Content-Type: audio/*} response) and a Bilibili video page/short link,
 * which is expanded through the public web API into a DASH audio URL. All work
 * happens on a background thread; the result is a single JSON payload emitted as
 * {@code rhine:resolve} so the UI never blocks on the network.</p>
 */
final class MediaResolver {

    private static final Pattern BV_PATTERN = Pattern.compile("(?i)\\b(BV[0-9A-Za-z]{10})\\b");
    private static final Pattern AID_PATTERN =
            Pattern.compile("(?i)(?:/video/av|/video/av|\\bav|[?&]aid=)(\\d+)");
    private static final Pattern P_PATTERN = Pattern.compile("[?&]p=(\\d+)");
    private static final String[] AUDIO_EXTENSIONS = {
            ".mp3", ".m4a", ".flac", ".ogg", ".aac", ".wav", ".opus",
    };
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    private MediaResolver() {
    }

    /** Never throws: failures become {@code {type:"error"}} payloads. */
    static JSONObject resolve(String rawUrl) {
        String url = normalizeLink(rawUrl);
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return error("audio", "只支持 http/https 链接，或 B 站 BV/av 号");
        }
        try {
            String host = hostOf(url);
            if (host.contains("bilibili.com") || host.contains("b23.tv")
                    || host.contains("bili2233.cn") || host.contains("bili22.cn")
                    || host.contains("bili33.cn")) {
                return resolveBilibili(url);
            }
            return resolveDirect(url);
        } catch (Exception failure) {
            String message = failure.getMessage();
            return error("audio", message == null || message.isEmpty() ? "链接解析失败" : message);
        }
    }

    /** Accepts bare BV/av ids and scheme-less Bilibili hosts from the share sheet. */
    private static String normalizeLink(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) return url;
        if (url.matches("(?i)^BV[0-9A-Za-z]{8,}$")) {
            return "https://www.bilibili.com/video/" + url;
        }
        if (url.matches("(?i)^av\\d+$")) {
            return "https://www.bilibili.com/video/" + url;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("b23.tv/") || lower.startsWith("www.bilibili.com/")
                || lower.startsWith("bilibili.com/") || lower.startsWith("m.bilibili.com/")
                || lower.startsWith("bili2233.cn/") || lower.startsWith("bili22.cn/")
                || lower.startsWith("bili33.cn/")) {
            return "https://" + url;
        }
        return url;
    }

    static JSONObject error(String kind, String message) {
        JSONObject json = done(kind);
        try {
            json.put("type", "error");
            json.put("error", message == null ? "链接解析失败" : message);
        } catch (Exception ignored) {
            // Literal keys only.
        }
        return json;
    }

    private static JSONObject done(String kind) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", "done");
            json.put("kind", kind);
        } catch (Exception ignored) {
            // Literal keys only.
        }
        return json;
    }

    // ------------------------------------------------------------- direct URLs

    private static JSONObject resolveDirect(String url) {
        if (hasAudioExtension(url)) {
            JSONObject json = done("audio");
            try {
                json.put("url", url);
                json.put("name", Net.sanitizeFileName(Net.nameFromUrl(url), url));
            } catch (Exception ignored) {
                // Literal keys only.
            }
            return json;
        }

        String[] finalUrl = new String[]{url};
        int status = -1;
        String contentType = "";
        String disposition = "";
        try {
            HttpURLConnection connection = Net.connect(url, "HEAD", Net.BILI_REFERER, Net.UA_DESKTOP,
                    CONNECT_TIMEOUT, READ_TIMEOUT, 5, null, finalUrl);
            try {
                status = connection.getResponseCode();
                contentType = header(connection, "Content-Type");
                disposition = header(connection, "Content-Disposition");
            } finally {
                connection.disconnect();
            }
        } catch (Exception headFailed) {
            status = -1;
        }
        if (status < 200 || status >= 300) {
            // Plenty of CDNs refuse HEAD; probe the first byte with GET instead.
            try {
                java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
                headers.put("Range", "bytes=0-0");
                HttpURLConnection connection = Net.connect(url, "GET", Net.BILI_REFERER,
                        Net.UA_DESKTOP, CONNECT_TIMEOUT, READ_TIMEOUT, 5, headers, finalUrl);
                try {
                    status = connection.getResponseCode();
                    contentType = header(connection, "Content-Type");
                    disposition = header(connection, "Content-Disposition");
                } finally {
                    connection.disconnect();
                }
            } catch (Exception getFailed) {
                return error("audio", "无法访问该链接：" + describe(getFailed));
            }
        }
        if (status == 405 || status == 403 || status < 200 || status >= 400) {
            return error("audio", "链接不可用（HTTP " + status + "）");
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (type.startsWith("audio/") || type.startsWith("application/ogg")
                || type.startsWith("application/octet-stream") && hasAudioExtension(finalUrl[0])) {
            String name = Net.headerFilename(disposition);
            if (name.isEmpty()) name = Net.nameFromUrl(finalUrl[0]);
            JSONObject json = done("audio");
            try {
                json.put("url", finalUrl[0]);
                json.put("name", Net.sanitizeFileName(name, finalUrl[0]));
                if (!type.isEmpty()) json.put("contentType", contentType);
            } catch (Exception ignored) {
                // Literal keys only.
            }
            return json;
        }
        String shown = type.isEmpty() ? "未知类型" : type;
        return error("audio", "这不是音频直链（" + shown + "）。请粘贴音频文件地址，或 B 站视频链接。");
    }

    // -------------------------------------------------------------- bilibili

    private static JSONObject resolveBilibili(String url) {
        String expanded = url;
        String host = hostOf(url);
        if (host.contains("b23.tv") || host.contains("bili2233.cn")
                || host.contains("bili22.cn") || host.contains("bili33.cn")) {
            String[] finalUrl = new String[]{url};
            HttpURLConnection connection = null;
            try {
                connection = Net.connect(url, "GET", Net.BILI_REFERER, Net.UA_DESKTOP,
                        CONNECT_TIMEOUT, READ_TIMEOUT, 8, null, finalUrl);
                connection.getResponseCode();
            } catch (Exception failure) {
                return error("bili", "短链展开失败：" + describe(failure));
            } finally {
                if (connection != null) connection.disconnect();
            }
            expanded = finalUrl[0];
        }

        String bvid = firstMatch(BV_PATTERN, expanded);
        long aid = parseLong(firstMatch(AID_PATTERN, expanded), 0);
        int page = parseInt(firstMatch(P_PATTERN, expanded), 1);
        if (bvid.isEmpty() && aid <= 0) {
            String lower = expanded.toLowerCase(Locale.US);
            if (lower.contains("/bangumi/") || lower.contains("/cheese/")
                    || lower.contains("bangumi/play")) {
                return error("bili", "番剧/课程链接暂不支持直接解析，请使用「B 站缓存导入」");
            }
            return error("bili", "链接中没有找到 BV 号或 av 号");
        }

        String viewUrl = "https://api.bilibili.com/x/web-interface/view?"
                + (bvid.isEmpty() ? "aid=" + aid : "bvid=" + bvid);
        JSONObject view = getJson(viewUrl);
        if (view == null) return error("bili", "无法连接 B 站接口，请检查网络");
        int code = view.optInt("code", -1);
        if (code != 0) {
            return error("bili", "B 站接口返回 " + code + "：" + view.optString("message")
                    + "。可改用「B 站缓存导入」");
        }
        JSONObject data = view.optJSONObject("data");
        if (data == null) return error("bili", "B 站接口没有返回视频信息");

        String title = Net.unescapeHtml(data.optString("title"));
        String cover = data.optString("pic");
        JSONObject owner = data.optJSONObject("owner");
        String artist = owner == null ? "" : Net.unescapeHtml(owner.optString("name"));
        long duration = data.optLong("duration", 0);
        String realBvid = data.optString("bvid", bvid);
        long realAid = data.optLong("aid", aid);

        long cid = 0;
        String part = "";
        long pageDuration = 0;
        JSONArray pages = data.optJSONArray("pages");
        if (pages != null && pages.length() > 0) {
            int index = Math.max(0, Math.min(page - 1, pages.length() - 1));
            JSONObject pageJson = pages.optJSONObject(index);
            if (pageJson != null) {
                cid = pageJson.optLong("cid", 0);
                part = Net.unescapeHtml(pageJson.optString("part"));
                pageDuration = pageJson.optLong("duration", 0);
            }
        }
        if (cid <= 0) cid = data.optLong("cid", 0);
        if (cid <= 0) return error("bili", "B 站接口没有返回 cid（视频可能已失效）");
        if (pageDuration > 0) duration = pageDuration;

        JSONObject play = playurl(realBvid, realAid, cid);
        if (play == null) return error("bili", "无法连接 B 站播放接口");
        int playCode = play.optInt("code", -1);
        if (playCode != 0) {
            String message = play.optString("message");
            if (message == null || message.isEmpty()) message = "code " + playCode;
            return error("bili", "B 站播放接口返回 " + playCode + "：" + message
                    + "。可能需要登录/受版权限制，建议使用「B 站缓存导入」");
        }
        JSONObject playData = play.optJSONObject("data");
        if (playData == null) return error("bili", "B 站播放接口没有返回媒体信息");
        String audioUrl = "";
        JSONObject dash = playData.optJSONObject("dash");
        if (dash != null) {
            JSONArray audio = dash.optJSONArray("audio");
            if (audio != null && audio.length() > 0) {
                JSONObject first = audio.optJSONObject(0);
                if (first != null) {
                    audioUrl = first.optString("baseUrl", "");
                    if (audioUrl.isEmpty()) audioUrl = first.optString("base_url", "");
                }
            }
        }
        String extension = ".m4a";
        if (audioUrl.isEmpty()) {
            JSONArray durl = playData.optJSONArray("durl");
            if (durl != null && durl.length() > 0) {
                JSONObject first = durl.optJSONObject(0);
                if (first != null) audioUrl = first.optString("url", "");
                extension = ".mp4";
            }
        }
        if (audioUrl.isEmpty()) {
            return error("bili", "B 站没有返回可用的音频流（可能需要登录或受版权限制）。"
                    + "建议使用「B 站缓存导入」");
        }

        String displayTitle = part.isEmpty() ? title : part;
        if (displayTitle.isEmpty()) displayTitle = "B 站音频";
        JSONObject json = done("bili");
        try {
            json.put("title", displayTitle);
            if (!title.isEmpty() && !title.equals(displayTitle)) json.put("albumTitle", title);
            json.put("artist", artist);
            json.put("cover", cover);
            json.put("duration", duration);
            json.put("url", audioUrl);
            json.put("referer", Net.BILI_REFERER);
            json.put("bvid", realBvid);
            json.put("cid", cid);
            json.put("name", Net.sanitizeFileName(
                    artist.isEmpty() ? displayTitle + extension
                            : displayTitle + " - " + artist + extension, null));
        } catch (Exception ignored) {
            // Literal keys only.
        }
        return json;
    }

    private static JSONObject playurl(String bvid, long aid, long cid) {
        JSONObject primary = null;
        if (bvid != null && !bvid.isEmpty()) {
            primary = getJson("https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                    + "&cid=" + cid + "&fnval=16&fourk=0&platform=html5");
            // platform=html5 often answers with a plain MP4 (durl). The pc-style
            // fallback below returns a DASH audio track, which is much smaller and
            // is what the cache importer can play as audio-only.
            if (hasDashAudio(primary)) return primary;
        }
        JSONObject fallback = getJson("https://api.bilibili.com/x/player/playurl?avid=" + aid
                + "&cid=" + cid + "&fnval=16");
        if (hasDashAudio(fallback)) return fallback;
        if (hasMedia(primary)) return primary;
        if (hasMedia(fallback)) return fallback;
        return fallback != null ? fallback : primary;
    }

    private static boolean hasDashAudio(JSONObject play) {
        if (play == null || play.optInt("code", -1) != 0) return false;
        JSONObject data = play.optJSONObject("data");
        if (data == null) return false;
        JSONObject dash = data.optJSONObject("dash");
        if (dash == null) return false;
        JSONArray audio = dash.optJSONArray("audio");
        if (audio == null || audio.length() == 0) return false;
        JSONObject first = audio.optJSONObject(0);
        return first != null && (!first.optString("baseUrl", "").isEmpty()
                || !first.optString("base_url", "").isEmpty());
    }

    private static boolean hasMedia(JSONObject play) {
        if (play == null || play.optInt("code", -1) != 0) return false;
        JSONObject data = play.optJSONObject("data");
        if (data == null) return false;
        if (hasDashAudio(play)) return true;
        JSONArray durl = data.optJSONArray("durl");
        if (durl != null && durl.length() > 0) {
            JSONObject first = durl.optJSONObject(0);
            return first != null && !first.optString("url", "").isEmpty();
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    private static JSONObject getJson(String url) {
        try {
            String text = Net.fetchText(url, Net.BILI_REFERER, Net.UA_DESKTOP, MAX_JSON_BYTES,
                    CONNECT_TIMEOUT, READ_TIMEOUT, null);
            return new JSONObject(text);
        } catch (Exception failure) {
            JSONObject error = new JSONObject();
            try {
                error.put("code", -999);
                error.put("message", "网络请求失败：" + describe(failure));
            } catch (Exception ignored) {
                // Literal keys only.
            }
            return error;
        }
    }

    private static boolean hasAudioExtension(String url) {
        String lower = url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        for (String extension : AUDIO_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            String value = new java.net.URL(url).getHost();
            return value == null ? "" : value.toLowerCase(Locale.US);
        } catch (Exception invalid) {
            return "";
        }
    }

    private static String header(HttpURLConnection connection, String name) {
        String value = connection.getHeaderField(name);
        return value == null ? "" : value;
    }

    private static String firstMatch(Pattern pattern, String value) {
        if (value == null) return "";
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String describe(Exception failure) {
        if (failure == null) return "未知错误";
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }
}
