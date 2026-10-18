package com.rhine.music;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Playlists stored as one JSON document in the app's private data directory.
 *
 * <p>Items keep a snapshot of the track metadata so a playlist still renders
 * while a removed song is unavailable, mirroring the Windows payload.</p>
 */
public final class PlaylistStore {

    private static final String TAG = "RhinePlaylists";
    private final File file;
    private final MusicLibrary library;
    private final Object lock = new Object();
    private JSONObject data = new JSONObject();
    private final SecureRandom random = new SecureRandom();

    public PlaylistStore(File dataDir, MusicLibrary library) {
        this.file = new File(dataDir, "playlists.json");
        this.library = library;
        load();
    }

    private void load() {
        try {
            if (file.isFile()) {
                data = new JSONObject(MusicLibrary.readText(file));
            }
        } catch (Exception failure) {
            Log.w(TAG, "cannot read playlists", failure);
        }
        if (data.optJSONArray("playlists") == null) {
            try {
                data = new JSONObject();
                data.put("version", 1);
                data.put("playlists", new JSONArray());
            } catch (JSONException ignored) {
                // Literal keys only.
            }
        }
    }

    private void save() {
        try {
            MusicLibrary.writeText(file, data.toString());
        } catch (Exception failure) {
            Log.w(TAG, "cannot persist playlists", failure);
        }
    }

    private JSONArray rawPlaylists() {
        JSONArray list = data.optJSONArray("playlists");
        return list == null ? new JSONArray() : list;
    }

