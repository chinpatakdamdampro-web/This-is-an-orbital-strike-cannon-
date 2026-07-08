package com.pvpbot.stabshot.spotify;

import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * SpotifyPlayer — thin wrapper around SpotifyWebServer.
 * All actual playback now happens in the browser via the Web Playback SDK.
 * This class just forwards commands to the web server and reads status back.
 */
@Environment(EnvType.CLIENT)
public class SpotifyPlayer {

    private static SpotifyPlayer instance;
    public static SpotifyPlayer getInstance() {
        if (instance == null) instance = new SpotifyPlayer();
        return instance;
    }
    private SpotifyPlayer() {}

    // -----------------------------------------------------------------------
    // Playback commands — all delegated to the web server
    // -----------------------------------------------------------------------

    public String play(String spotifyUri) {
        ensureServerRunning();
        SpotifyWebServer.getInstance().play(spotifyUri);
        return "[Spotify] Playing: " + spotifyUri;
    }

    public String pause() {
        SpotifyWebServer.getInstance().pause();
        return "[Spotify] Paused.";
    }

    public String resume() {
        SpotifyWebServer.getInstance().resume();
        return "[Spotify] Resumed.";
    }

    public String next() {
        SpotifyWebServer.getInstance().next();
        return "[Spotify] Skipped to next track.";
    }

    public String prev() {
        SpotifyWebServer.getInstance().prev();
        return "[Spotify] Going to previous track.";
    }

    public String stop() {
        SpotifyWebServer.getInstance().stopPlayback();
        return "[Spotify] Stopped.";
    }

    // -----------------------------------------------------------------------
    // Search then play
    // -----------------------------------------------------------------------

    public String playSearch(String query) {
        ensureServerRunning();
        try {
            var results = SpotifyApi.searchTracks(query, 1);
            if (results.isEmpty()) return "[Spotify] No results for: " + query;
            SpotifyApi.TrackInfo track = results.get(0);
            SpotifyWebServer.getInstance().play(track.spotifyUri);
            return "[Spotify] Playing: " + track.name + " — " + track.artist;
        } catch (Exception e) {
            return "[Spotify] Error: " + e.getMessage();
        }
    }

    // -----------------------------------------------------------------------
    // Status
    // -----------------------------------------------------------------------

    public String getStatusString() {
        JsonObject s = SpotifyWebServer.getInstance().getStatus();
        if (s == null || !s.has("name")) return "[Spotify] Nothing playing (or player page not open).";
        String name    = s.get("name").getAsString();
        String artist  = s.get("artist").getAsString();
        boolean playing = s.has("playing") && s.get("playing").getAsBoolean();
        return "[Spotify] " + (playing ? "▶ " : "⏸ ") + name + " — " + artist;
    }

    public boolean isPlaying() {
        JsonObject s = SpotifyWebServer.getInstance().getStatus();
        return s != null && s.has("playing") && s.get("playing").getAsBoolean();
    }

    // -----------------------------------------------------------------------
    // Open browser to player page
    // -----------------------------------------------------------------------

    public void openPlayer() {
        ensureServerRunning();
        SpotifyWebServer.getInstance().openPlayer();
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void ensureServerRunning() {
        if (!SpotifyWebServer.getInstance().isRunning()) {
            try {
                SpotifyWebServer.getInstance().start();
            } catch (Exception e) {
                System.err.println("[SpotifyPlayer] Could not start web server: " + e.getMessage());
            }
        }
    }
}
