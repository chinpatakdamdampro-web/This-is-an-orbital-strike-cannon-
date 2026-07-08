package com.pvpbot.stabshot.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

@Environment(EnvType.CLIENT)
public class SpotifyAuth {

    public static final String CLIENT_ID    = "72ae379dfa6c40a09006532c136cbbdf";
    // 127.0.0.1 required — Spotify no longer accepts "localhost" as of April 2025
    public static final String REDIRECT_URI = "http://127.0.0.1:7890/callback";

    private static final String SCOPES =
            "streaming user-read-playback-state user-modify-playback-state " +
            "user-read-currently-playing user-read-private user-read-email";

    private String accessToken;
    private String refreshToken;
    private long   tokenExpiresAt;
    private String codeVerifier;

    private static SpotifyAuth instance;
    public static SpotifyAuth getInstance() {
        if (instance == null) instance = new SpotifyAuth();
        return instance;
    }
    private SpotifyAuth() {}

    public boolean isAuthenticated() { return accessToken != null; }

    public String getAccessToken() throws Exception {
        if (accessToken == null) throw new IllegalStateException("Not authenticated.");
        if (System.currentTimeMillis() > tokenExpiresAt - 60_000) refreshAccessToken();
        return accessToken;
    }

    public CompletableFuture<Void> startLogin() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        new Thread(() -> {
            try {
                codeVerifier = generateCodeVerifier();
                String codeChallenge = generateCodeChallenge(codeVerifier);
                String authUrl = buildAuthUrl(codeChallenge);
                openBrowserOrPrint(authUrl);
                String code = startCallbackServer();
                exchangeCodeForTokens(code);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, "SpotifyAuth-Thread").start();
        return future;
    }

    public String buildAuthUrl(String codeChallenge) {
        return "https://accounts.spotify.com/authorize"
                + "?client_id="             + CLIENT_ID
                + "&response_type=code"
                + "&redirect_uri="          + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                + "&code_challenge_method=S256"
                + "&code_challenge="        + codeChallenge
                + "&scope="                 + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8);
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeChallenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    public static void openBrowserOrPrint(String url) {
        boolean opened = false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                opened = true;
            }
        } catch (Exception ignored) {}
        if (!opened) {
            System.out.println("[StabShot Spotify] Open this URL to log in:\n" + url);
            SpotifyState.setPendingAuthUrl(url);
        }
    }

    private static String startCallbackServer() throws Exception {
        try (ServerSocket ss = new ServerSocket(7890, 1, InetAddress.getByName("127.0.0.1"))) {
            ss.setSoTimeout(120_000);
            try (Socket client = ss.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String requestLine = in.readLine();
                String body = "<html><body><h2 style='font-family:sans-serif'>Logged in to Spotify! You can close this tab and return to Minecraft.</h2></body></html>";
                PrintWriter out = new PrintWriter(client.getOutputStream());
                out.println("HTTP/1.1 200 OK");
                out.println("Content-Type: text/html");
                out.println("Content-Length: " + body.length());
                out.println("Connection: close");
                out.println();
                out.println(body);
                out.flush();
                if (requestLine == null) throw new IOException("Empty Spotify callback.");
                String query = requestLine.split(" ")[1].split("\\?", 2)[1];
                for (String param : query.split("&")) {
                    if (param.startsWith("code=")) return URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                }
                throw new IOException("No code in Spotify callback.");
            }
        }
    }

    private void exchangeCodeForTokens(String code) throws Exception {
        String body = "grant_type=authorization_code"
                + "&code="          + URLEncoder.encode(code,         StandardCharsets.UTF_8)
                + "&redirect_uri="  + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                + "&client_id="     + CLIENT_ID
                + "&code_verifier=" + codeVerifier;
        applyTokenResponse(postToken(body));
    }

    private void refreshAccessToken() throws Exception {
        String body = "grant_type=refresh_token"
                + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                + "&client_id="     + CLIENT_ID;
        applyTokenResponse(postToken(body));
    }

    private static JsonObject postToken(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://accounts.spotify.com/api/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new IOException("Spotify token error " + resp.statusCode() + ": " + resp.body());
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private void applyTokenResponse(JsonObject json) {
        accessToken    = json.get("access_token").getAsString();
        tokenExpiresAt = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000;
        if (json.has("refresh_token") && !json.get("refresh_token").isJsonNull())
            refreshToken = json.get("refresh_token").getAsString();
    }

    public void saveTokens(Path configDir) {
        if (accessToken == null) return;
        try (PrintWriter pw = new PrintWriter(configDir.resolve("stabshot_spotify_tokens.txt").toFile())) {
            pw.println(accessToken);
            pw.println(refreshToken != null ? refreshToken : "");
            pw.println(tokenExpiresAt);
        } catch (Exception e) {
            System.err.println("[StabShot Spotify] Could not save tokens: " + e.getMessage());
        }
    }

    public boolean loadTokens(Path configDir) {
        java.io.File file = configDir.resolve("stabshot_spotify_tokens.txt").toFile();
        if (!file.exists()) return false;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            accessToken    = br.readLine();
            String rt      = br.readLine();
            refreshToken   = (rt != null && !rt.isBlank()) ? rt : null;
            tokenExpiresAt = Long.parseLong(br.readLine().trim());
            return accessToken != null && !accessToken.isBlank();
        } catch (Exception e) { return false; }
    }
}
