package com.pvpbot.stabshot.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A tiny HTTP server running on localhost:7891 that:
 *
 *  GET  /player          → serves the Web Playback SDK HTML page
 *  GET  /token           → returns the current Spotify access token (for the SDK)
 *  POST /play?uri=...    → tells the browser player to play a track/playlist URI
 *  POST /pause           → pause
 *  POST /resume          → resume
 *  POST /stop            → stop
 *  POST /next            → next track
 *  POST /prev            → previous track
 *  POST /status          → browser posts back current track info (JSON)
 *  GET  /status          → mod reads current track info
 *
 * The browser page connects to this server via fetch() calls.
 * The mod sends commands and reads state through the same endpoints.
 */
@Environment(EnvType.CLIENT)
public class SpotifyWebServer {

    public static final int PORT = 7891;

    private static SpotifyWebServer instance;
    public static SpotifyWebServer getInstance() {
        if (instance == null) instance = new SpotifyWebServer();
        return instance;
    }

    private ServerSocket serverSocket;
    private Thread       serverThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Pending command for the browser to pick up on next poll
    private final AtomicReference<String> pendingCommand = new AtomicReference<>(null);
    // Last status posted by the browser
    private final AtomicReference<JsonObject> lastStatus = new AtomicReference<>(null);

    // -----------------------------------------------------------------------
    // Start / stop
    // -----------------------------------------------------------------------

