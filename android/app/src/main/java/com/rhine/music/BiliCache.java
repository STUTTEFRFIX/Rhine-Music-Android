package com.rhine.music;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Android Bilibili client cache importer.
 *
 * <p>The client stores each downloaded video as
 * {@code <avid>/<cid>/{entry.json, 0/audio.m4s}} (and
 * {@code bangumi/<season>/<ep>/...} for anime). This class turns those folders
 * into a private, playable library:</p>
 *
 * <ol>
 *   <li>collect every {@code entry.json} below a staging directory,</li>
 *   <li>transmux {@code audio.m4s} (fragmented MP4/AAC) into a plain
 *       {@code .m4a} with {@link MediaExtractor} + {@link MediaMuxer}, falling
 *       back to a byte copy when the extractor cannot parse it,</li>
 *   <li>download the cover (best effort) and write a sidecar JSON so
 *       {@link MusicLibrary} can index the album without touching the network.</li>
 * </ol>
 *
 * <p>The conversion key is the hash of the original cache path plus its size,
 * so importing the same cache twice reuses the existing output instead of
 * converting it again.</p>
 */
final class BiliCache {

    private static final String TAG = "RhineBili";
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ENTRIES = 2000;
    private static final String[] CACHE_CANDIDATES = {
            "/data/media/0/Android/data/tv.danmaku.bili/download",
            "/data/media/0/Android/data/com.bilibili.app.in/download",
            "/data/media/0/Android/data/tv.danmaku.bilibilihd/download",
            "/storage/emulated/0/Android/data/tv.danmaku.bili/download",
            "/storage/emulated/0/Android/data/com.bilibili.app.in/download",
            "/storage/emulated/0/Android/data/tv.danmaku.bilibilihd/download",
            "/sdcard/Android/data/tv.danmaku.bili/download",
            "/sdcard/Android/data/com.bilibili.app.in/download",
    };

    private BiliCache() {
    }

    // ------------------------------------------------------------- public API

    static final class RootCheck {
        public boolean granted;
        public String message = "";
    }

    static final class Result {
        public int total;
        public int copied;
        public int converted;
        public int skipped;
        public int failed;
        public String message = "";
    }

    interface Listener {
        void onProgress(int copied, int total, int converted, String message);
    }