    private JSONObject rawPlaylist(String id) {
        JSONArray list = rawPlaylists();
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("id"))) return entry;
        }
        return null;
    }

    /** Serialized playlists with their tracks resolved against the library. */
    public JSONArray list() {
        synchronized (lock) {
            JSONArray out = new JSONArray();
            JSONArray list = rawPlaylists();
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry == null) continue;
                out.put(serialize(entry));
            }
            return out;
        }
    }

    private JSONObject serialize(JSONObject entry) {
        JSONObject out = new JSONObject();
        try {
            out.put("id", entry.optString("id"));
            out.put("name", entry.optString("name"));
            out.put("createdAt", entry.optString("createdAt"));
            out.put("updatedAt", entry.optString("updatedAt"));
            JSONArray items = entry.optJSONArray("items");
            JSONArray tracks = new JSONArray();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    JSONObject view = new JSONObject(item.toString());
                    MusicLibrary.Track resolved = library.track(item.optString("trackId"));
                    view.put("available", resolved != null);
                    if (resolved != null) {
                        JSONObject track = resolved.toJson();
                        track.put("albumId", item.optString("albumId"));
                        view.put("track", track);
                    }
                    tracks.put(view);
                }
            }
            out.put("tracks", tracks);
        } catch (JSONException ignored) {
            // Field names are literals.
        }
        return out;
    }

    public JSONObject find(String id) {
        synchronized (lock) {
            JSONObject entry = rawPlaylist(id);
            return entry == null ? null : serialize(entry);
        }
    }

    public JSONObject create(String name) {
        synchronized (lock) {
            JSONObject entry = new JSONObject();
            String id = uuid();
            String now = iso();
            try {
                entry.put("id", id);
                entry.put("name", name);
                entry.put("createdAt", now);
                entry.put("updatedAt", now);
                entry.put("items", new JSONArray());
                rawPlaylists().put(entry);
            } catch (JSONException ignored) {
                // Field names are literals.
            }
            save();
            return find(id);
        }
    }

    public JSONObject rename(String id, String name) {
        synchronized (lock) {
            JSONObject entry = rawPlaylist(id);
            if (entry == null) return null;
            try {
                entry.put("name", name);
                entry.put("updatedAt", iso());
            } catch (JSONException ignored) {
                // Field names are literals.
            }
            save();
            return find(id);
        }
    }

    public boolean delete(String id) {
        synchronized (lock) {
            JSONArray list = rawPlaylists();
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry != null && id.equals(entry.optString("id"))) {
                    list.remove(i);
                    save();
                    return true;
                }
            }
            return false;
        }
    }

    public JSONObject addTracks(String id, JSONArray trackIds) {
        synchronized (lock) {
            JSONObject entry = rawPlaylist(id);
            if (entry == null) return null;
            JSONArray items = entry.optJSONArray("items");
            if (items == null) {
                items = new JSONArray();
                try {
                    entry.put("items", items);
                } catch (JSONException ignored) {
                    // Field names are literals.
                }
            }
            int added = 0;
            int skipped = 0;
            for (int i = 0; i < trackIds.length(); i++) {
                String trackId = trackIds.optString(i);
                MusicLibrary.Track track = library.track(trackId);
                if (track == null) {
                    skipped++;
                    continue;
                }
                boolean exists = false;
                for (int j = 0; j < items.length(); j++) {
                    JSONObject existing = items.optJSONObject(j);
                    if (existing != null && trackId.equals(existing.optString("trackId"))) {
                        exists = true;
                        break;
                    }
                }
                if (exists) {
                    skipped++;
                    continue;
                }
                JSONObject item = new JSONObject();
                try {
                    item.put("id", randomHex(24));
                    item.put("trackId", trackId);
                    item.put("albumId", albumIdOf(trackId));
                    item.put("title", track.title.isEmpty() ? track.displayName : track.title);
                    item.put("artist", track.artist.isEmpty() ? "未知艺术家" : track.artist);
                    item.put("album", track.albumTitle);
                    item.put("duration", Math.max(0, track.durationMs) / 1000.0);
                } catch (JSONException ignored) {
                    // Field names are literals.
                }
                items.put(item);
                added++;
            }
            try {
                entry.put("updatedAt", iso());
            } catch (JSONException ignored) {
                // Field names are literals.
            }
            save();
            return summary(added, skipped);
        }
    }

    public JSONObject summary(int added, int skipped) {
        JSONObject out = new JSONObject();
        try {
            out.put("added", added);
            out.put("skipped", skipped);
        } catch (JSONException ignored) {
            // Field names are literals.
        }
        return out;
    }

    public JSONObject reorder(String id, JSONArray itemIds) {
        synchronized (lock) {
            JSONObject entry = rawPlaylist(id);
            if (entry == null) return null;
            JSONArray items = entry.optJSONArray("items");
            if (items == null) return find(id);
            JSONArray ordered = new JSONArray();
            for (int i = 0; i < itemIds.length(); i++) {
                String itemId = itemIds.optString(i);
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item != null && itemId.equals(item.optString("id"))) {
                        ordered.put(item);
                        break;
                    }
                }
            }
            // Anything the client did not mention keeps its relative position.
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.optJSONObject(j);
                if (item == null) continue;
                boolean present = false;
                for (int k = 0; k < ordered.length(); k++) {
                    JSONObject kept = ordered.optJSONObject(k);
                    if (kept != null && kept.optString("id").equals(item.optString("id"))) {
                        present = true;
                        break;
                    }
                }
                if (!present) ordered.put(item);
            }
            try {
                entry.put("items", ordered);
                entry.put("updatedAt", iso());
            } catch (JSONException ignored) {
                // Field names are literals.
            }
            save();
            return find(id);
        }
    }

    public JSONObject removeTrack(String playlistId, String itemId) {
        synchronized (lock) {
            JSONObject entry = rawPlaylist(playlistId);
            if (entry == null) return null;
            JSONArray items = entry.optJSONArray("items");
            if (items != null) {
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item != null && itemId.equals(item.optString("id"))) {
                        items.remove(j);
                        break;
                    }
                }
            }
            try {
                entry.put("updatedAt", iso());
            } catch (JSONException ignored) {
                // Field names are literals.
            }
            save();
            return find(playlistId);
        }
    }

    private String albumIdOf(String trackId) {
        MusicLibrary.Track track = library.track(trackId);
        return track == null ? "" : track.albumTitle;
    }

    private String randomHex(int length) {
        StringBuilder builder = new StringBuilder(length);
        String alphabet = "0123456789abcdef";
        for (int i = 0; i < length; i++) builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return builder.toString();
    }

    private String uuid() {
        String hex = randomHex(32);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
    }

    private static String iso() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