    public void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket(PORT, 10, InetAddress.getByName("127.0.0.1"));
        running.set(true);
        serverThread = new Thread(this::loop, "SpotifyWebServer");
        serverThread.setDaemon(true);
        serverThread.start();
        System.out.println("[StabShot Spotify] Web server started on http://127.0.0.1:" + PORT);
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
    }

    public boolean isRunning() { return running.get(); }

    // -----------------------------------------------------------------------
    // Command API (called by SpotifyCommand)
    // -----------------------------------------------------------------------

    public void play(String spotifyUri) {
        pendingCommand.set("play:" + spotifyUri);
    }

    public void pause()  { pendingCommand.set("pause");  }
    public void resume() { pendingCommand.set("resume"); }
    public void next()   { pendingCommand.set("next");   }
    public void prev()   { pendingCommand.set("prev");   }
    public void stopPlayback() { pendingCommand.set("stop"); }

    public JsonObject getStatus() { return lastStatus.get(); }

    /** Opens the player page in the system browser (PC) or prints URL (mobile). */
    public void openPlayer() {
        String url = "http://127.0.0.1:" + PORT + "/player";
        SpotifyAuth.openBrowserOrPrint(url);
    }

    // -----------------------------------------------------------------------
    // Server loop
    // -----------------------------------------------------------------------

    private void loop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                // Handle each request in a short-lived thread
                new Thread(() -> handleClient(client), "SpotifyWS-Client").start();
            } catch (Exception e) {
                if (running.get()) System.err.println("[SpotifyWebServer] Accept error: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        try (client) {
            BufferedReader in  = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream   out = client.getOutputStream();

            // Read request line + headers
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isBlank()) return;

            String method = requestLine.split(" ")[0];
            String path   = requestLine.split(" ")[1];

            // Read remaining headers (needed for Content-Length on POST)
            int contentLength = 0;
            String line;
            while (!(line = in.readLine()).isBlank()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                }
            }

            // Read body if POST
            String body = "";
            if ("POST".equals(method) && contentLength > 0) {
                char[] buf = new char[contentLength];
                in.read(buf, 0, contentLength);
                body = new String(buf);
            }

            // Route
            String pathOnly = path.split("\\?")[0];
            switch (pathOnly) {
                case "/player" -> servePlayer(out);
                case "/token"  -> serveToken(out);
                case "/play"   -> {
                    String uri = extractQuery(path, "uri");
                    pendingCommand.set("play:" + uri);
                    serveOk(out, "{}");
                }
                case "/pause"  -> { pendingCommand.set("pause");  serveOk(out, "{}"); }
                case "/resume" -> { pendingCommand.set("resume"); serveOk(out, "{}"); }
                case "/next"   -> { pendingCommand.set("next");   serveOk(out, "{}"); }
                case "/prev"   -> { pendingCommand.set("prev");   serveOk(out, "{}"); }
                case "/stop"   -> { pendingCommand.set("stop");   serveOk(out, "{}"); }
                case "/status" -> {
                    if ("POST".equals(method)) {
                        // Browser posting its current state
                        if (!body.isBlank()) {
                            try {
                                lastStatus.set(JsonParser.parseString(body).getAsJsonObject());
                            } catch (Exception ignored) {}
                        }
                        serveOk(out, "{}");
                    } else {
                        // Mod reading state
                        JsonObject s = lastStatus.get();
                        serveOk(out, s != null ? s.toString() : "{}");
                    }
                }
                case "/poll"   -> {
                    // Browser polls for pending commands
                    String cmd = pendingCommand.getAndSet(null);
                    serveOk(out, cmd != null ? "{\"cmd\":\"" + cmd + "\"}" : "{\"cmd\":null}");
                }
                default -> serve404(out);
            }
        } catch (Exception e) {
            System.err.println("[SpotifyWebServer] Handler error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // The Web Playback SDK HTML page
    // -----------------------------------------------------------------------

    private void servePlayer(OutputStream out) throws IOException {
        String html = buildPlayerHtml();
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static String buildPlayerHtml() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Vulgar's OSC – Spotify Player</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background: #121212; color: #fff;
    font-family: 'Segoe UI', Arial, sans-serif;
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    min-height: 100vh; gap: 24px; padding: 24px;
  }
  .card {
    background: #1e1e1e; border-radius: 16px;
    padding: 32px; width: 100%; max-width: 420px;
    box-shadow: 0 8px 32px rgba(0,0,0,0.5);
    display: flex; flex-direction: column; gap: 20px;
  }
  .logo { color: #1db954; font-size: 13px; font-weight: 700; letter-spacing: 2px; text-transform: uppercase; }
  .track-name { font-size: 22px; font-weight: 700; line-height: 1.2; }
  .track-artist { font-size: 14px; color: #aaa; }
  .album-art { width: 100%; aspect-ratio: 1; border-radius: 12px; object-fit: cover; background: #333; display: block; }
  .controls { display: flex; align-items: center; justify-content: center; gap: 20px; }
  .btn {
    background: none; border: none; color: #fff; cursor: pointer;
    font-size: 28px; display: flex; align-items: center; justify-content: center;
    width: 48px; height: 48px; border-radius: 50%;
    transition: background 0.15s, transform 0.1s;
  }
  .btn:hover { background: rgba(255,255,255,0.1); transform: scale(1.1); }
  .btn.play-pause {
    background: #1db954; font-size: 24px;
    width: 56px; height: 56px;
  }
  .btn.play-pause:hover { background: #1ed760; }
  .progress-wrap { display: flex; flex-direction: column; gap: 6px; }
  .progress-bar { width: 100%; height: 4px; background: #333; border-radius: 2px; overflow: hidden; }
  .progress-fill { height: 100%; background: #1db954; border-radius: 2px; width: 0%; transition: width 0.5s linear; }
  .times { display: flex; justify-content: space-between; font-size: 11px; color: #aaa; }
  .status { font-size: 12px; color: #aaa; text-align: center; }
  .status.error { color: #e74c3c; }
  .status.ok { color: #1db954; }
  h2 { font-size: 16px; color: #aaa; text-align: center; }
</style>
</head>
<body>
<div class="card">
  <div class="logo">▶ Vulgar's OSC — Spotify</div>
  <img class="album-art" id="art" src="" alt="">
  <div>
    <div class="track-name" id="trackName">Not playing</div>
    <div class="track-artist" id="trackArtist">—</div>
  </div>
  <div class="progress-wrap">
    <div class="progress-bar"><div class="progress-fill" id="progress"></div></div>
    <div class="times"><span id="posTime">0:00</span><span id="durTime">0:00</span></div>
  </div>
  <div class="controls">
    <button class="btn" id="btnPrev" title="Previous">⏮</button>
    <button class="btn play-pause" id="btnPlay" title="Play/Pause">▶</button>
    <button class="btn" id="btnNext" title="Next">⏭</button>
  </div>
  <div class="status" id="status">Initialising…</div>
</div>

<script src="https://sdk.scdn.co/spotify-player.js"></script>
<script>
const BASE = 'http://127.0.0.1:7891';
let player, deviceId, paused = true, duration = 0, position = 0;

function setStatus(msg, type) {
  const el = document.getElementById('status');
  el.textContent = msg;
  el.className = 'status' + (type ? ' ' + type : '');
}

function fmt(ms) {
  const s = Math.floor(ms / 1000);
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}

// Fetch token from mod
async function getToken() {
  const r = await fetch(BASE + '/token');
  const j = await r.json();
  return j.token;
}

// Post status back to mod
async function postStatus(data) {
  await fetch(BASE + '/status', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data)
  }).catch(() => {});
}

// Poll for commands from mod
async function pollCommands() {
  try {
    const r = await fetch(BASE + '/poll');
    const j = await r.json();
    if (j.cmd) {
      const cmd = j.cmd;
      if (cmd.startsWith('play:')) {
        const uri = cmd.slice(5);
        await playUri(uri);
      } else if (cmd === 'pause')  { player?.pause(); }
      else if (cmd === 'resume')   { player?.resume(); }
      else if (cmd === 'next')     { player?.nextTrack(); }
      else if (cmd === 'prev')     { player?.previousTrack(); }
      else if (cmd === 'stop')     { player?.pause(); }
    }
  } catch(e) {}
  setTimeout(pollCommands, 500);
}

async function playUri(uri) {
  if (!deviceId) { setStatus('Player not ready yet', 'error'); return; }
  const token = await getToken();
  // Transfer playback to this device first, then play
  await fetch('https://api.spotify.com/v1/me/player', {
    method: 'PUT',
    headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' },
    body: JSON.stringify({ device_ids: [deviceId], play: false })
  });
  // Determine if URI is a track or something else
  const body = uri.startsWith('spotify:track:')
    ? { uris: [uri] }
    : { context_uri: uri };
  await fetch('https://api.spotify.com/v1/me/player/play?device_id=' + deviceId, {
    method: 'PUT',
    headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

// Progress ticker
setInterval(() => {
  if (!paused && duration > 0) {
    position = Math.min(position + 500, duration);
    document.getElementById('progress').style.width = (position / duration * 100) + '%';
    document.getElementById('posTime').textContent = fmt(position);
  }
}, 500);

window.onSpotifyWebPlaybackSDKReady = async () => {
  setStatus('SDK loaded, connecting…');
  const token = await getToken();

  player = new Spotify.Player({
    name: "Vulgar's OSC",
    getOAuthToken: async cb => { cb(await getToken()); },
    volume: 0.8
  });

  player.addListener('ready', ({ device_id }) => {
    deviceId = device_id;
    setStatus('Ready! Use /ts spotify play <song> in Minecraft.', 'ok');
    console.log('[SpotifySDK] Device ID:', device_id);
    pollCommands();
  });

  player.addListener('not_ready', () => {
    deviceId = null;
    setStatus('Player went offline.', 'error');
  });

  player.addListener('player_state_changed', state => {
    if (!state) return;
    paused   = state.paused;
    duration = state.duration;
    position = state.position;

    const track = state.track_window?.current_track;
    if (track) {
      document.getElementById('trackName').textContent   = track.name;
      document.getElementById('trackArtist').textContent = track.artists.map(a => a.name).join(', ');
      const img = track.album?.images?.[0]?.url;
      if (img) document.getElementById('art').src = img;
      document.getElementById('durTime').textContent = fmt(duration);
      document.getElementById('btnPlay').textContent = paused ? '▶' : '⏸';

      postStatus({
        playing:  !paused,
        name:     track.name,
        artist:   track.artists.map(a => a.name).join(', '),
        album:    track.album?.name || '',
        uri:      track.uri,
        art:      img || '',
        position: position,
        duration: duration
      });
    }
  });

  player.addListener('initialization_error', ({ message }) => setStatus('Init error: ' + message, 'error'));
  player.addListener('authentication_error',  ({ message }) => setStatus('Auth error: ' + message, 'error'));
  player.addListener('account_error',         ({ message }) => setStatus('Account error (Premium required?): ' + message, 'error'));

  document.getElementById('btnPlay').onclick = () => player.togglePlay();
  document.getElementById('btnNext').onclick = () => player.nextTrack();
  document.getElementById('btnPrev').onclick = () => player.previousTrack();

  player.connect().then(ok => {
    if (!ok) setStatus('Failed to connect to Spotify.', 'error');
  });
};
</script>
</body>
</html>
""";
    }

    // -----------------------------------------------------------------------
    // HTTP helpers
    // -----------------------------------------------------------------------

    private void serveToken(OutputStream out) throws IOException {
        String token = "null";
        try {
            token = "\"" + SpotifyAuth.getInstance().getAccessToken() + "\"";
        } catch (Exception ignored) {}
        serveOk(out, "{\"token\":" + token + "}");
    }

    private void serveOk(OutputStream out, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private void serve404(OutputStream out) throws IOException {
        byte[] bytes = "Not found".getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 404 Not Found\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static String extractQuery(String path, String key) {
        if (!path.contains("?")) return "";
        String query = path.split("\\?", 2)[1];
        for (String param : query.split("&")) {
            if (param.startsWith(key + "=")) {
                return URLDecoder.decode(param.substring(key.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
