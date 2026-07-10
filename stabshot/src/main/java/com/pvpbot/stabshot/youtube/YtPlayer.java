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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Environment(EnvType.CLIENT)
public class YtPlayer {

    private static final long   MAX_CACHE_BYTES = 100L * 1024 * 1024; // 100 MB
    private static final String CACHE_FOLDER    = "stabshot/yt_cache";

    // Singleton
    private static YtPlayer instance;
    public static YtPlayer getInstance() {
        if (instance == null) instance = new YtPlayer();
        return instance;
    }
    private YtPlayer() {}

    // State
    private final AtomicBoolean           loopActive   = new AtomicBoolean(false);
    private final AtomicReference<String> currentTitle = new AtomicReference<>(null);
    private final AtomicBoolean           playing      = new AtomicBoolean(false);
    private Thread                        playThread;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void play(YtSearch.YtResult result, boolean loop) {
        stopInternal();
        loopActive.set(loop);
        currentTitle.set(result.title());
        playing.set(true);

        playThread = new Thread(() -> {
            try {
                do {
                    Path cached = getCachedFile(result.videoId());
                    if (cached != null) {
                        // Fully cached — zero data usage
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

    public void download(YtSearch.YtResult result) {
        new Thread(() -> {
            try {
                sendChat("§e[YT] Downloading: §f" + result.title() + "§e...");
                String audioUrl = resolveAudioUrl(result.url());
                Path dest = ThemeSongPlayer.getSongsDir()
                        .resolve(sanitizeFilename(result.title()) + ".mp3");
                Files.createDirectories(dest.getParent());
                downloadToFile(audioUrl, dest);
                sendChat("§a[YT] Saved to songs folder: §f" + dest.getFileName());
            } catch (Exception e) {
                sendChat("§c[YT] Download failed: " + e.getMessage());
            }
        }, "YtDownload-Thread").start();
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

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void stopInternal() {
        loopActive.set(false);
        playing.set(false);
        if (playThread != null) {
            playThread.interrupt();
            playThread = null;
        }
    }

    private void streamAndCache(YtSearch.YtResult result) throws Exception {
        // Always resolve a FRESH audio URL — never cache the URL itself, only the file
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

        // Wait up to 5s for 256 KB to buffer
        int waited = 0;
        while (waited < 5000) {
            if (Files.exists(tempFile) && Files.size(tempFile) > 256_000) break;
            Thread.sleep(100);
            waited += 100;
        }

        Path playFrom = Files.exists(tempFile) ? tempFile : finalFile;
        if (Files.exists(playFrom)) {
            ThemeSongPlayer.playFile(playFrom);
            waitForPlayback(playFrom);
        }

        downloadThread.join(30_000);
    }

    /**
     * Resolves a fresh direct audio stream URL from YouTube.
     * Called every time — YouTube stream URLs expire after ~6 hours.
     * This is the fix for "The page needs to be reloaded" — we never
     * reuse a stale URL, always get a new one from a fresh extractor.
     */
    private String resolveAudioUrl(String videoUrl) throws Exception {
        // Fresh init every call to avoid stale extractor state
        NewPipe.init(new YtDownloader());

        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl);

        List<AudioStream> streams = info.getAudioStreams();
        if (streams == null || streams.isEmpty()) {
            throw new IOException("No audio streams found for: " + videoUrl);
        }

        // Lowest bitrate = least data used on mobile
        AudioStream best = streams.stream()
                .filter(s -> {
                    try { return s.getBitrate() > 0; } catch (Exception e) { return false; }
                })
                .min(Comparator.comparingInt(s -> {
                    try { return s.getBitrate(); } catch (Exception e) { return Integer.MAX_VALUE; }
                }))
                .orElse(streams.get(0));

        String url = best.getContent();
        if (url == null || url.isBlank()) {
            throw new IOException("Stream URL was empty — YouTube may have changed their API.");
        }
        return url;
    }

    private static void downloadToFile(String url, Path dest) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }

        try (InputStream  in  = new BufferedInputStream(resp.body(), 8192);
             OutputStream out = Files.newOutputStream(dest,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private void waitForPlayback(Path file) throws InterruptedException {
        long fileSizeBytes = 0;
        try { fileSizeBytes = Files.size(file); } catch (Exception ignored) {}
        // ~128kbps = 16 KB/s
        long estimatedMs = (fileSizeBytes > 0) ? (fileSizeBytes / 16_000) * 1000 : 300_000;
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
