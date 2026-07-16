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

    private static final long   MAX_CACHE_BYTES = 100L * 1024 * 1024;
    private static final String CACHE_FOLDER    = "stabshot/yt_cache";
    private static final String TAG             = "[StabShot/YT]";
    public  static volatile boolean DEBUG = false;

    private static YtPlayer instance;
    public static YtPlayer getInstance() {
        if (instance == null) instance = new YtPlayer();
        return instance;
    }
    private YtPlayer() {}

    private final AtomicBoolean           loopActive   = new AtomicBoolean(false);
    private final AtomicReference<String> currentTitle = new AtomicReference<>(null);
    private final AtomicBoolean           playing      = new AtomicBoolean(false);
    private Thread                        playThread;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public void play(YtSearch.YtResult result, boolean loop) {
        log("play() called: " + result.title() + " loop=" + loop);
        stopInternal();
        loopActive.set(loop);
        currentTitle.set(result.title());
        playing.set(true);

        playThread = new Thread(() -> {
            log("YtPlayer thread started");
            try {
                do {
                    Path cached = getCachedFile(result.videoId());
                    if (cached != null) {
                        log("Using cached file: " + cached);
                        ThemeSongPlayer.playFile(cached);
                        waitForPlayback(cached);
                    } else {
                        log("No cache found, streaming from YouTube");
                        streamAndCache(result);
                    }
                } while (loopActive.get() && !Thread.currentThread().isInterrupted());
            } catch (InterruptedException e) {
                log("YtPlayer thread interrupted (expected on stop)");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log("ERROR in YtPlayer thread: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
                sendChat("§c[YT] Playback error: " + e.getMessage());
            } finally {
                playing.set(false);
                if (!loopActive.get()) currentTitle.set(null);
                log("YtPlayer thread finished");
            }
        }, "YtPlayer-Thread");
        playThread.setDaemon(true);
        playThread.start();
        log("YtPlayer thread started");
    }

    public void stop() {
        log("stop() called");
        stopInternal();
        ThemeSongPlayer.stop();
    }

    public boolean isPlaying()       { return playing.get(); }
    public String  getCurrentTitle() { return currentTitle.get(); }

    public void download(YtSearch.YtResult result) {
        log("download() called: " + result.title());
        new Thread(() -> {
            try {
                sendChat("§e[YT] Downloading: §f" + result.title() + "§e...");
                log("Resolving audio URL for download: " + result.url());
                String audioUrl = resolveAudioUrl(result.url());
                log("Audio URL resolved: " + audioUrl.substring(0, Math.min(80, audioUrl.length())) + "...");
                Path dest = ThemeSongPlayer.getSongsDir()
                        .resolve(sanitizeFilename(result.title()) + ".mp3");
                Files.createDirectories(dest.getParent());
                log("Downloading to: " + dest);
                downloadToFile(audioUrl, dest);
                log("Download complete: " + dest);
                sendChat("§a[YT] Saved to songs folder: §f" + dest.getFileName());
            } catch (Exception e) {
                log("ERROR in download: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
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
        log("clearCache() called");
        Path dir = getCacheDir();
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .filter(Files::isRegularFile)
                .forEach(p -> { try { Files.delete(p); log("Deleted cache: " + p.getFileName()); }
                                catch (Exception ignored) {} });
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void stopInternal() {
        log("stopInternal() called");
        loopActive.set(false);
        playing.set(false);
        Thread t = playThread;
        if (t != null) {
            t.interrupt();
            playThread = null;
            log("YtPlayer thread interrupted");
        }
    }

    private void streamAndCache(YtSearch.YtResult result) throws Exception {
        log("streamAndCache() for: " + result.title() + " url=" + result.url());
        String audioUrl = resolveAudioUrl(result.url());
        log("Resolved audio URL: " + audioUrl.substring(0, Math.min(80, audioUrl.length())) + "...");

        Path cacheDir  = getCacheDir();
        Files.createDirectories(cacheDir);
        Path tempFile  = cacheDir.resolve(result.videoId() + ".tmp");
        Path finalFile = cacheDir.resolve(result.videoId() + ".mp3");

        Files.deleteIfExists(tempFile);
        log("Temp file: " + tempFile);

        AtomicBoolean downloadDone = new AtomicBoolean(false);
        Thread downloadThread = new Thread(() -> {
            try {
                log("Download thread started");
                downloadToFile(audioUrl, tempFile);
                Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                log("Download complete, moved to: " + finalFile);
                evictCacheIfNeeded();
            } catch (Exception e) {
                log("ERROR in download thread: " + e.getClass().getName() + ": " + e.getMessage());
                e.printStackTrace();
            } finally {
                downloadDone.set(true);
            }
        }, "YtCache-Thread");
        downloadThread.setDaemon(true);
        downloadThread.start();

        log("Waiting for 256KB buffer before starting playback...");
        int waited = 0;
        while (waited < 5000) {
            if (Files.exists(tempFile) && Files.size(tempFile) > 256_000) break;
            Thread.sleep(100);
            waited += 100;
        }
        log("Buffer wait done after " + waited + "ms, tempFile size="
                + (Files.exists(tempFile) ? Files.size(tempFile) : 0) + " bytes");

        Path playFrom = Files.exists(tempFile) ? tempFile : finalFile;
        if (Files.exists(playFrom)) {
            log("Starting playback from: " + playFrom);
            ThemeSongPlayer.playFile(playFrom);
            waitForPlayback(playFrom);
        } else {
            log("ERROR: No file to play from — temp and final both missing");
        }

        downloadThread.join(30_000);
        log("streamAndCache() complete");
    }

    private String resolveAudioUrl(String videoUrl) throws Exception {
        log("resolveAudioUrl() for: " + videoUrl);
        NewPipe.init(new YtDownloader());
        log("NewPipe initialized");

        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl);
        log("StreamInfo fetched, errors=" + info.getErrors().size());

        List<AudioStream> streams = info.getAudioStreams();
        if (streams == null || streams.isEmpty()) {
            log("ERROR: No audio streams found");
            throw new IOException("No audio streams found for: " + videoUrl);
        }
        log("Found " + streams.size() + " audio streams");

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
            log("ERROR: All stream URLs were empty or null");
            throw new IOException("All stream URLs were empty.");
        }

        log("Sorted streams: " + sorted.size() + ", picking lowest bitrate");

        HttpClient http = HttpClient.newHttpClient();
        for (AudioStream stream : sorted) {
            String url = stream.getContent();
            int bitrate = -1;
            try { bitrate = stream.getBitrate(); } catch (Exception ignored) {}
            log("Trying stream: bitrate=" + bitrate + " url=" + url.substring(0, Math.min(60, url.length())) + "...");
            try {
                HttpRequest head = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .header("User-Agent", "Mozilla/5.0")
                        .build();
                HttpResponse<Void> resp = http.send(head, HttpResponse.BodyHandlers.discarding());
                log("HEAD response status: " + resp.statusCode());
                if (resp.statusCode() < 400) {
                    log("Using stream with bitrate=" + bitrate);
                    return url;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while verifying stream URL", e);
            } catch (Exception e) {
                log("WARN: HEAD request failed for stream: " + e.getMessage());
            }
        }

        log("WARN: All HEAD checks failed, falling back to first stream");
        return sorted.get(0).getContent();
    }

    private static void downloadToFile(String url, Path dest) throws Exception {
        log("downloadToFile() to: " + dest.getFileName());
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .GET().build();

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
        log("HTTP response status: " + resp.statusCode());

        try (InputStream  in  = new BufferedInputStream(resp.body(), 8192);
             OutputStream out = Files.newOutputStream(dest,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[8192];
            int n; long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            log("Download finished: " + total + " bytes written to " + dest.getFileName());
        }
    }

    private void waitForPlayback(Path file) throws InterruptedException {
        long fileSizeBytes = 0;
        try { fileSizeBytes = Files.size(file); } catch (Exception ignored) {}
        long estimatedMs = (fileSizeBytes > 0) ? (fileSizeBytes / 16_000) * 1000 : 300_000;
        log("waitForPlayback(): estimatedMs=" + estimatedMs + " fileSize=" + fileSizeBytes);
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < estimatedMs) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (!loopActive.get() && !ThemeSongPlayer.isPlaying()) {
                log("waitForPlayback(): ThemeSongPlayer stopped early, ending wait");
                break;
            }
            Thread.sleep(500);
        }
        log("waitForPlayback() done after " + (System.currentTimeMillis() - start) + "ms");
    }

    private Path getCachedFile(String videoId) {
        Path f = getCacheDir().resolve(videoId + ".mp3");
        boolean exists = Files.exists(f);
        log("getCachedFile(): " + videoId + ".mp3 exists=" + exists);
        return exists ? f : null;
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
            log("Cache eviction check: total=" + total + " max=" + MAX_CACHE_BYTES);
            for (Path f : files) {
                if (total <= MAX_CACHE_BYTES) break;
                long size = Files.size(f);
                Files.delete(f);
                total -= size;
                log("Evicted cache file: " + f.getFileName());
            }
        } catch (Exception e) {
            log("ERROR in cache eviction: " + e.getMessage());
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

    private static void log(String msg) {
        if (DEBUG) System.out.println(TAG + " " + msg);
    }
}
