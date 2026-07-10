package com.pvpbot.stabshot.youtube;

import com.pvpbot.stabshot.themesong.ThemeSongPlayer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles YouTube audio streaming and caching.
 *
 * <h3>Key changes from previous version</h3>
 * <ul>
 *   <li>{@code java.net.http.HttpClient} replaced with {@link HttpURLConnection}
 *       throughout (HTTP download, HEAD verification). {@code HttpClient} is a
 *       Java 11 API absent on the Android JREs used by ZalithLauncher 1,
 *       PojavLauncher and MojoLauncher. {@link HttpURLConnection} is available
 *       everywhere.</li>
 *   <li>HEAD-request URL verification is kept but now uses
 *       {@link HttpURLConnection} with a short connect-timeout to avoid
 *       blocking the caller for too long on flaky connections.</li>
 *   <li>Download streaming uses chunked I/O so large audio files (20–100 MB)
 *       do not accumulate in memory before being written to disk.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class YtPlayer {

    private static final long   MAX_CACHE_BYTES = 100L * 1024 * 1024; // 100 MB
    private static final String CACHE_FOLDER    = "stabshot/yt_cache";

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static YtPlayer instance;
    public static YtPlayer getInstance() {
        if (instance == null) instance = new YtPlayer();
        return instance;
    }
    private YtPlayer() {}

    // ── State ──────────────────────────────────────────────────────────────────
    private final AtomicBoolean           loopActive    = new AtomicBoolean(false);
    private final AtomicReference<String> currentTitle  = new AtomicReference<>(null);
    private final AtomicReference<String> currentVideoId = new AtomicReference<>(null);
    private final AtomicBoolean           playing       = new AtomicBoolean(false);
    private Thread                        playThread;

    // ── Public API ─────────────────────────────────────────────────────────────

    public void play(YtSearch.YtResult result, boolean loop) {
        stopInternal();
        loopActive.set(loop);
        currentTitle.set(result.title());
        currentVideoId.set(result.videoId());
        playing.set(true);

        playThread = new Thread(() -> {
            try {
                do {
                    Path cached = getCachedFile(result.videoId());
                    if (cached != null) {
                        ThemeSongPlayer.playFile(cached);
                        waitForPlayback(cached);
                    } else {
                        streamAndCache(result);
                    }
                } while (loopActive.get() && !Thread.currentThread().isInterrupted());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[YtPlayer] Playback error: " + e.getMessage());
                e.printStackTrace();
                sendChat("§c[YT] Playback error: " + e.getMessage());
            } finally {
                playing.set(false);
                if (!loopActive.get()) currentTitle.set(null);
            }
        }, "YtPlayer-Thread");
        playThread.setDaemon(true);
        playThread.start();
    }

    public void stop() {
        stopInternal();
        ThemeSongPlayer.stop();
    }

    public boolean isPlaying()       { return playing.get(); }
    public String  getCurrentTitle() { return currentTitle.get(); }

    /**
     * Downloads the top result for {@code result} to the songs folder.
     *
     * @param result     the video to download
     * @param customName filename to save as (without extension), or {@code null}
     *                   to use the sanitized video title
     */
    public void download(YtSearch.YtResult result, String customName) {
        new Thread(() -> {
            try {
                String saveName = (customName != null && !customName.isBlank())
                        ? sanitizeFilename(customName)
                        : sanitizeFilename(result.title());
                sendChat("§e[YT] Downloading: §f" + result.title()
                        + (customName != null ? " §e→ §f" + saveName : "") + "§e...");
                String audioUrl = resolveAudioUrl(result.url());
                Path dest = ThemeSongPlayer.getSongsDir().resolve(saveName + ".mp3");
                Files.createDirectories(dest.getParent());
                downloadToFile(audioUrl, dest);
                sendChat("§a[YT] Saved: §f" + dest.getFileName()
                        + " §7— play with §f/ts play " + saveName);
            } catch (Exception e) {
                sendChat("§c[YT] Download failed: " + e.getMessage());
                System.err.println("[YtPlayer] Download error: " + e.getMessage());
                e.printStackTrace();
            }
        }, "YtDownload-Thread").start();
    }

    /** Backwards-compatible overload: uses the video title as the filename. */
    public void download(YtSearch.YtResult result) {
        download(result, null);
    }

    /**
     * Copies the cached file for the most recently played video to the songs
     * folder under {@code name} (without extension).
     *
     * @return {@code null} on success, or a user-visible error string on failure
     */
    public String saveCurrentToSongs(String name) {
        String videoId = currentVideoId.get();
        if (videoId == null) {
            return "No YouTube track has been played yet this session.";
        }
        Path cached = getCachedFile(videoId);
        if (cached == null) {
            return "Cached file not found for the current track. Try playing it first with /ts yt pick.";
        }
        String safeName = sanitizeFilename(name);
        if (safeName.isEmpty()) {
            return "Invalid filename: §f" + name;
        }
        Path dest = ThemeSongPlayer.getSongsDir().resolve(safeName + ".mp3");
        try {
            Files.createDirectories(dest.getParent());
            Files.copy(cached, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return null; // success
        } catch (Exception e) {
            return "Could not save file: " + e.getMessage();
        }
    }

    public String getCacheSizeString() {
        try {
            Path dir = getCacheDir();
            if (!Files.exists(dir)) return "0 MB";
            long bytes = Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0L; } })
                    .sum();
            return String.format("%.1f MB", bytes / 1_048_576.0);
        } catch (Exception e) { return "unknown"; }
    }

    public void clearCache() throws Exception {
        Path dir = getCacheDir();
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .filter(Files::isRegularFile)
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private void stopInternal() {
        loopActive.set(false);
        playing.set(false);
        currentVideoId.set(null);
        if (playThread != null) {
            playThread.interrupt();
            playThread = null;
        }
    }

    private void streamAndCache(YtSearch.YtResult result) throws Exception {
        // Resolve a fresh audio URL every time — YouTube stream URLs expire after ~6 h.
        String audioUrl = resolveAudioUrl(result.url());

        Path cacheDir  = getCacheDir();
        Files.createDirectories(cacheDir);
        Path tempFile  = cacheDir.resolve(result.videoId() + ".tmp");
        Path finalFile = cacheDir.resolve(result.videoId() + ".mp3");

        Files.deleteIfExists(tempFile);

        AtomicBoolean downloadDone = new AtomicBoolean(false);
        Thread downloadThread = new Thread(() -> {
            try {
                downloadToFile(audioUrl, tempFile);
                Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                evictCacheIfNeeded();
            } catch (Exception e) {
                System.err.println("[YtPlayer] Cache write error: " + e.getMessage());
            } finally {
                downloadDone.set(true);
            }
        }, "YtCache-Thread");
        downloadThread.setDaemon(true);
        downloadThread.start();

        // Wait up to 8 s for at least 256 KB to buffer (slightly longer than before
        // to accommodate slower mobile connections).
        int waited = 0;
        while (waited < 8000) {
            if (Files.exists(tempFile) && Files.size(tempFile) > 256_000) break;
            if (downloadDone.get()) break; // download finished (maybe a small file)
            Thread.sleep(100);
            waited += 100;
        }

        Path playFrom = Files.exists(tempFile) ? tempFile : finalFile;
        if (Files.exists(playFrom)) {
            ThemeSongPlayer.playFile(playFrom);
            waitForPlayback(playFrom);
        } else {
            throw new IOException("[YT] Audio file not available for playback after buffering.");
        }

        downloadThread.join(60_000);
    }

    /**
     * Resolves a fresh, playable direct audio-stream URL for {@code videoUrl}.
     *
     * <p>We re-initialise NewPipe on every call. This is intentional: the
     * extractor caches internal YouTube state (player JS, cipher functions) that
     * becomes stale after a few hours and causes "The page needs to be reloaded"
     * errors. A fresh {@link YtDownloader} instance avoids reusing any such
     * cached state from a previous session.</p>
     *
     * <p>We then send a lightweight HEAD request (via {@link HttpURLConnection},
     * not {@code HttpClient}) to each candidate URL to skip dead or expired ones
     * before returning the first live URL.</p>
     */
    private String resolveAudioUrl(String videoUrl) throws Exception {
        NewPipe.init(new YtDownloader());

        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl);

        List<AudioStream> streams = info.getAudioStreams();
        if (streams == null || streams.isEmpty()) {
            throw new IOException("No audio streams found for: " + videoUrl);
        }

        // Sort ascending by bitrate to minimise data usage on mobile.
        List<AudioStream> sorted = streams.stream()
                .filter(s -> {
                    try { return s.getContent() != null && !s.getContent().isBlank(); }
                    catch (Exception e) { return false; }
                })
                .sorted(Comparator.comparingInt(s -> {
                    try { return s.getBitrate(); } catch (Exception e) { return Integer.MAX_VALUE; }
                }))
                .toList();

        if (sorted.isEmpty()) {
            throw new IOException("All stream URLs were empty. YouTube may have blocked extraction.");
        }

        // Verify each URL is reachable with a HEAD request (HttpURLConnection).
        for (AudioStream stream : sorted) {
            String url = stream.getContent();
            try {
                HttpURLConnection head = YtDownloader.openConnection(url, "HEAD", null, null);
                head.setConnectTimeout(5_000); // shorter timeout for probing
                head.setReadTimeout(5_000);
                int code = head.getResponseCode();
                head.disconnect();
                if (code < 400) return url;
            } catch (Exception ignored) {
                // timeout, connection refused, or any other error — skip to next URL
            }
        }

        // Fallback: return first URL without verification
        return sorted.get(0).getContent();
    }

    /**
     * Downloads the content at {@code url} to {@code dest} using chunked I/O.
     * Uses {@link HttpURLConnection} for Android JRE compatibility.
     */
    static void downloadToFile(String url, Path dest) throws Exception {
        HttpURLConnection conn = YtDownloader.openConnection(url, "GET", null, null);
        int code = conn.getResponseCode();
        if (code >= 400) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " downloading " + url);
        }

        try (InputStream  in  = new BufferedInputStream(conn.getInputStream(), 8192);
             OutputStream out = Files.newOutputStream(dest,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            conn.disconnect();
        }
    }

    private void waitForPlayback(Path file) throws InterruptedException {
        long fileSizeBytes = 0;
        try { fileSizeBytes = Files.size(file); } catch (Exception ignored) {}
        // ~128 kbps audio ≈ 16 KB/s
        long estimatedMs = (fileSizeBytes > 0) ? (fileSizeBytes / 16_000) * 1000L : 300_000L;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < estimatedMs) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!loopActive.get() && !ThemeSongPlayer.isPlaying()) break;
            Thread.sleep(500);
        }
    }

    private Path getCachedFile(String videoId) {
        Path f = getCacheDir().resolve(videoId + ".mp3");
        return Files.exists(f) ? f : null;
    }

    private Path getCacheDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(CACHE_FOLDER);
    }

    private void evictCacheIfNeeded() {
        try {
            Path dir = getCacheDir();
            if (!Files.exists(dir)) return;
            List<Path> files = Files.walk(dir)
                    .filter(p -> p.toString().endsWith(".mp3"))
                    .sorted(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (Exception e) { return 0L; }
                    }))
                    .toList();
            long total = files.stream().mapToLong(p -> {
                try { return Files.size(p); } catch (Exception e) { return 0L; }
            }).sum();
            for (Path f : files) {
                if (total <= MAX_CACHE_BYTES) break;
                long size = Files.size(f);
                Files.delete(f);
                total -= size;
            }
        } catch (Exception e) {
            System.err.println("[YtPlayer] Cache eviction error: " + e.getMessage());
        }
    }

    private static String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static void sendChat(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.execute(() -> {
                if (client.inGameHud != null) {
                    client.inGameHud.getChatHud().addMessage(Text.literal(msg));
                }
            });
        }
    }
}
