package com.rhine.music;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Local music index for the Android port.
 *
 * <p>Four sources feed the same album/track contract the Windows build exposes
 * over its local HTTP service: the bundled demo album, files downloaded from a
 * user supplied URL, folders imported through the Storage Access Framework and
 * the system media library. Metadata is read with {@link MediaMetadataRetriever}
 * and cached, so a rescan only pays for new or changed files.</p>
 */
public final class MusicLibrary {

    private static final String TAG = "RhineLibrary";
    private static final String INDEX_VERSION = "1";
    private static final long LYRICS_LIMIT = 1024L * 1024L;

    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<String>();
    private static final Set<String> BROWSER_EXTENSIONS = new HashSet<String>();

    static {
        Collections.addAll(AUDIO_EXTENSIONS,
                ".flac", ".wav", ".m4a", ".mp4", ".alac", ".dsf", ".dff",
                ".mp3", ".aac", ".aiff", ".aif", ".ogg", ".opus");
        Collections.addAll(BROWSER_EXTENSIONS,
                ".flac", ".wav", ".m4a", ".mp4", ".mp3", ".aac", ".ogg", ".opus");
    }

    /** Genre rule set mirrors scripts/music-library.mjs DEFAULT_RULES. */
    private static final String[][] DEFAULT_RULES = {
            {"mandopop", "华语流行", "Mandopop|国语流行音乐|华语流行音乐|华语流行|国语流行|Chinese Pop"},
            {"pop", "流行", "Pop|流行音乐"},
            {"rock", "摇滚", "Rock|摇滚音乐"},
            {"jazz", "爵士", "Jazz|爵士乐"},
            {"classical", "古典", "Classical|古典音乐"},
            {"electronic", "电子", "Electronic|Electronica|电子音乐"},
            {"ambient", "氛围", "Ambient|氛围音乐"},
            {"soundtrack", "原声", "Soundtrack|OST|原声音乐|电影原声"},
            {"unclassified", "未分类", ""},
    };

    public static final class Track {
        public String id = "";
        public String uri = "";
        public String source = "";
        public String groupKey = "";
        public String title = "";
        public String artist = "";
        public String albumTitle = "";
        public String genre = "";
        public String folderLabel = "";
        public String folderPath = "";
        public String relativePath = "";
        public String displayName = "";
        public String lrcUri = "";
        public String dataPath = "";
        public String artworkKey = "";
        public String format = "";
        public String codec = "";
        public int year;
        public int trackNumber;
        public int discNumber;
        public int bitrate;
        public int sampleRate;
        public int channels;
        public long durationMs;
        public long size;
        public long modified;
        public boolean browserPlayable = true;

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("albumId", "");
            json.put("title", title.isEmpty() ? displayName : title);
            json.put("artist", artist.isEmpty() ? "未知艺术家" : artist);
            if (trackNumber > 0) json.put("trackNumber", trackNumber);
            if (discNumber > 0) json.put("discNumber", discNumber);
            json.put("duration", Math.max(0, durationMs) / 1000.0);
            json.put("format", format);
            if (!codec.isEmpty()) json.put("codec", codec);
            if (bitrate > 0) json.put("bitrate", bitrate);
            if (sampleRate > 0) json.put("sampleRate", sampleRate);
            if (channels > 0) json.put("numberOfChannels", channels);
            json.put("browserPlayable", browserPlayable);
            json.put("audioUrl", "/api/audio/" + id);
            json.put("relativePath", relativePath);
            json.put("offline", false);
            return json;
        }

        JSONObject persist() throws JSONException {
            JSONObject json = toJson();
            json.put("uri", uri);
            json.put("source", source);
            json.put("groupKey", groupKey);
            json.put("albumTitle", albumTitle);
            json.put("genre", genre);
            json.put("folderLabel", folderLabel);
            json.put("folderPath", folderPath);
            json.put("displayName", displayName);
            json.put("lrcUri", lrcUri);
            json.put("dataPath", dataPath);
            json.put("artworkKey", artworkKey);
            json.put("codec", codec);
            json.put("size", size);
            json.put("modified", modified);
            json.put("durationMs", durationMs);
            json.put("browserPlayable", browserPlayable);
            json.put("trackNumber", trackNumber);
            json.put("discNumber", discNumber);
            json.put("year", year);
            json.put("bitrate", bitrate);
            json.put("sampleRate", sampleRate);
            json.put("channels", channels);
            return json;
        }

        static Track fromPersist(JSONObject json) {
            Track track = new Track();
            track.id = json.optString("id");
            track.uri = json.optString("uri");
            track.source = json.optString("source");
            track.groupKey = json.optString("groupKey");
            track.title = json.optString("title");
            track.artist = json.optString("artist");
            track.albumTitle = json.optString("albumTitle");
            track.genre = json.optString("genre");
            track.folderLabel = json.optString("folderLabel");
            track.folderPath = json.optString("folderPath");
            track.relativePath = json.optString("relativePath");
            track.displayName = json.optString("displayName");
            track.lrcUri = json.optString("lrcUri");
            track.dataPath = json.optString("dataPath");
            track.artworkKey = json.optString("artworkKey");
            track.format = json.optString("format");
            track.codec = json.optString("codec");
            track.year = json.optInt("year");
            track.trackNumber = json.optInt("trackNumber");
            track.discNumber = json.optInt("discNumber");
            track.bitrate = json.optInt("bitrate");
            track.sampleRate = json.optInt("sampleRate");
            track.channels = json.optInt("channels");
            track.durationMs = json.optLong("durationMs");
            track.size = json.optLong("size");
            track.modified = json.optLong("modified");
            track.browserPlayable = json.optBoolean("browserPlayable", true);
            return track;
        }

