package com.pvpbot.stabshot.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around the Spotify Web API.
 *
 * Note on audio playback:
 *   The Spotify Web API does NOT provide direct MP3 download URLs for full
 *   tracks – that would require a Spotify Connect active device.  What we
 *   CAN do (and what this class does) is use the 30-second preview_url that
 *   Spotify includes on each track object.  These are plain MP3 URLs that
 *   require no auth header and work in JLayer on both PC and PojavLauncher.
 *
 *   For full-track playback the approach is:
 *     - Control Spotify Connect (play/pause/seek) via the Player API, so the
 *       song actually plays in the user's Spotify app.
 *     - Stream the preview MP3 inside Minecraft as a "now-playing preview".
 *
 *   This dual approach is implemented in SpotifyPlayer.
 */
@Environment(EnvType.CLIENT)
public class SpotifyApi {

    private static final String BASE = "https://api.spotify.com/v1";

    // -----------------------------------------------------------------------
    // Track search
    // -----------------------------------------------------------------------

    public static class TrackInfo {
        public final String id;
        public final String name;
        public final String artist;
        public final String album;
        public final String previewUrl;   // 30-second MP3, may be null
        public final String spotifyUri;   // spotify:track:<id>

        public TrackInfo(String id, String name, String artist,
                         String album, String previewUrl, String spotifyUri) {
            this.id         = id;
            this.name       = name;
            this.artist     = artist;
            this.album      = album;
            this.previewUrl = previewUrl;
            this.spotifyUri = spotifyUri;
        }

        @Override
        public String toString() {
            return name + " – " + artist + (previewUrl != null ? "" : " [no preview]");
        }
    }

    /**
     * Search for tracks matching {@code query}.
     * @param limit max results (1-50)
     */
    public static List<TrackInfo> searchTracks(String query, int limit) throws Exception {
        String token = SpotifyAuth.getInstance().getAccessToken();
        String url   = BASE + "/search?type=track&limit=" + limit
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        JsonObject json = get(url, token);
        JsonArray  items = json.getAsJsonObject("tracks").getAsJsonArray("items");

        List<TrackInfo> results = new ArrayList<>();
        for (JsonElement el : items) {
            JsonObject track  = el.getAsJsonObject();
            String     id     = track.get("id").getAsString();
            String     name   = track.get("name").getAsString();
            String     artist = track.getAsJsonArray("artists")
                                     .get(0).getAsJsonObject()
                                     .get("name").getAsString();
            String album      = track.getAsJsonObject("album")
                                     .get("name").getAsString();
            String preview    = track.has("preview_url") && !track.get("preview_url").isJsonNull()
                                ? track.get("preview_url").getAsString() : null;
            String uri        = track.get("uri").getAsString();
            results.add(new TrackInfo(id, name, artist, album, preview, uri));
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // Currently playing
    // -----------------------------------------------------------------------

    /** Returns the currently playing track, or null if nothing is active. */
    public static TrackInfo currentlyPlaying() throws Exception {
        String token = SpotifyAuth.getInstance().getAccessToken();
        String url   = BASE + "/me/player/currently-playing";

        HttpResponse<String> resp = rawGet(url, token);
        if (resp.statusCode() == 204 || resp.body().isBlank()) return null; // nothing playing

        JsonObject json  = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonElement item = json.get("item");
        if (item == null || item.isJsonNull()) return null;

        JsonObject track  = item.getAsJsonObject();
        String     id     = track.get("id").getAsString();
        String     name   = track.get("name").getAsString();
        String     artist = track.getAsJsonArray("artists")
                                 .get(0).getAsJsonObject()
                                 .get("name").getAsString();
        String album      = track.getAsJsonObject("album").get("name").getAsString();
        String preview    = track.has("preview_url") && !track.get("preview_url").isJsonNull()
                            ? track.get("preview_url").getAsString() : null;
        String uri        = track.get("uri").getAsString();
        return new TrackInfo(id, name, artist, album, preview, uri);
    }

    // -----------------------------------------------------------------------
    // Playback control (Spotify Connect – plays in the Spotify app)
    // -----------------------------------------------------------------------

    /** Tells Spotify Connect to play a specific track URI. */
    public static void playTrack(String spotifyUri) throws Exception {
        String token = SpotifyAuth.getInstance().getAccessToken();
        String body  = "{\"uris\":[\"" + spotifyUri + "\"]}";
        put(BASE + "/me/player/play", token, body);
    }

    public static void pause() throws Exception {
        String token = SpotifyAuth.getInstance().getAccessToken();
        put(BASE + "/me/player/pause", token, null);
    }

    public static void resume() throws Exception {
        String token = SpotifyAuth.getInstance().getAccessToken();
        put(BASE + "/me/player/play", token, null);
    }

    public static void nextTrack() throws Exception {
        String token = SpotifyAuth.getInstance().getAccessToken();
        post(BASE + "/me/player/next", token, null);
    }

    public static void previousTrack() throws Exception {
        String token = SpotifyAuth.getInstance().getAccessToken();
        post(BASE + "/me/player/previous", token, null);
    }

    // -----------------------------------------------------------------------
    // Playlist tracks
    // -----------------------------------------------------------------------

    /** Returns up to 50 tracks from a Spotify playlist. */
    public static List<TrackInfo> getPlaylistTracks(String playlistId) throws Exception {
        String token = SpotifyAuth.getInstance().getAccessToken();
        String url   = BASE + "/playlists/" + playlistId + "/tracks?limit=50&fields=items(track(id,name,artists,album,preview_url,uri))";

        JsonObject json = get(url, token);
        JsonArray  items = json.getAsJsonArray("items");

        List<TrackInfo> results = new ArrayList<>();
        for (JsonElement el : items) {
            JsonObject wrapper = el.getAsJsonObject();
            if (wrapper.get("track").isJsonNull()) continue;
            JsonObject track  = wrapper.getAsJsonObject("track");
            String     id     = track.get("id").getAsString();
            String     name   = track.get("name").getAsString();
            String     artist = track.getAsJsonArray("artists")
                                     .get(0).getAsJsonObject()
                                     .get("name").getAsString();
            String album      = track.getAsJsonObject("album").get("name").getAsString();
            String preview    = track.has("preview_url") && !track.get("preview_url").isJsonNull()
                                ? track.get("preview_url").getAsString() : null;
            String uri        = track.get("uri").getAsString();
            results.add(new TrackInfo(id, name, artist, album, preview, uri));
        }
        return results;
    }

    // -----------------------------------------------------------------------
    // HTTP helpers
    // -----------------------------------------------------------------------

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static JsonObject get(String url, String token) throws Exception {
        HttpResponse<String> resp = rawGet(url, token);
        if (resp.statusCode() >= 400) {
            throw new IOException("Spotify API error " + resp.statusCode() + ": " + resp.body());
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private static HttpResponse<String> rawGet(String url, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void put(String url, String token, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = (body != null)
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(publisher)
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("Spotify API error " + resp.statusCode() + ": " + resp.body());
        }
    }

    private static void post(String url, String token, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = (body != null)
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(publisher)
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("Spotify API error " + resp.statusCode() + ": " + resp.body());
        }
    }

    // Let IOException be used without import clash
    private static class IOException extends java.io.IOException {
        IOException(String msg) { super(msg); }
    }
}