    /** True when a {@code su} binary is present. Does not execute it. */
    static boolean suBinaryExists() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/su/bin/su", "/system/sbin/su", "/vendor/bin/su",
        };
        for (String path : paths) {
            try {
                if (new File(path).exists()) return true;
            } catch (Exception ignored) {
                // Keep probing.
            }
        }
        return false;
    }

    /** Runs {@code su -c id}; only output containing {@code uid=0} counts. */
    static RootCheck checkRoot() {
        RootCheck check = new RootCheck();
        ShellResult result = runSu("id", 6000);
        if (result.code == -1) {
            check.granted = false;
            check.message = "设备没有 su 命令（未 Root，或授权管理器不可用）。"
                    + "请改用「选择缓存目录」导入。";
            return check;
        }
        String output = result.output == null ? "" : result.output;
        if (output.contains("uid=0")) {
            check.granted = true;
            check.message = "已获得 Root 权限";
        } else {
            check.granted = false;
            String detail = output.trim();
            if (detail.length() > 120) detail = detail.substring(0, 120);
            check.message = detail.isEmpty()
                    ? "su 没有返回 uid=0：权限被拒绝。请在 Root 管理器中允许本应用，或改用「选择缓存目录」。"
                    : "su 授权失败：" + detail + "。可改用「选择缓存目录」导入。";
        }
        return check;
    }

    /** Locates Bilibili download folders through {@code su}; empty without root. */
    static List<String> findCacheDirs() {
        List<String> found = new ArrayList<String>();
        for (String candidate : CACHE_CANDIDATES) {
            ShellResult result = runSu("test -d " + quote(candidate) + " && echo yes", 6000);
            if (result.code == 0 && result.output.contains("yes") && !found.contains(candidate)) {
                found.add(candidate);
            }
        }
        ShellResult glob = runSu("ls -d /data/media/0/Android/data/*bili*/download "
                + "/storage/emulated/0/Android/data/*bili*/download 2>/dev/null", 8000);
        if (glob.output != null) {
            for (String line : glob.output.split("\\r?\\n")) {
                String path = line.trim();
                if (path.startsWith("/") && !found.contains(path)) found.add(path);
            }
        }
        return found;
    }

    /** Package names derived from cache paths, for {@code biliStatus()}. */
    static JSONArray packagesJson(List<String> dirs) {
        JSONArray array = new JSONArray();
        Set<String> seen = new HashSet<String>();
        if (dirs == null) return array;
        for (String dir : dirs) {
            int marker = dir.indexOf("/Android/data/");
            if (marker < 0) continue;
            String rest = dir.substring(marker + "/Android/data/".length());
            int slash = rest.indexOf('/');
            String packageName = slash >= 0 ? rest.substring(0, slash) : rest;
            if (!packageName.isEmpty() && seen.add(packageName)) array.put(packageName);
        }
        return array;
    }

    /** Copies the cache roots into the app private inbox, then chowns them. */
    static String stageRootDirectories(List<String> dirs, File inbox, int uid) {
        if (dirs == null || dirs.isEmpty()) return "没有可导入的 B 站缓存目录";
        StringBuilder command = new StringBuilder();
        command.append("rm -rf ").append(quote(inbox.getAbsolutePath()))
                .append(" && mkdir -p ").append(quote(inbox.getAbsolutePath()));
        for (String dir : dirs) {
            String packageName = packageNameOf(dir);
            File destination = new File(inbox, packageName);
            command.append(" && mkdir -p ").append(quote(destination.getAbsolutePath()))
                    .append(" && cp -r ").append(quote(dir + "/.")).append(" ")
                    .append(quote(destination.getAbsolutePath() + "/"));
        }
        command.append(" && chown -R ").append(uid).append(":").append(uid)
                .append(" ").append(quote(inbox.getAbsolutePath()));
        ShellResult copy = runSu(command.toString(), 180000);
        if (copy.code != 0) {
            String detail = copy.output == null ? "" : copy.output.trim();
            if (detail.length() > 200) detail = detail.substring(0, 200);
            return "复制 B 站缓存失败" + (detail.isEmpty() ? "" : "：" + detail);
        }
        // Files created by root may carry a root SELinux label; best effort only.
        runSu("restorecon -R " + quote(inbox.getAbsolutePath()) + " 2>/dev/null", 30000);
        return null;
    }

    // ------------------------------------------------------------- SAF import

    /**
     * Recursively copies only {@code entry.json} and {@code audio.m4s} from a
     * SAF tree into {@code destination}, preserving relative paths.
     *
     * @return number of files copied
     */
    static int copySafTree(Context context, Uri treeUri, File destination) throws IOException {
        if (context == null || treeUri == null) return 0;
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception invalid) {
            throw new IOException("无法读取所选目录");
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建导入目录");
        }
        return copySafDirectory(context, treeUri, rootId, destination, 0);
    }

    private static int copySafDirectory(Context context, Uri treeUri, String documentId,
                                        File destination, int depth) throws IOException {
        if (depth > MAX_DEPTH) return 0;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        Cursor cursor = null;
        int copied = 0;
        List<String> directories = new ArrayList<String>();
        List<String> directoryNames = new ArrayList<String>();
        List<String> fileIds = new ArrayList<String>();
        List<String> fileNames = new ArrayList<String>();
        try {
            cursor = context.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
            }, null, null, null);
            if (cursor == null) return 0;
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.isNull(1) ? "" : cursor.getString(1);
                String mime = cursor.isNull(2) ? "" : cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    directories.add(id);
                    directoryNames.add(name);
                } else {
                    fileIds.add(id);
                    fileNames.add(name);
                }
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        for (int i = 0; i < fileIds.size(); i++) {
            String name = fileNames.get(i);
            if (!"entry.json".equalsIgnoreCase(name) && !"audio.m4s".equalsIgnoreCase(name)) continue;
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileIds.get(i));
            File target = new File(destination, name);
            if (copyUri(context.getContentResolver(), documentUri, target)) copied++;
        }
        for (int i = 0; i < directories.size(); i++) {
            String name = directoryNames.get(i);
            if (name == null || name.isEmpty()) name = "dir";
            File child = new File(destination, sanitizeSegment(name));
            if (!child.isDirectory() && !child.mkdirs()) continue;
            copied += copySafDirectory(context, treeUri, directories.get(i), child, depth + 1);
        }
        return copied;
    }

    private static boolean copyUri(ContentResolver resolver, Uri uri, File target) {
        InputStream input = null;
        FileOutputStream output = null;
        File temp = new File(target.getAbsolutePath() + ".part");
        try {
            input = resolver.openInputStream(uri);
            if (input == null) return false;
            output = new FileOutputStream(temp);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
            output.close();
            output = null;
            if (target.exists() && !target.delete()) return false;
            return temp.renameTo(target);
        } catch (Exception failure) {
            Log.w(TAG, "cannot copy " + uri, failure);
            return false;
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            if (output != null) try { output.close(); } catch (IOException ignored) { }
            if (temp.exists() && !target.exists()) temp.delete();
        }
    }

    // ---------------------------------------------------------------- ingest

    /** Recursively deletes the contents of a staging directory (best effort). */
    static void clearDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                clearDirectory(child);
                if (!child.delete() && child.exists()) Log.w(TAG, "cannot delete " + child);
            } else if (!child.delete() && child.exists()) {
                Log.w(TAG, "cannot delete " + child);
            }
        }
    }

    static int countEntries(File root) {
        List<File> entries = collectEntries(root);
        return entries.size();
    }

    static boolean hasEntries(File root) {
        return countEntries(root) > 0;
    }

    /** Synchronous; must run on a background thread. */
    static Result ingest(File root, MusicLibrary library, Listener listener) {
        Result result = new Result();
        List<File> entryFiles = collectEntries(root);
        result.total = entryFiles.size();
        if (result.total == 0) {
            result.message = "没有找到 B 站缓存条目（entry.json）。请确认选择的目录包含 "
                    + "download/<avid>/<cid>/entry.json。";
            return result;
        }
        if (listener != null) {
            listener.onProgress(0, result.total, 0, "发现 " + result.total + " 个缓存条目，开始转换");
        }
        File mediaDir = library.biliMediaDir();
        if (!mediaDir.isDirectory() && !mediaDir.mkdirs()) {
            result.message = "无法创建转换输出目录";
            return result;
        }
        Map<String, String> coverCache = new HashMap<String, String>();
        int[] coverFailures = new int[]{0};
        int processed = 0;
        for (File entryFile : entryFiles) {
            processed++;
            Entry entry = parseEntry(entryFile);
            File entryDir = entryFile.getParentFile();
            File audio = findAudio(entryDir);
            String display = displayName(entry);
            if (audio == null) {
                result.skipped++;
                if (listener != null) {
                    listener.onProgress(result.copied, result.total, result.converted,
                            "跳过（没有 audio.m4s）：" + display);
                }
                continue;
            }
            String key = keyFor(audio);
            File target = new File(mediaDir, key + ".m4a");
            File metaFile = new File(mediaDir, key + ".json");
            JSONObject oldMeta = readJson(metaFile);
            long oldDuration = oldMeta == null ? 0 : oldMeta.optLong("durationMs", 0);
            boolean existed = target.isFile() && target.length() > 0;
            long modifiedBefore = target.lastModified();
            long duration = prepareAudio(audio, target);
            if (duration < 0) {
                result.failed++;
                Log.w(TAG, "cannot decode " + audio.getAbsolutePath());
                if (listener != null) {
                    listener.onProgress(result.copied, result.total, result.converted,
                            "跳过（无法转换）：" + display);
                }
                continue;
            }
            boolean convertedNow = !existed || target.lastModified() != modifiedBefore;
            String artworkKey = oldMeta == null ? "" : oldMeta.optString("artworkKey", "");
            if (artworkKey.isEmpty() && !entry.cover.isEmpty()) {
                artworkKey = coverCache.get(entry.cover);
                if (artworkKey == null) {
                    artworkKey = downloadCover(entry.cover, library, coverFailures);
                    coverCache.put(entry.cover, artworkKey);
                }
            }
            if (duration <= 0 && oldDuration > 0) duration = oldDuration;
            writeMeta(metaFile, entry, entryFile, audio, key, duration, artworkKey);
            result.copied++;
            if (convertedNow) result.converted++;
            if (listener != null) {
                listener.onProgress(result.copied, result.total, result.converted,
                        "已导入 " + display + "（" + processed + "/" + result.total + "）");
            }
        }
        result.message = "导入完成：共 " + result.total + " 个缓存条目，成功 " + result.copied
                + " 个（本次转换 " + result.converted + " 个），跳过 " + result.skipped
                + " 个，失败 " + result.failed + " 个。";
        return result;
    }

    // -------------------------------------------------------------- internals

    private static List<File> collectEntries(File root) {
        List<File> files = new ArrayList<File>();
        if (root != null) collectEntries(root, files, 0);
        return files;
    }

    private static void collectEntries(File directory, List<File> out, int depth) {
        if (directory == null || depth > MAX_DEPTH || out.size() >= MAX_ENTRIES) return;
        File[] children;
        try {
            children = directory.listFiles();
        } catch (Exception denied) {
            return;
        }
        if (children == null) return;
        for (File child : children) {
            if (out.size() >= MAX_ENTRIES) return;
            if (child.isDirectory()) {
                collectEntries(child, out, depth + 1);
            } else if ("entry.json".equalsIgnoreCase(child.getName()) && child.length() > 0) {
                out.add(child);
            }
        }
    }

    private static Entry parseEntry(File entryFile) {
        Entry entry = new Entry();
        entry.entryFile = entryFile;
        File dir = entryFile.getParentFile();
        JSONObject json = readJson(entryFile);
        if (json == null) return entry;
        entry.title = Net.unescapeHtml(json.optString("title", "")).trim();
        entry.cover = json.optString("cover", "");
        if (entry.cover.isEmpty()) {
            JSONObject coverObject = json.optJSONObject("cover");
            if (coverObject != null) entry.cover = coverObject.optString("url", "");
        }
        entry.owner = Net.unescapeHtml(json.optString("owner_name", "")).trim();
        if (entry.owner.isEmpty()) {
            JSONObject owner = json.optJSONObject("owner");
            if (owner != null) entry.owner = Net.unescapeHtml(owner.optString("name", "")).trim();
        }
        entry.mediaType = json.optInt("media_type", 0);
        entry.hasDashAudio = json.optBoolean("has_dash_audio", true);
        JSONObject page = json.optJSONObject("page_data");
        if (page != null) {
            entry.part = Net.unescapeHtml(page.optString("part", "")).trim();
            entry.cid = page.optLong("cid", 0);
            entry.avid = page.optString("vid", "").trim();
            entry.order = page.optInt("page", 0);
        }
        if (entry.avid.isEmpty()) entry.avid = json.optString("avid", "").trim();
        if (entry.cid <= 0) entry.cid = json.optLong("cid", 0);
        if (entry.title.isEmpty()) entry.title = entry.part;
        if (entry.part.isEmpty()) entry.part = entry.title;
        if (entry.owner.isEmpty()) entry.owner = "B站";
        // bangumi/<season_id>/<ep_id>/entry.json
        String season = "";
        String episode = "";
        if (dir != null) {
            File seasonDir = dir.getParentFile();
            File bangumiDir = seasonDir == null ? null : seasonDir.getParentFile();
            if (bangumiDir != null && "bangumi".equalsIgnoreCase(bangumiDir.getName())) {
                season = seasonDir.getName();
                episode = dir.getName();
            }
        }
        entry.seasonId = season;
        entry.episodeId = episode;
        if (!entry.avid.isEmpty()) entry.groupId = "avid:" + entry.avid;
        else if (!season.isEmpty()) entry.groupId = "season:" + season;
        else entry.groupId = "dir:" + MusicLibrary.sha256Hex(
                dir == null ? entryFile.getAbsolutePath() : dir.getAbsolutePath()).substring(0, 16);
        if (entry.order <= 0 && !episode.isEmpty()) entry.order = numericSuffix(episode);
        return entry;
    }

    private static File findAudio(File entryDir) {
        if (entryDir == null) return null;
        File direct = new File(entryDir, "audio.m4s");
        if (isUsable(direct)) return direct;
        File zero = new File(entryDir, "0/audio.m4s");
        if (isUsable(zero)) return zero;
        File[] children = entryDir.listFiles();
        if (children == null) return null;
        List<File> directories = new ArrayList<File>();
        for (File child : children) {
            if (child.isDirectory()) directories.add(child);
        }
        Collections.sort(directories, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return MusicLibrary.naturalCompare(a.getName(), b.getName());
            }
        });
        for (File directory : directories) {
            File candidate = new File(directory, "audio.m4s");
            if (isUsable(candidate)) return candidate;
        }
        return null;
    }

    private static boolean isUsable(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    private static String keyFor(File audio) {
        return MusicLibrary.sha256Hex(audio.getAbsolutePath() + "|" + audio.length()).substring(0, 24);
    }

    /**
     * Ensures {@code target} contains a playable m4a.
     *
     * @return duration in milliseconds, or -1 when neither remux nor copy works
     */
    private static long prepareAudio(File source, File target) {
        if (isUsable(target)) {
            long duration = readDuration(target);
            if (duration >= 0) return duration;
            Log.w(TAG, "cached m4a is unreadable, rebuilding " + target.getName());
            if (!target.delete()) Log.w(TAG, "cannot delete broken " + target);
        }
        long transmuxed = transmux(source, target);
        if (transmuxed >= 0) {
            long duration = readDuration(target);
            if (duration >= 0) return duration > 0 ? duration : transmuxed;
            Log.w(TAG, "transmux output unreadable, falling back to raw copy");
            if (!target.delete()) Log.w(TAG, "cannot delete " + target);
        }
        if (copyFallback(source, target)) {
            long duration = readDuration(target);
            if (duration >= 0) return duration;
            Log.w(TAG, "raw audio.m4s is not playable, skipping " + source.getAbsolutePath());
            if (!target.delete()) Log.w(TAG, "cannot delete " + target);
        }
        return -1;
    }

    private static long transmux(File source, File target) {
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        if (temp.exists() && !temp.delete()) {
            Log.w(TAG, "cannot clear " + temp);
            return -1;
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean finished = false;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat format = null;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat candidate = extractor.getTrackFormat(index);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = index;
                    format = candidate;
                    break;
                }
            }
            if (audioTrack < 0 || format == null) {
                Log.w(TAG, "no audio track in " + source.getAbsolutePath());
                return -1;
            }
            extractor.selectTrack(audioTrack);
            muxer = new MediaMuxer(temp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outputTrack = muxer.addTrack(format);
            muxer.start();
            int maxInput = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
            ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(1024 * 1024, maxInput));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastPts = 0;
            int samples = 0;
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                long pts = extractor.getSampleTime();
                info.set(0, size, pts, extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC);
                muxer.writeSampleData(outputTrack, buffer, info);
                lastPts = pts;
                samples++;
                if (!extractor.advance()) break;
            }
            if (samples == 0) {
                Log.w(TAG, "no samples in " + source.getAbsolutePath());
                return -1;
            }
            muxer.stop();
            muxer.release();
            muxer = null;
            extractor.release();
            if (target.exists() && !target.delete()) return -1;
            if (!temp.renameTo(target)) {
                Log.w(TAG, "cannot rename " + temp + " to " + target);
                return -1;
            }
            finished = true;
            long duration = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) / 1000L : lastPts / 1000L;
            return Math.max(0, duration);
        } catch (Exception failure) {
            Log.w(TAG, "remux failed for " + source.getAbsolutePath(), failure);
            return -1;
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
                // Already released.
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                    // Never started.
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                    // Already released.
                }
            }
            if (!finished && temp.exists() && !temp.delete()) {
                Log.w(TAG, "cannot delete " + temp);
            }
        }
    }

    private static boolean copyFallback(File source, File target) {
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(temp);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
            output.close();
            output = null;
            if (target.exists() && !target.delete()) return false;
            if (!temp.renameTo(target)) return false;
            return target.isFile() && target.length() > 0;
        } catch (Exception failure) {
            Log.w(TAG, "raw copy failed for " + source.getAbsolutePath(), failure);
            return false;
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            if (output != null) try { output.close(); } catch (IOException ignored) { }
            if (temp.exists() && !target.exists()) temp.delete();
        }
    }

    /** @return duration in ms, 0 when parsed without duration, -1 when unreadable */
    private static long readDuration(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value == null) return 0;
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException invalid) {
                return 0;
            }
        } catch (Exception failure) {
            return -1;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing to release.
            }
        }
    }

    private static String downloadCover(String coverUrl, MusicLibrary library, int[] failures) {
        if (coverUrl == null || coverUrl.isEmpty() || failures[0] >= 2) return "";
        String url = coverUrl;
        if (url.startsWith("http://")) url = "https://" + url.substring("http://".length());
        byte[] bytes = null;
        try {
            bytes = Net.fetchBytes(url, Net.BILI_REFERER, Net.UA_DESKTOP,
                    12 * 1024 * 1024, 15000, 60000, null, null);
        } catch (Exception primary) {
            if (!url.equals(coverUrl)) {
                try {
                    bytes = Net.fetchBytes(coverUrl, Net.BILI_REFERER, Net.UA_DESKTOP,
                            12 * 1024 * 1024, 15000, 60000, null, null);
                } catch (Exception fallback) {
                    bytes = null;
                }
            }
        }
        if (bytes == null || bytes.length == 0) {
            failures[0]++;
            Log.w(TAG, "cannot download cover " + coverUrl);
            return "";
        }
        return library.cacheArtwork(bytes);
    }

    private static void writeMeta(File file, Entry entry, File entryFile, File audio, String key,
                                  long duration, String artworkKey) {
        JSONObject json = new JSONObject();
        try {
            json.put("version", 1);
            json.put("title", entry.title);
            json.put("part", entry.part);
            json.put("artist", entry.owner);
            json.put("coverUrl", entry.cover);
            json.put("artworkKey", artworkKey);
            json.put("avid", entry.avid);
            json.put("cid", entry.cid);
            json.put("seasonId", entry.seasonId);
            json.put("episodeId", entry.episodeId);
            json.put("groupId", entry.groupId);
            json.put("sourcePath", audio.getAbsolutePath());
            json.put("entryPath", entryFile.getAbsolutePath());
            json.put("durationMs", duration);
            json.put("order", entry.order);
            json.put("mediaType", entry.mediaType);
            json.put("hasDashAudio", entry.hasDashAudio);
            json.put("converted", true);
            json.put("importedAt", System.currentTimeMillis());
            MusicLibrary.writeText(file, json.toString());
        } catch (Exception failure) {
            Log.w(TAG, "cannot write metadata for " + key, failure);
        }
    }

    private static JSONObject readJson(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            byte[] bytes = MusicLibrary.readBytes(file);
            if (bytes == null || bytes.length == 0) return null;
            return new JSONObject(new String(bytes, "UTF-8"));
        } catch (Exception failure) {
            return null;
        }
    }

    private static String displayName(Entry entry) {
        if (entry == null) return "未知条目";
        if (!entry.part.isEmpty()) return entry.part;
        if (!entry.title.isEmpty()) return entry.title;
        return "未知条目";
    }

    // -------------------------------------------------------------- shell/SAF

    private static final class ShellResult {
        int code;
        String output = "";
    }

    private static ShellResult runSu(String command, int timeoutMs) {
        ShellResult result = new ShellResult();
        Process process;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        } catch (Exception failure) {
            result.code = -1;
            result.output = failure.getMessage() == null ? "" : failure.getMessage();
            return result;
        }
        final Process running = process;
        final StringBuilder output = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream input = null;
                try {
                    input = running.getInputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        String text = new String(buffer, 0, count, "UTF-8");
                        synchronized (output) {
                            if (output.length() < 32768) output.append(text);
                        }
                    }
                } catch (Exception ignored) {
                    // Process died.
                } finally {
                    if (input != null) try { input.close(); } catch (IOException ignored) { }
                }
            }
        }, "rhine-su-reader");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        boolean finished = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                result.code = running.exitValue();
                finished = true;
                break;
            } catch (IllegalThreadStateException stillRunning) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!finished) {
            try {
                running.destroy();
            } catch (Exception ignored) {
                // Already gone.
            }
            result.code = -2;
        }
        try {
            reader.join(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (output) {
            result.output = output.toString().trim();
        }
        return result;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String packageNameOf(String dir) {
        int marker = dir.indexOf("/Android/data/");
        if (marker >= 0) {
            String rest = dir.substring(marker + "/Android/data/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) return rest.substring(0, slash);
        }
        return "bili-" + MusicLibrary.sha256Hex(dir).substring(0, 8);
    }

    private static String sanitizeSegment(String value) {
        String name = value.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001f]", "_").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) return "dir";
        return name;
    }

    private static int numericSuffix(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Extracted cache metadata, before the playable file is written. */
    private static final class Entry {
        File entryFile;
        String title = "";
        String part = "";
        String owner = "";
        String cover = "";
        String avid = "";
        String seasonId = "";
        String episodeId = "";
        String groupId = "";
        long cid;
        int order;
        int mediaType;
        boolean hasDashAudio = true;
    }
}