        String identity() {
            return uri + "\u0000" + size + "\u0000" + modified;
        }
    }

    public static final class Album {
        public String id = "";
        public String title = "";
        public String artist = "";
        public String folder = "";
        public String coverKey = "";
        public String genreId = "unclassified";
        public int year;
        public int discCount = 1;
        public final List<Track> tracks = new ArrayList<Track>();
        public final List<String> rawGenres = new ArrayList<String>();

        JSONObject toJson(Map<String, String> rules) throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("title", title);
            json.put("artist", artist);
            if (year > 0) json.put("year", year);
            json.put("discCount", Math.max(1, discCount));
            json.put("genreId", genreId);
            json.put("rawGenres", new JSONArray(rawGenres));
            json.put("folder", folder);
            if (!coverKey.isEmpty()) json.put("coverUrl", "/api/artwork/" + id + "?v=" + coverKey.substring(0, Math.min(10, coverKey.length())));
            JSONArray tracksJson = new JSONArray();
            for (Track track : tracks) {
                JSONObject trackJson = track.toJson();
                trackJson.put("albumId", id);
                tracksJson.put(trackJson);
            }
            json.put("tracks", tracksJson);
            json.put("producers", new JSONArray());
            boolean offline = tracks.isEmpty();
            json.put("offline", offline);
            JSONObject online = new JSONObject();
            online.put("status", "unqueried");
            json.put("online", online);
            return json;
        }
    }

    /** Seekable read handle used by the HTTP audio route. */
    public static final class AudioSource {
        public final InputStream stream;
        public final long length;

        AudioSource(InputStream stream, long length) {
            this.stream = stream;
            this.length = length;
        }

        public void close() {
            try {
                stream.close();
            } catch (IOException ignored) {
                // The client is already gone.
            }
        }
    }

    public static final class SafRoot {
        public String uri = "";
        public String label = "";

        JSONObject persist() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("uri", uri);
            json.put("label", label);
            return json;
        }
    }

    private final Context context;
    private final File dataDir;
    private final File demoDir;
    private final File downloadDir;
    private final File artworkDir;
    private final File artworkThumbDir;
    private final File biliDir;
    private final File biliInboxDir;
    private final File cacheDir;

    private final Object lock = new Object();
    private final List<Album> albums = new ArrayList<Album>();
    private final Map<String, Track> trackById = new HashMap<String, Track>();
    private final List<SafRoot> safRoots = new ArrayList<SafRoot>();
    private final Map<String, String> albumOverrides = new LinkedHashMap<String, String>();
    private final Map<String, String> artworkMime = new HashMap<String, String>();

    private String scanStartedAt = "";
    private String scanFinishedAt = "";
    private String scanError = "";
    private volatile boolean scanning;
    private volatile boolean loaded;
    private volatile boolean mediaEnabled = true;
    private volatile boolean demoInstalled;

    public MusicLibrary(Context context) {
        this.context = context.getApplicationContext();
        File files = this.context.getFilesDir();
        this.dataDir = new File(files, "music-data");
        this.demoDir = new File(files, "demo");
        this.downloadDir = new File(files, "downloads");
        this.artworkDir = new File(dataDir, "artwork");
        this.artworkThumbDir = new File(dataDir, "artwork-thumb");
        this.biliDir = new File(files, "bili-media");
        this.biliInboxDir = new File(files, "bili-inbox");
        this.cacheDir = new File(this.context.getCacheDir(), "audio");
        ensureDirs();
    }

    public File downloadDir() {
        return downloadDir;
    }

    public File demoDir() {
        return demoDir;
    }

    public File artworkDir() {
        return artworkDir;
    }

    /** Thumbnails generated for {@code /api/artwork/<id>?w=...}. */
    public File artworkThumbDir() {
        return artworkThumbDir;
    }

    /** Converted Bilibili cache tracks (one {@code .m4a} + sidecar JSON each). */
    public File biliMediaDir() {
        return biliDir;
    }

    /** Staging area for Root/SAF imports before conversion. */
    public File biliInboxDir() {
        return biliInboxDir;
    }

    private void ensureDirs() {
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) Log.w(TAG, "cannot create " + dataDir);
        if (!demoDir.isDirectory() && !demoDir.mkdirs()) Log.w(TAG, "cannot create " + demoDir);
        if (!downloadDir.isDirectory() && !downloadDir.mkdirs()) Log.w(TAG, "cannot create " + downloadDir);
        if (!artworkDir.isDirectory() && !artworkDir.mkdirs()) Log.w(TAG, "cannot create " + artworkDir);
        if (!artworkThumbDir.isDirectory() && !artworkThumbDir.mkdirs()) {
            Log.w(TAG, "cannot create " + artworkThumbDir);
        }
        if (!biliDir.isDirectory() && !biliDir.mkdirs()) Log.w(TAG, "cannot create " + biliDir);
        if (!biliInboxDir.isDirectory() && !biliInboxDir.mkdirs()) Log.w(TAG, "cannot create " + biliInboxDir);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) Log.w(TAG, "cannot create " + cacheDir);
    }

    public void markDemoInstalled(boolean installed) {
        this.demoInstalled = installed;
    }

    // ---------------------------------------------------------------- storage

    private File indexFile() {
        return new File(dataDir, "index.json");
    }

    private File configFile() {
        return new File(dataDir, "config.json");
    }

    private File rulesFile() {
        return new File(dataDir, "genre-rules.json");
    }

    public synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (configFile().isFile()) {
                JSONObject config = new JSONObject(readText(configFile()));
                mediaEnabled = config.optBoolean("mediaEnabled", true);
                JSONArray roots = config.optJSONArray("safRoots");
                if (roots != null) {
                    for (int i = 0; i < roots.length(); i++) {
                        JSONObject entry = roots.optJSONObject(i);
                        if (entry == null) continue;
                        SafRoot root = new SafRoot();
                        root.uri = entry.optString("uri");
                        root.label = entry.optString("label");
                        if (!root.uri.isEmpty()) safRoots.add(root);
                    }
                }
            }
            File rules = rulesFile();
            if (rules.isFile()) {
                JSONObject stored = new JSONObject(readText(rules));
                JSONObject overrides = stored.optJSONObject("albumOverrides");
                if (overrides != null) {
                    JSONArray names = overrides.names();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            String key = names.optString(i);
                            if (!key.isEmpty()) albumOverrides.put(key, overrides.optString(key));
                        }
                    }
                }
            }
            if (indexFile().isFile()) {
                JSONObject index = new JSONObject(readText(indexFile()));
                JSONArray tracks = index.optJSONArray("tracks");
                List<Track> restored = new ArrayList<Track>();
                if (tracks != null) {
                    for (int i = 0; i < tracks.length(); i++) {
                        JSONObject json = tracks.optJSONObject(i);
                        if (json != null) restored.add(Track.fromPersist(json));
                    }
                }
                rebuildAlbums(restored);
                scanFinishedAt = index.optString("scannedAt");
            }
        } catch (Exception error) {
            Log.w(TAG, "cannot read cached index", error);
        }
    }

    private void saveIndex() {
        try {
            List<Track> all = new ArrayList<Track>();
            synchronized (lock) {
                for (Album album : albums) all.addAll(album.tracks);
            }
            Collections.sort(all, new Comparator<Track>() {
                @Override
                public int compare(Track a, Track b) {
                    return a.uri.compareTo(b.uri);
                }
            });
            JSONArray tracks = new JSONArray();
            for (Track track : all) tracks.put(track.persist());
            JSONObject index = new JSONObject();
            index.put("version", INDEX_VERSION);
            index.put("scannedAt", scanFinishedAt);
            index.put("tracks", tracks);
            writeText(indexFile(), index.toString());
        } catch (Exception error) {
            Log.w(TAG, "cannot persist index", error);
        }
    }

    private void saveConfig() {
        try {
            JSONObject config = new JSONObject();
            config.put("version", 1);
            config.put("mediaEnabled", mediaEnabled);
            JSONArray roots = new JSONArray();
            synchronized (lock) {
                for (SafRoot root : safRoots) roots.put(root.persist());
            }
            config.put("safRoots", roots);
            writeText(configFile(), config.toString());
        } catch (Exception error) {
            Log.w(TAG, "cannot persist config", error);
        }
    }

    // ---------------------------------------------------------------- scanning

    public boolean isScanning() {
        return scanning;
    }

    /** Starts a background scan when one is not already running. */
    public void requestScan() {
        synchronized (lock) {
            if (scanning) return;
            scanning = true;
            scanStartedAt = iso(new Date());
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                scanAll();
            }
        }, "rhine-scan");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private void scanAll() {
        List<Track> collected = new ArrayList<Track>();
        Map<String, Track> previous = new HashMap<String, Track>();
        synchronized (lock) {
            for (Album album : albums) {
                for (Track track : album.tracks) previous.put(track.identity(), track);
            }
        }
        String error = "";
        try {
            Set<String> seen = new HashSet<String>();
            scanDemo(collected, previous, seen);
            scanDownloads(collected, previous, seen);
            scanBili(collected, previous, seen);
            List<SafRoot> rootsCopy;
            synchronized (lock) {
                rootsCopy = new ArrayList<SafRoot>(safRoots);
            }
            for (SafRoot root : rootsCopy) {
                try {
                    scanSafTree(root, collected, previous, seen);
                } catch (Exception failure) {
                    Log.w(TAG, "SAF root failed: " + root.uri, failure);
                }
            }
            if (mediaEnabled && hasAudioPermission()) {
                try {
                    scanMediaStore(collected, previous, seen);
                } catch (Exception failure) {
                    Log.w(TAG, "media store scan failed", failure);
                }
            }
            rebuildAlbums(dedupe(collected));
            scanFinishedAt = iso(new Date());
            saveIndex();
        } catch (Throwable failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            Log.w(TAG, "scan failed", failure);
        } finally {
            synchronized (lock) {
                scanError = error;
                scanning = false;
                if (scanFinishedAt.isEmpty()) scanFinishedAt = iso(new Date());
            }
        }
    }

    /**
     * The same physical file can surface both through the media library and an
     * imported folder. Keep the first occurrence — imports are scanned before the
     * media store — so the archive does not list duplicate albums and folder
     * artwork/lyrics continue to work for imported folders.
     */
    private List<Track> dedupe(List<Track> tracks) {
        List<Track> out = new ArrayList<Track>();
        Set<String> keys = new HashSet<String>();
        for (Track track : tracks) {
            String key = canonicalFileKey(track);
            if (!key.isEmpty() && !keys.add(key)) continue;
            out.add(track);
        }
        return out;
    }

    private static String canonicalFileKey(Track track) {
        String value = "";
        if ("media".equals(track.source)) {
            value = track.dataPath;
            if (value.startsWith("/storage/emulated/0/")) value = value.substring("/storage/emulated/0/".length());
            else if (value.startsWith("/sdcard/")) value = value.substring("/sdcard/".length());
        } else if ("saf".equals(track.source)) {
            int marker = track.uri.indexOf("/document/");
            if (marker >= 0) {
                String documentId = track.uri.substring(marker + "/document/".length());
                try {
                    documentId = java.net.URLDecoder.decode(documentId, "UTF-8");
                } catch (Exception ignored) {
                    // Keep the encoded form; it is still stable per file.
                }
                if (documentId.startsWith("primary:")) value = documentId.substring("primary:".length());
                else value = documentId;
            }
        } else {
            value = track.uri;
        }
        return value.trim().toLowerCase(Locale.US);
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean canReadMediaLibrary() {
        return hasAudioPermission();
    }

    /**
     * Reuses cached metadata when a file kept the same identity, otherwise reads
     * tags and cover art. New tracks also get a stable id derived from their URI.
     */
    private Track accept(File file, String source, String groupKey, String folderLabel, String folderPath,
                         String relativePath, Map<String, Track> previous) {
        Track stub = new Track();
        stub.uri = file.getAbsolutePath();
        stub.source = source;
        stub.groupKey = groupKey;
        stub.folderLabel = folderLabel;
        stub.folderPath = folderPath;
        stub.relativePath = relativePath;
        stub.displayName = file.getName();
        stub.size = file.length();
        stub.modified = file.lastModified();
        stub.id = trackId(stub.uri);
        stub.format = extension(stub.displayName);
        stub.browserPlayable = BROWSER_EXTENSIONS.contains("." + stub.format.toLowerCase(Locale.US));
        Track cached = previous.get(stub.identity());
        if (cached != null) {
            copyMetadata(cached, stub);
            return stub;
        }
        readTags(stub);
        stub.lrcUri = findSiblingLyrics(file);
        return stub;
    }

    private void copyMetadata(Track from, Track to) {
        to.title = from.title;
        to.artist = from.artist;
        to.albumTitle = from.albumTitle;
        to.genre = from.genre;
        to.year = from.year;
        to.trackNumber = from.trackNumber;
        to.discNumber = from.discNumber;
        to.bitrate = from.bitrate;
        to.sampleRate = from.sampleRate;
        to.channels = from.channels;
        to.durationMs = from.durationMs;
        to.codec = from.codec;
        to.artworkKey = from.artworkKey;
        to.lrcUri = from.lrcUri;
        to.dataPath = from.dataPath;
    }

    private void readTags(Track track) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (track.uri.startsWith("content:")) {
                retriever.setDataSource(context, Uri.parse(track.uri));
            } else {
                retriever.setDataSource(track.uri);
            }
            track.title = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            track.artist = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            track.albumTitle = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            track.genre = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE));
            track.year = parseInt(clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)), 0);
            track.trackNumber = parseInt(clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)), 0);
            track.discNumber = parseInt(clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)), 0);
            track.durationMs = parseLong(clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)), 0);
            track.bitrate = parseInt(clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)), 0);
            String mime = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
            if (!mime.isEmpty()) {
                int slash = mime.indexOf('/');
                track.codec = slash >= 0 ? mime.substring(slash + 1) : mime;
            }
        } catch (Exception failure) {
            Log.d(TAG, "no tags for " + track.displayName + ": " + failure.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing to release.
            }
        }
    }

    private byte[] readEmbeddedArtwork(Track track) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (track.uri.startsWith("content:")) {
                retriever.setDataSource(context, Uri.parse(track.uri));
            } else {
                retriever.setDataSource(track.uri);
            }
            return retriever.getEmbeddedPicture();
        } catch (Exception failure) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing to release.
            }
        }
    }

    private void scanDemo(List<Track> out, Map<String, Track> previous, Set<String> seen) {
        if (!demoDir.isDirectory()) return;
        JSONObject manifest = null;
        File manifestFile = new File(demoDir, "manifest.json");
        if (manifestFile.isFile()) {
            try {
                manifest = new JSONObject(readText(manifestFile));
            } catch (Exception ignored) {
                manifest = null;
            }
        }
        if (manifest != null) {
            JSONArray entries = manifest.optJSONArray("albums");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject albumJson = entries.optJSONObject(i);
                    if (albumJson == null) continue;
                    String folder = albumJson.optString("folder", "demo");
                    String albumTitle = albumJson.optString("title", "Rhine 演示");
                    String albumArtist = albumJson.optString("artist", "Rhine Lab");
                    String albumGenre = albumJson.optString("genre", "Ambient");
                    int year = albumJson.optInt("year", 0);
                    String coverName = albumJson.optString("cover", "");
                    File coverFile = coverName.isEmpty() ? null : new File(demoDir, folder + "/" + coverName);
                    JSONArray trackList = albumJson.optJSONArray("tracks");
                    if (trackList == null) continue;
                    for (int t = 0; t < trackList.length(); t++) {
                        JSONObject trackJson = trackList.optJSONObject(t);
                        if (trackJson == null) continue;
                        String name = trackJson.optString("file", "");
                        File file = new File(demoDir, folder + "/" + name);
                        if (!file.isFile()) continue;
                        if (!seen.add(file.getAbsolutePath())) continue;
                                        Track track = accept(file, "demo", "demo|" + folder, folder, folder,
                                folder + "/" + name, previous);
                        track.title = trackJson.optString("title", track.title);
                        track.artist = trackJson.optString("artist", albumArtist);
                        track.albumTitle = albumTitle;
                        track.genre = albumGenre;
                        track.year = year;
                        track.discNumber = 1;
                        track.trackNumber = t + 1;
                        String lrc = trackJson.optString("lyrics", "");
                        if (!lrc.isEmpty()) {
                            File lyricFile = new File(demoDir, folder + "/" + lrc);
                            if (lyricFile.isFile()) track.lrcUri = lyricFile.getAbsolutePath();
                        }
                        if (coverFile != null && coverFile.isFile() && track.artworkKey.isEmpty()) {
                            track.artworkKey = cacheArtwork(readBytes(coverFile));
                        }
                        if (track.durationMs <= 0) readTags(track);
                        out.add(track);
                    }
                }
            }
            return;
        }
        // Fallback: no manifest, treat each folder as an album.
        File[] groups = demoDir.listFiles();
        if (groups == null) return;
        for (File group : groups) {
            if (!group.isDirectory()) continue;
            File[] files = group.listFiles();
            if (files == null) continue;
            String albumTitle = group.getName();
            int index = 0;
            for (File file : files) {
                if (!file.isFile() || !isAudio(file.getName())) continue;
                if (!seen.add(file.getAbsolutePath())) continue;
                Track track = accept(file, "demo", "demo|" + albumTitle, albumTitle, albumTitle,
                        albumTitle + "/" + file.getName(), previous);
                track.trackNumber = ++index;
                out.add(track);
            }
        }
    }

    private void scanDownloads(List<Track> out, Map<String, Track> previous, Set<String> seen) {
        walkMediaFolder(downloadDir, downloadDir, "download", "下载音乐", "下载音乐", out, previous, seen, 0);
    }

    /**
     * Indexes files produced by {@link BiliCache}: {@code bili-media/<key>.m4a}
     * plus its {@code <key>.json} sidecar. The sidecar carries the Bilibili
     * metadata (title/part/owner/avid/cover) so a rescan never needs the network.
     */
    private void scanBili(List<Track> out, Map<String, Track> previous, Set<String> seen) {
        File[] entries = biliDir.listFiles();
        if (entries == null) return;
        List<File> audioFiles = new ArrayList<File>();
        for (File entry : entries) {
            if (!entry.isFile()) continue;
            String lower = entry.getName().toLowerCase(Locale.US);
            if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) audioFiles.add(entry);
        }
        Collections.sort(audioFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return naturalCompare(a.getName(), b.getName());
            }
        });
        for (File file : audioFiles) {
            if (!seen.add(file.getAbsolutePath())) continue;
            JSONObject meta = readBiliMeta(file);
            Track track = new Track();
            track.uri = file.getAbsolutePath();
            track.source = "bili";
            track.displayName = file.getName();
            track.size = file.length();
            track.modified = file.lastModified();
            track.id = trackId(track.uri);
            track.format = "M4A";
            track.browserPlayable = true;
            Track cached = previous.get(track.identity());
            if (cached != null) copyMetadata(cached, track);
            else readTags(track);

            String albumTitle = meta == null ? "" : clean(meta.optString("title"));
            String part = meta == null ? "" : clean(meta.optString("part"));
            String artist = meta == null ? "" : clean(meta.optString("artist"));
            String groupId = meta == null ? "" : clean(meta.optString("groupId"));
            if (albumTitle.isEmpty()) albumTitle = "B站缓存";
            if (part.isEmpty()) part = albumTitle;
            if (artist.isEmpty()) artist = "B站";
            if (groupId.isEmpty()) groupId = "file:" + stem(file.getName());
            track.groupKey = "bili|" + groupId;
            track.folderLabel = "B站缓存";
            track.folderPath = albumTitle;
            track.albumTitle = albumTitle;
            track.title = part;
            track.artist = artist;
            track.relativePath = "B站缓存/" + albumTitle + "/" + part;
            track.artworkKey = meta == null ? "" : clean(meta.optString("artworkKey"));
            if (meta != null) {
                long duration = meta.optLong("durationMs", 0);
                if (duration > 0) track.durationMs = duration;
                track.trackNumber = meta.optInt("order", track.trackNumber);
                track.dataPath = meta.optString("sourcePath", track.dataPath);
            }
            track.codec = "m4a";
            out.add(track);
        }
    }

    private static JSONObject readBiliMeta(File audio) {
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        File meta = new File(audio.getParentFile(), (dot > 0 ? name.substring(0, dot) : name) + ".json");
        if (!meta.isFile()) return null;
        try {
            return new JSONObject(readText(meta));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** True once at least one Bilibili cache track is indexed. */
    public boolean hasBiliTracks() {
        synchronized (lock) {
            for (Album album : albums) {
                for (Track track : album.tracks) {
                    if ("bili".equals(track.source)) return true;
                }
            }
        }
        return false;
    }

    /** Files and folders below {@code root}; nested folders become albums. */
    private void walkMediaFolder(File root, File folder, String source, String rootLabel,
                                 String albumLabel, List<Track> out, Map<String, Track> previous,
                                 Set<String> seen, int depth) {
        if (depth > 6) return;
        File[] entries = folder.listFiles();
        if (entries == null) return;
        List<File> covers = new ArrayList<File>();
        List<File> tracks = new ArrayList<File>();
        for (File entry : entries) {
            if (entry.isDirectory()) continue;
            if (isAudio(entry.getName())) tracks.add(entry);
            else if (isImage(entry.getName())) covers.add(entry);
        }
        if (!tracks.isEmpty()) {
            Collections.sort(tracks, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return naturalCompare(a.getName(), b.getName());
                }
            });
            File cover = preferredCover(covers);
            String groupKey = source + "|" + folder.getAbsolutePath();
            String label = albumLabel;
            int order = 0;
            for (File file : tracks) {
                if (!seen.add(file.getAbsolutePath())) continue;
                Track track = accept(file, source, groupKey, label, folder.getAbsolutePath(),
                        rootLabel + "/" + relative(root, file), previous);
                track.trackNumber = ++order;
                if (cover != null && track.artworkKey.isEmpty()) {
                    track.artworkKey = cacheArtwork(readBytes(cover));
                }
                if (track.albumTitle.isEmpty()) track.albumTitle = label;
                out.add(track);
            }
        }
        List<File> directories = new ArrayList<File>();
        for (File entry : entries) {
            if (entry.isDirectory() && !entry.getName().startsWith(".")) directories.add(entry);
        }
        Collections.sort(directories, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return naturalCompare(a.getName(), b.getName());
            }
        });
        for (File directory : directories) {
            walkMediaFolder(root, directory, source, rootLabel, directory.getName(), out, previous, seen, depth + 1);
        }
    }

    private void scanSafTree(SafRoot root, List<Track> out, Map<String, Track> previous, Set<String> seen) {
        Uri treeUri = Uri.parse(root.uri);
        String rootDocId;
        try {
            rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception failure) {
            Log.w(TAG, "invalid SAF root " + root.uri, failure);
            return;
        }
        Map<String, String> lyrics = new HashMap<String, String>();
        Map<String, File> covers = new HashMap<String, File>();
        List<Track> collected = new ArrayList<Track>();
        walkSaf(treeUri, rootDocId, "", root.label, root.label, collected, previous, seen, lyrics, covers, 0);
        for (Track track : collected) {
            if (track.artworkKey.isEmpty()) {
                File cover = covers.get(track.groupKey);
                if (cover != null && cover.isFile()) {
                    track.artworkKey = cacheArtwork(readBytes(cover));
                }
            }
            out.add(track);
        }
    }

    private void walkSaf(Uri treeUri, String documentId, String relativePath, String rootLabel,
                         String folderLabel, List<Track> out, Map<String, Track> previous,
                         Set<String> seen, Map<String, String> lyrics, Map<String, File> covers,
                         int depth) {
        if (depth > 8) return;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        Cursor cursor = null;
        List<String> directories = new ArrayList<String>();
        List<String> directoryNames = new ArrayList<String>();
        List<SafFile> files = new ArrayList<SafFile>();
        try {
            cursor = context.getContentResolver().query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            }, null, null, null);
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                SafFile file = new SafFile();
                file.documentId = cursor.getString(0);
                file.name = cursor.isNull(1) ? "" : cursor.getString(1);
                file.mime = cursor.isNull(2) ? "" : cursor.getString(2);
                file.size = cursor.isNull(3) ? 0 : cursor.getLong(3);
                file.modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(file.mime)) {
                    directories.add(file.documentId);
                    directoryNames.add(file.name);
                } else {
                    files.add(file);
                }
            }
        } catch (Exception failure) {
            Log.w(TAG, "SAF query failed at " + relativePath, failure);
            return;
        } finally {
            if (cursor != null) cursor.close();
        }
        String groupKey = "saf|" + treeUri + "|" + (relativePath.isEmpty() ? "/" : relativePath);
        // Lyrics and folder covers are indexed first: an audio file may be listed
        // before its sidecar .lrc, and the track is built in one pass.
        for (SafFile file : files) {
            if (isLyrics(file.name)) {
                String docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId).toString();
                lyrics.put(documentId + "|" + stem(file.name).toLowerCase(Locale.US), docUri);
            } else if (isImage(file.name)) {
                int rank = coverRank(new File(file.name));
                if (rank < 9 && !covers.containsKey(groupKey)) {
                    File placeholder = new File(artworkDir, "saf-cover-" + safeHash(documentId));
                    if (!placeholder.isFile()) {
                        copyUriToFile(Uri.parse(DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId).toString()), placeholder);
                    }
                    if (placeholder.isFile()) covers.put(groupKey, placeholder);
                }
            }
        }
        int order = 0;
        for (SafFile file : files) {
            if (!isAudio(file.name)) continue;
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId);
            Track stub = new Track();
            stub.uri = documentUri.toString();
            stub.source = "saf";
            stub.groupKey = groupKey;
            stub.folderLabel = folderLabel;
            stub.folderPath = relativePath;
            stub.relativePath = rootLabel + "/" + (relativePath.isEmpty() ? "" : relativePath + "/") + file.name;
            stub.displayName = file.name;
            stub.size = file.size;
            stub.modified = file.modified;
            stub.id = trackId(stub.uri);
            stub.format = extension(file.name);
            stub.browserPlayable = BROWSER_EXTENSIONS.contains("." + stub.format.toLowerCase(Locale.US));
            String stem = stem(file.name).toLowerCase(Locale.US);
            String lrc = lyrics.get(documentId + "|" + stem);
            if (lrc != null) stub.lrcUri = lrc;
            if (!seen.add(stub.uri)) continue;
            Track cached = previous.get(stub.identity());
            if (cached != null) copyMetadata(cached, stub);
            else readTags(stub);
            if (stub.albumTitle.trim().isEmpty()) stub.albumTitle = folderLabel;
            if (stub.trackNumber <= 0) stub.trackNumber = ++order;
            File cover = covers.get(groupKey);
            if (stub.artworkKey.isEmpty() && cover != null && cover.isFile()) {
                stub.artworkKey = cacheArtwork(readBytes(cover));
            }
            out.add(stub);
        }
        for (int i = 0; i < directories.size(); i++) {
            String child = relativePath.isEmpty() ? directoryNames.get(i) : relativePath + "/" + directoryNames.get(i);
            String label = directoryNames.get(i);
            walkSaf(treeUri, directories.get(i), child, rootLabel, label, out, previous, seen, lyrics, covers, depth + 1);
        }
    }

    private void scanMediaStore(List<Track> out, Map<String, Track> previous, Set<String> seen) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection;
        boolean hasData = true;
        try {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.TRACK,
                    MediaStore.Audio.Media.YEAR,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATA,
            };
        } catch (Throwable unsupported) {
            hasData = false;
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.TRACK,
                    MediaStore.Audio.Media.YEAR,
                    MediaStore.Audio.Media.DISPLAY_NAME,
            };
        }
        String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
        Cursor cursor = null;
        List<Track> collected = new ArrayList<Track>();
        try {
            cursor = resolver.query(collection, projection, selection, null,
                    MediaStore.Audio.Media.ALBUM + " COLLATE NOCASE ASC, " + MediaStore.Audio.Media.TRACK + " ASC");
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
            int titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int artistColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int albumColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
            int albumIdColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
            int durationColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
            int mimeColumn = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE);
            int modifiedColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED);
            int trackColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK);
            int yearColumn = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR);
            int nameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
            int dataColumn = hasData ? cursor.getColumnIndex(MediaStore.Audio.Media.DATA) : -1;
            while (cursor.moveToNext()) {
                long mediaId = cursor.getLong(idColumn);
                Uri contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                        .appendPath(Long.toString(mediaId)).build();
                Track track = new Track();
                track.uri = contentUri.toString();
                if (!seen.add(track.uri)) continue;
                track.source = "media";
                track.displayName = nameColumn >= 0 && !cursor.isNull(nameColumn)
                        ? cursor.getString(nameColumn) : mediaId + ".mp3";
                track.title = titleColumn >= 0 && !cursor.isNull(titleColumn) ? cursor.getString(titleColumn) : "";
                track.artist = artistColumn >= 0 && !cursor.isNull(artistColumn) ? cursor.getString(artistColumn) : "";
                track.albumTitle = albumColumn >= 0 && !cursor.isNull(albumColumn) ? cursor.getString(albumColumn) : "";
                if (track.artist.trim().isEmpty() || "<unknown>".equalsIgnoreCase(track.artist.trim())) {
                    track.artist = "";
                }
                if (track.title.trim().isEmpty()) track.title = stem(track.displayName);
                if (track.albumTitle.trim().isEmpty() || "<unknown>".equalsIgnoreCase(track.albumTitle.trim())) {
                    track.albumTitle = "";
                }
                String albumNumber = albumIdColumn >= 0 && !cursor.isNull(albumIdColumn)
                        ? Long.toString(cursor.getLong(albumIdColumn)) : "0";
                String mime = mimeColumn >= 0 && !cursor.isNull(mimeColumn) ? cursor.getString(mimeColumn) : "";
                track.format = extension(track.displayName);
                if (track.format.isEmpty() && mime.startsWith("audio/")) track.format = mime.substring(6).toUpperCase(Locale.US);
                track.browserPlayable = BROWSER_EXTENSIONS.contains("." + track.format.toLowerCase(Locale.US));
                track.size = sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : 0;
                track.modified = modifiedColumn >= 0 && !cursor.isNull(modifiedColumn) ? cursor.getLong(modifiedColumn) * 1000L : 0;
                track.durationMs = durationColumn >= 0 && !cursor.isNull(durationColumn) ? cursor.getLong(durationColumn) : 0;
                int packedTrack = trackColumn >= 0 && !cursor.isNull(trackColumn) ? cursor.getInt(trackColumn) : 0;
                track.trackNumber = packedTrack > 0 ? (packedTrack % 1000) : 0;
                track.discNumber = packedTrack > 0 ? (packedTrack / 1000) : 0;
                track.year = yearColumn >= 0 && !cursor.isNull(yearColumn) ? cursor.getInt(yearColumn) : 0;
                if (dataColumn >= 0 && !cursor.isNull(dataColumn)) track.dataPath = cursor.getString(dataColumn);
                if (track.albumTitle.isEmpty() && !track.dataPath.isEmpty()) {
                    File parent = new File(track.dataPath).getParentFile();
                    if (parent != null && parent.getName() != null) track.albumTitle = parent.getName();
                }
                track.groupKey = "media|" + albumNumber + "|" + normalizeAlbumName(track.albumTitle);
                track.folderLabel = track.albumTitle;
                track.folderPath = track.albumTitle;
                track.relativePath = track.albumTitle + "/" + track.displayName;
                track.id = trackId(track.uri);
                track.codec = mime;
                if (track.artworkKey.isEmpty()) {
                    Track cached = previous.get(track.identity());
                    if (cached != null) track.artworkKey = cached.artworkKey;
                }
                if (track.lrcUri.isEmpty() && !track.dataPath.isEmpty()) {
                    track.lrcUri = findSiblingLyrics(new File(track.dataPath));
                }
                collected.add(track);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        // Cover art comes from the first track of each album only, so a large
        // library does not pay for a retriever call per song.
        Map<String, String> albumArt = new HashMap<String, String>();
        for (Track track : collected) {
            if (!track.artworkKey.isEmpty()) {
                if (!albumArt.containsKey(track.groupKey)) albumArt.put(track.groupKey, track.artworkKey);
                continue;
            }
            String cachedArt = albumArt.get(track.groupKey);
            if (cachedArt != null) {
                if (!cachedArt.isEmpty()) track.artworkKey = cachedArt;
                continue;
            }
            byte[] picture = readEmbeddedArtwork(track);
            if (picture != null && picture.length > 0) {
                String key = cacheArtwork(picture);
                track.artworkKey = key;
                if (!albumArt.containsKey(track.groupKey)) albumArt.put(track.groupKey, key);
            } else {
                albumArt.put(track.groupKey, "");
            }
        }
        // Give the rest of an album the cover found on a later track, if any.
        for (Track track : collected) {
            if (track.artworkKey.isEmpty()) {
                String key = albumArt.get(track.groupKey);
                if (key != null && !key.isEmpty()) track.artworkKey = key;
            }
        }
        // Device music often keeps the cover next to the files instead of in the
        // tags, so fall back to a folder image before giving up on artwork.
        Map<String, String> folderArt = new HashMap<String, String>();
        for (Track track : collected) {
            if (!track.artworkKey.isEmpty() || track.dataPath.isEmpty()) continue;
            String key = folderArt.get(track.groupKey);
            if (key == null) {
                key = "";
                File parent = new File(track.dataPath).getParentFile();
                if (parent != null) {
                    File[] entries = parent.listFiles();
                    List<File> images = new ArrayList<File>();
                    if (entries != null) {
                        for (File entry : entries) {
                            if (entry.isFile() && isImage(entry.getName())) images.add(entry);
                        }
                    }
                    File cover = preferredCover(images);
                    if (cover != null) key = cacheArtwork(readBytes(cover));
                }
                folderArt.put(track.groupKey, key);
            }
            if (!key.isEmpty()) track.artworkKey = key;
        }
        out.addAll(collected);
    }

    // ---------------------------------------------------------------- albums

    private void rebuildAlbums(List<Track> tracks) {
        Map<String, List<Track>> groups = new LinkedHashMap<String, List<Track>>();
        for (Track track : tracks) {
            String key = track.groupKey.isEmpty() ? "misc|" + track.id : track.groupKey;
            List<Track> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<Track>();
                groups.put(key, group);
            }
            group.add(track);
        }
        Map<String, Album> byId = new LinkedHashMap<String, Album>();
        for (List<Track> group : groups.values()) {
            if (group.isEmpty()) continue;
            Collections.sort(group, new Comparator<Track>() {
                @Override
                public int compare(Track a, Track b) {
                    if (a.discNumber != b.discNumber) return a.discNumber - b.discNumber;
                    if (a.trackNumber != b.trackNumber) return a.trackNumber - b.trackNumber;
                    return naturalCompare(a.displayName, b.displayName);
                }
            });
            Track first = group.get(0);
            String title = first.albumTitle;
            if (title == null || title.trim().isEmpty()) title = first.folderLabel;
            if (title == null || title.trim().isEmpty()) title = "未命名专辑";
            title = title.trim();
            String artist = first.artist;
            if (artist == null || artist.trim().isEmpty()) artist = "未知艺术家";
            String albumId = "album-" + sha256Hex("title:" + normalizeAlbumName(title)).substring(0, 24);
            Album album = byId.get(albumId);
            if (album == null) {
                album = new Album();
                album.id = albumId;
                album.title = title;
                album.artist = artist.trim();
                album.folder = first.folderPath;
                album.year = first.year;
                byId.put(albumId, album);
            }
            for (Track track : group) {
                track.albumTitle = title;
                if (album.coverKey.isEmpty() && !track.artworkKey.isEmpty()) album.coverKey = track.artworkKey;
                if (album.year == 0) album.year = track.year;
                album.discCount = Math.max(album.discCount, Math.max(1, track.discNumber));
                if (!track.genre.isEmpty()) {
                    for (String raw : splitGenres(track.genre)) {
                        if (!album.rawGenres.contains(raw)) album.rawGenres.add(raw);
                    }
                }
                album.tracks.add(track);
            }
        }
        List<Album> ordered = new ArrayList<Album>(byId.values());
        Collections.sort(ordered, new Comparator<Album>() {
            @Override
            public int compare(Album a, Album b) {
                return naturalCompare(a.title, b.title);
            }
        });
        for (Album album : ordered) {
            album.genreId = resolveGenre(album);
        }
        Map<String, Track> byTrackId = new HashMap<String, Track>();
        for (Album album : ordered) {
            for (Track track : album.tracks) byTrackId.put(track.id, track);
        }
        synchronized (lock) {
            albums.clear();
            albums.addAll(ordered);
            trackById.clear();
            trackById.putAll(byTrackId);
        }
    }

    private String resolveGenre(Album album) {
        String override = albumOverrides.get(album.id);
        if (override != null && !override.isEmpty()) return override;
        for (String raw : album.rawGenres) {
            String normalized = normalizeAlbumName(raw);
            for (String[] rule : DEFAULT_RULES) {
                if ("unclassified".equals(rule[0])) continue;
                for (String alias : rule[2].split("\\|")) {
                    if (!alias.isEmpty() && normalizeAlbumName(alias).equals(normalized)) return rule[0];
                }
            }
        }
        return "unclassified";
    }

    // ---------------------------------------------------------------- snapshot

    public JSONObject snapshot() {
        JSONArray albumArray = new JSONArray();
        JSONArray genreArray = new JSONArray();
        JSONArray rootArray = new JSONArray();
        boolean running;
        String startedAt;
        String finishedAt;
        String error;
        synchronized (lock) {
            Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
            boolean hasBili = false;
            for (Album album : albums) {
                try {
                    albumArray.put(album.toJson(albumOverrides));
                } catch (JSONException ignored) {
                    // A single broken album must not drop the whole library.
                }
                for (Track track : album.tracks) {
                    if ("bili".equals(track.source)) {
                        hasBili = true;
                        break;
                    }
                }
                Integer count = counts.get(album.genreId);
                counts.put(album.genreId, count == null ? 1 : count + 1);
            }
            for (String[] rule : DEFAULT_RULES) {
                try {
                    JSONObject genre = new JSONObject();
                    genre.put("id", rule[0]);
                    genre.put("name", rule[1]);
                    if (!rule[2].isEmpty()) {
                        JSONArray aliases = new JSONArray();
                        for (String alias : rule[2].split("\\|")) if (!alias.isEmpty()) aliases.put(alias);
                        genre.put("aliases", aliases);
                    }
                    Integer count = counts.get(rule[0]);
                    if (count != null) genre.put("albumCount", count.intValue());
                    genreArray.put(genre);
                } catch (JSONException ignored) {
                    // Skip this rule.
                }
            }
            rootArray.put(rootJson("内置演示", "online", ""));
            rootArray.put(rootJson("下载音乐", "online", ""));
            if (hasBili) rootArray.put(rootJson("B站缓存", "online", ""));
            for (SafRoot root : safRoots) {
                rootArray.put(rootJson(root.label.isEmpty() ? "导入文件夹" : root.label, "online", ""));
            }
            if (mediaEnabled && hasAudioPermission()) {
                rootArray.put(rootJson("设备音乐库", "online", ""));
            } else if (mediaEnabled) {
                rootArray.put(rootJson("设备音乐库", "unscanned", ""));
            }
            running = scanning;
            startedAt = scanStartedAt;
            finishedAt = scanFinishedAt;
            error = scanError;
        }
        JSONObject snapshot = new JSONObject();
        try {
            snapshot.put("version", 1);
            snapshot.put("albums", albumArray);
            snapshot.put("genres", genreArray);
            snapshot.put("roots", rootArray);
            JSONObject scan = new JSONObject();
            scan.put("running", running);
            if (!startedAt.isEmpty()) scan.put("startedAt", startedAt);
            if (!finishedAt.isEmpty()) scan.put("finishedAt", finishedAt);
            if (!error.isEmpty()) scan.put("error", error);
            snapshot.put("scan", scan);
            snapshot.put("onlineEnabled", false);
            JSONObject enrich = new JSONObject();
            enrich.put("running", false);
            enrich.put("completed", 0);
            enrich.put("total", 0);
            snapshot.put("enrich", enrich);
            JSONObject introductions = new JSONObject();
            introductions.put("running", false);
            introductions.put("completed", 0);
            introductions.put("total", 0);
            introductions.put("updated", 0);
            introductions.put("notFound", 0);
            introductions.put("failed", 0);
            snapshot.put("introductions", introductions);
        } catch (JSONException ignored) {
            // org.json only throws on invalid keys, which are all literals here.
        }
        return snapshot;
    }

    private JSONObject rootJson(String label, String status, String error) {
        JSONObject json = new JSONObject();
        try {
            json.put("path", label);
            json.put("status", status);
            if (!error.isEmpty()) json.put("error", error);
        } catch (JSONException ignored) {
            // Literal keys only.
        }
        return json;
    }

    // ---------------------------------------------------------------- config

    public JSONObject configJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("version", 1);
            JSONArray roots = new JSONArray();
            JSONObject demo = new JSONObject();
            demo.put("path", "内置演示");
            demo.put("status", "online");
            roots.put(demo);
            JSONObject downloads = new JSONObject();
            downloads.put("path", "下载音乐");
            downloads.put("status", "online");
            roots.put(downloads);
            if (hasBiliTracks()) {
                JSONObject bili = new JSONObject();
                bili.put("path", "B站缓存");
                bili.put("status", "online");
                roots.put(bili);
            }
            synchronized (lock) {
                for (SafRoot root : safRoots) {
                    JSONObject entry = new JSONObject();
                    entry.put("path", root.label.isEmpty() ? "导入文件夹" : root.label);
                    entry.put("status", "online");
                    roots.put(entry);
                }
            }
            JSONObject media = new JSONObject();
            media.put("path", "设备音乐库");
            media.put("status", mediaEnabled ? (hasAudioPermission() ? "online" : "unscanned") : "offline");
            roots.put(media);
            json.put("roots", roots);
            json.put("onlineEnabled", false);
            json.put("foobarBaseUrl", JSONObject.NULL);
            json.put("musicBrainzContact", "");
            json.put("musicBrainzConfigured", false);
        } catch (JSONException ignored) {
            // Literal keys only.
        }
        return json;
    }

    public JSONObject updateConfig(JSONObject value) {
        if (value != null && value.has("mediaEnabled")) {
            mediaEnabled = value.optBoolean("mediaEnabled", mediaEnabled);
            saveConfig();
        }
        return configJson();
    }

    public JSONObject genreRulesJson() {
        JSONArray genres = new JSONArray();
        for (String[] rule : DEFAULT_RULES) {
            try {
                JSONObject genre = new JSONObject();
                genre.put("id", rule[0]);
                genre.put("name", rule[1]);
                if (!rule[2].isEmpty()) {
                    JSONArray aliases = new JSONArray();
                    for (String alias : rule[2].split("\\|")) if (!alias.isEmpty()) aliases.put(alias);
                    genre.put("aliases", aliases);
                }
                genres.put(genre);
            } catch (JSONException ignored) {
                // Skip this rule.
            }
        }
        JSONObject rules = new JSONObject();
        try {
            rules.put("version", 1);
            rules.put("genres", genres);
            synchronized (lock) {
                JSONObject overrides = new JSONObject();
                for (Map.Entry<String, String> entry : albumOverrides.entrySet()) {
                    overrides.put(entry.getKey(), entry.getValue());
                }
                rules.put("albumOverrides", overrides);
            }
        } catch (JSONException ignored) {
            // Literal keys only.
        }
        return rules;
    }

    public JSONObject updateGenreRules(JSONObject body) {
        if (body != null) {
            JSONObject overrides = body.optJSONObject("albumOverrides");
            if (overrides != null) {
                synchronized (lock) {
                    albumOverrides.clear();
                    JSONArray names = overrides.names();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            String key = names.optString(i);
                            if (!key.isEmpty()) albumOverrides.put(key, overrides.optString(key));
                        }
                    }
                }
                try {
                    JSONObject stored = new JSONObject();
                    stored.put("version", 1);
                    JSONObject values = new JSONObject();
                    for (Map.Entry<String, String> entry : albumOverrides.entrySet()) {
                        values.put(entry.getKey(), entry.getValue());
                    }
                    stored.put("albumOverrides", values);
                    writeText(rulesFile(), stored.toString());
                } catch (JSONException ignored) {
                    // Literal keys only.
                } catch (IOException failure) {
                    Log.w(TAG, "cannot persist genre overrides", failure);
                }
            }
        }
        return genreRulesJson();
    }

    public void addSafRoot(String uri, String label) {
        if (uri == null || uri.isEmpty()) return;
        synchronized (lock) {
            for (SafRoot root : safRoots) {
                if (root.uri.equals(uri)) {
                    if (label != null && !label.isEmpty()) root.label = label;
                    saveConfig();
                    return;
                }
            }
            SafRoot root = new SafRoot();
            root.uri = uri;
            root.label = label == null || label.isEmpty() ? "导入文件夹" : label;
            safRoots.add(root);
        }
        saveConfig();
    }

    // ---------------------------------------------------------------- playback

    public Track track(String trackId) {
        synchronized (lock) {
            return trackById.get(trackId);
        }
    }

    /** Opens the audio bytes positioned at {@code offset}; caller closes. */
    public AudioSource openAudio(String trackId, long offset) throws IOException {
        Track track = track(trackId);
        if (track == null) throw new IOException("索引中没有此歌曲");
        long length = track.size;
        if (track.uri.startsWith("content:")) {
            try {
                ContentResolver resolver = context.getContentResolver();
                android.content.res.AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(Uri.parse(track.uri), "r");
                if (descriptor == null) throw new IOException("无法读取音频文件");
                if (descriptor.getLength() > 0) length = descriptor.getLength();
                FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor());
                long position = descriptor.getStartOffset() + Math.max(0, offset);
                if (stream.getChannel().isOpen()) {
                    stream.getChannel().position(position);
                    return new AudioSource(stream, length);
                }
            } catch (Exception seekFailure) {
                Log.d(TAG, "provider stream not seekable, caching file: " + seekFailure.getMessage());
            }
            // Some providers hand out pipes; cache the whole file once.
            File cached = cachedCopy(track);
            FileInputStream stream = new FileInputStream(cached);
            stream.getChannel().position(Math.max(0, offset));
            return new AudioSource(stream, cached.length());
        }
        File file = new File(track.uri);
        if (!file.isFile()) throw new IOException("歌曲文件已不存在");
        FileInputStream stream = new FileInputStream(file);
        stream.getChannel().position(Math.max(0, offset));
        return new AudioSource(stream, file.length());
    }

    public File audioFile(String trackId) {
        Track track = track(trackId);
        if (track == null || track.uri.startsWith("content:")) return null;
        File file = new File(track.uri);
        return file.isFile() ? file : null;
    }

    private File cachedCopy(Track track) throws IOException {
        File target = new File(cacheDir, track.id + "." + track.format.toLowerCase(Locale.US));
        if (target.isFile() && target.length() > 0) return target;
        File temp = new File(cacheDir, track.id + ".part");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = context.getContentResolver().openInputStream(Uri.parse(track.uri));
            if (in == null) throw new IOException("无法读取音频文件");
            out = new FileOutputStream(temp);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
        if (!temp.renameTo(target)) throw new IOException("无法缓存音频文件");
        return target;
    }

    /** Artwork bytes for an album id, or null when the album has no cover. */
    public File artworkFor(String albumId) {
        String key = "";
        synchronized (lock) {
            for (Album album : albums) {
                if (album.id.equals(albumId)) {
                    key = album.coverKey;
                    break;
                }
            }
        }
        if (key.isEmpty()) return null;
        File file = artworkFileByKey(key);
        return file;
    }

    private File artworkFileByKey(String key) {
        File direct = new File(artworkDir, key);
        if (direct.isFile()) return direct;
        File[] candidates = artworkDir.listFiles();
        if (candidates == null) return null;
        for (File candidate : candidates) {
            String name = candidate.getName();
            if (name.equals(key) || name.startsWith(key + ".")) return candidate;
        }
        return null;
    }

    public JSONObject lyrics(String trackId) {
        JSONObject result = new JSONObject();
        try {
            Track track = track(trackId);
            String text = "";
            if (track != null) {
                String source = track.lrcUri;
                if (source == null || source.isEmpty()) {
                    if (!track.dataPath.isEmpty()) {
                        File lyric = findSiblingLyricsFile(new File(track.dataPath));
                        if (lyric != null) source = lyric.getAbsolutePath();
                    }
                }
                if (source != null && !source.isEmpty()) {
                    byte[] bytes = source.startsWith("content:")
                            ? readUri(Uri.parse(source)) : readBytes(new File(source));
                    if (bytes != null && bytes.length <= LYRICS_LIMIT) {
                        text = new String(bytes, "UTF-8");
                        if (text.startsWith("\uFEFF")) text = text.substring(1);
                    }
                }
            }
            result.put("found", !text.isEmpty());
            result.put("text", text);
        } catch (Exception failure) {
            try {
                result.put("found", false);
                result.put("text", "");
            } catch (JSONException ignored) {
                // Literal keys only.
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- helpers

    public static String trackId(String uri) {
        return "track-" + sha256Hex(uri).substring(0, 24);
    }

    /** Stores artwork bytes under a content hash and returns the cache key. */
    public String cacheArtwork(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        try {
            String extension = ".jpg";
            if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50) extension = ".png";
            else if (bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') extension = ".webp";
            String key = sha256Hex(bytes).substring(0, 24);
            File target = new File(artworkDir, key + extension);
            if (!target.isFile()) {
                FileOutputStream out = new FileOutputStream(target);
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
            synchronized (lock) {
                artworkMime.put(key, extension);
            }
            return key;
        } catch (Exception failure) {
            Log.w(TAG, "cannot cache artwork", failure);
            return "";
        }
    }

    private static String findSiblingLyrics(File audioFile) {
        File lyric = findSiblingLyricsFile(audioFile);
        return lyric == null ? "" : lyric.getAbsolutePath();
    }

    private static File findSiblingLyricsFile(File audioFile) {
        if (audioFile == null) return null;
        File parent = audioFile.getParentFile();
        if (parent == null) return null;
        String stem = stem(audioFile.getName());
        File[] candidates = parent.listFiles();
        if (candidates == null) return null;
        for (File candidate : candidates) {
            if (candidate.isFile() && isLyrics(candidate.getName())
                    && stem(candidate.getName()).equalsIgnoreCase(stem)) return candidate;
        }
        return null;
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toUpperCase(Locale.US) : "";
    }

    private static boolean isAudio(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return false;
        return AUDIO_EXTENSIONS.contains(name.substring(dot).toLowerCase(Locale.US));
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private static boolean isLyrics(String name) {
        return name.toLowerCase(Locale.US).endsWith(".lrc");
    }

    private static File preferredCover(List<File> covers) {
        if (covers.isEmpty()) return null;
        List<File> sorted = new ArrayList<File>(covers);
        Collections.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return coverRank(a) - coverRank(b);
            }
        });
        return sorted.get(0);
    }

    private static int coverRank(File file) {
        String stem = stem(file.getName()).toLowerCase(Locale.US);
        if ("cover".equals(stem)) return 0;
        if ("folder".equals(stem)) return 1;
        if ("front".equals(stem)) return 2;
        return 9;
    }

    static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                long numberA = Long.parseLong(a.substring(startA, i));
                long numberB = Long.parseLong(b.substring(startB, j));
                if (numberA != numberB) return numberA < numberB ? -1 : 1;
            } else {
                if (ca != cb) return Character.toLowerCase(ca) - Character.toLowerCase(cb);
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    static String normalizeAlbumName(String value) {
        if (value == null) return "";
        return Normalizer.normalize(Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.US), Normalizer.Form.NFKC);
    }

    private static List<String> splitGenres(String value) {
        List<String> out = new ArrayList<String>();
        if (value == null) return out;
        for (String part : value.split("[/,;、]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) out.add(trimmed);
        }
        return out;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String iso(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    private static String relative(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (filePath.startsWith(rootPath)) {
            String rest = filePath.substring(rootPath.length());
            return rest.startsWith(File.separator) ? rest.substring(1) : rest;
        }
        return file.getName();
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(value.getBytes("UTF-8")));
        } catch (Exception failure) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(value));
        } catch (Exception failure) {
            return Integer.toHexString(value.length);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.US, "%02x", value & 0xFF));
        return builder.toString();
    }

    private static String safeHash(String value) {
        return sha256Hex(value).substring(0, 16);
    }

    static String readText(File file) throws IOException {
        byte[] bytes = readBytes(file);
        return new String(bytes, "UTF-8");
    }

    static byte[] readBytes(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return readAll(in);
        } catch (IOException failure) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    private byte[] readUri(Uri uri) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            return in == null ? null : readAll(in);
        } catch (IOException failure) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    private void copyUriToFile(Uri uri, File target) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) return;
            out = new FileOutputStream(target);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        } catch (Exception failure) {
            if (target.isFile() && !target.delete()) Log.w(TAG, "cannot delete " + target);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[65536];
        int count;
        while ((count = in.read(chunk)) != -1) out.write(chunk, 0, count);
        return out.toByteArray();
    }

    static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            out.write(text.getBytes("UTF-8"));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!temp.renameTo(file)) {
            if (file.exists() && !file.delete()) throw new IOException("cannot replace " + file);
            if (!temp.renameTo(file)) throw new IOException("cannot rename " + temp);
        }
    }

    private static final class SafFile {
        String documentId = "";
        String name = "";
        String mime = "";
        long size;
        long modified;
    }
}
