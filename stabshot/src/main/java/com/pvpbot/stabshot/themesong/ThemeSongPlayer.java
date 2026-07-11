package com.pvpbot.stabshot.themesong;

import javazoom.jl.decoder.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.client.sound.SoundInstance.AttenuationType;

import javax.sound.sampled.AudioFormat;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays audio files by feeding them into Minecraft's own SoundManager.
 *
 * This is the correct approach for all platforms including Android launchers
 * (ZalithLauncher, PojavLauncher, MojoLauncher) because Minecraft's SoundManager
 * already has a working OpenAL context set up — we just provide a custom
 * AudioStream that reads from disk instead of a resource pack.
 *
 * MP3 is decoded frame-by-frame via JLayer into PCM and fed through a custom
 * AudioStream implementation. OGG uses Minecraft's built-in OggAudioStream.
 *
 * Loop mode works by a background thread that sleeps for the song's estimated
 * duration then re-submits the SoundInstance to the SoundManager — identical
 * to how the original working version operated.
 */
@Environment(EnvType.CLIENT)
public class ThemeSongPlayer {

    public static final String SONGS_FOLDER = "stabshot/songs";

    private static final Identifier  DUMMY_ID    = Identifier.of("stabshot", "dummy");
    private static final SoundEvent  DUMMY_EVENT = SoundEvent.of(DUMMY_ID);

    private static DiskSoundInstance currentInstance = null;
    private static String            currentSong     = null;
    private static boolean           playing         = false;
    private static boolean           looping         = false;
    private static Thread            loopThread      = null;
    private static final AtomicBoolean loopActive    = new AtomicBoolean(false);

    // ── Public API ────────────────────────────────────────────────────────────

    public static String play(String name, boolean loop) {
        stop();

        Path songsDir = getSongsDir();
        Path   file = null;
        String ext  = null;
        for (String e : new String[]{"ogg", "mp3"}) {
            Path candidate = songsDir.resolve(name + "." + e);
            if (Files.exists(candidate)) { file = candidate; ext = e; break; }
        }
        if (file == null) {
            return "§cSong not found: §f" + name + ".ogg §7or §f" + name + ".mp3\n"
                 + "§7Put audio files in: §f" + songsDir + "\n"
                 + "§7Available: §f" + String.join(", ", getSongNames());
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return "Client not ready.";

        currentSong = name;
        playing     = true;
        looping     = loop;

        final Path   fFile = file;
        final String fExt  = ext;

        if (loop) {
            loopActive.set(true);
            loopThread = new Thread(() -> {
                while (loopActive.get()) {
                    // Stop whatever is currently playing
                    client.execute(() -> {
                        synchronized (ThemeSongPlayer.class) {
                            if (currentInstance != null) {
                                client.getSoundManager().stop(currentInstance);
                            }
                        }
                    });

                    long durationMs = estimateDurationMs(fFile, fExt);
                    if (durationMs <= 0) durationMs = 3000L;

                    DiskSoundInstance inst = new DiskSoundInstance(fFile, fExt);
                    client.execute(() -> {
                        synchronized (ThemeSongPlayer.class) {
                            currentInstance = inst;
                        }
                        client.getSoundManager().play(inst);
                    });

                    try {
                        Thread.sleep(durationMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "StabShot-LoopThread");
            loopThread.setDaemon(true);
            loopThread.start();

        } else {
            DiskSoundInstance inst = new DiskSoundInstance(fFile, fExt);
            client.execute(() -> {
                try {
                    synchronized (ThemeSongPlayer.class) {
                        currentInstance = inst;
                    }
                    client.getSoundManager().play(inst);
                } catch (Exception e) {
                    playing = false;
                    if (client.inGameHud != null) {
                        client.inGameHud.getChatHud().addMessage(
                                net.minecraft.text.Text.literal("§c[StabShot] Play error: " + e.getMessage()));
                    }
                }
            });
        }

        return null;
    }

    /**
     * Used by YtPlayer to play a cached audio file through the same MC sound engine.
     */
    public static void playFile(Path file) {
        stop();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        String lower = file.getFileName().toString().toLowerCase();
        String ext   = lower.endsWith(".mp3") ? "mp3" : "ogg";

        currentSong = file.getFileName().toString();
        playing     = true;
        looping     = false;

        DiskSoundInstance inst = new DiskSoundInstance(file, ext);
        client.execute(() -> {
            synchronized (ThemeSongPlayer.class) {
                currentInstance = inst;
            }
            client.getSoundManager().play(inst);
        });
    }

    public static void stop() {
        loopActive.set(false);

        if (loopThread != null) {
            loopThread.interrupt();
            loopThread = null;
        }

        if (currentInstance != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                DiskSoundInstance inst = currentInstance;
                client.execute(() -> client.getSoundManager().stop(inst));
            }
            currentInstance = null;
        }

        currentSong = null;
        playing     = false;
        looping     = false;
    }

    public static boolean isPlaying()      { return playing; }
    public static boolean isLooping()      { return looping; }
    public static String  getCurrentSong() { return currentSong; }

    public static List<String> getSongNames() {
        List<String> names = new ArrayList<>();
        Path dir = getSongsDir();
        if (!Files.exists(dir)) return names;
        File[] files = dir.toFile().listFiles(f -> {
            String n = f.getName().toLowerCase();
            return f.isFile() && (n.endsWith(".ogg") || n.endsWith(".mp3"));
        });
        if (files == null) return names;
        for (File f : files) {
            String n = f.getName(); int dot = n.lastIndexOf('.');
            names.add(dot > 0 ? n.substring(0, dot) : n);
        }
        Collections.sort(names);
        return names;
    }

    public static Path getSongsDir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(SONGS_FOLDER);
        try { if (!Files.exists(dir)) Files.createDirectories(dir); } catch (Exception ignored) {}
        return dir;
    }

    // ── Duration estimation (same logic as original) ──────────────────────────

    private static long estimateDurationMs(Path file, String ext) {
        try {
            if ("mp3".equals(ext)) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    Bitstream bs    = new Bitstream(in);
                    Header    first = bs.readFrame();
                    if (first == null) return 0L;
                    int bitrate = first.bitrate();
                    bs.closeFrame();
                    bs.close();
                    if (bitrate <= 0) return 0L;
                    return Files.size(file) * 8000L / bitrate;
                }
            } else {
                // OGG: use Minecraft's OggAudioStream to measure total PCM bytes
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                    net.minecraft.client.sound.OggAudioStream ogg =
                            new net.minecraft.client.sound.OggAudioStream(in);
                    AudioFormat fmt = ogg.getFormat();
                    int sr = (int) fmt.getSampleRate();
                    int ch = fmt.getChannels();
                    long totalBytes = 0L;
                    ByteBuffer buf;
                    while ((buf = ogg.getBuffer(8192)) != null && buf.remaining() > 0) {
                        totalBytes += buf.remaining();
                    }
                    ogg.close();
                    if (sr <= 0 || ch <= 0) return 0L;
                    return totalBytes * 1000L / ((long)(sr * ch) * 2L);
                }
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    // ── DiskSoundInstance — feeds audio from disk into MC's SoundManager ──────

    @Environment(EnvType.CLIENT)
    static class DiskSoundInstance extends AbstractSoundInstance implements FabricSoundInstance {

        private final Path   filePath;
        private final String ext;

        DiskSoundInstance(Path filePath, String ext) {
            super(DUMMY_EVENT, SoundCategory.MASTER, net.minecraft.util.math.random.Random.create());
            this.filePath        = filePath;
            this.ext             = ext;
            this.volume          = 1.0f;
            this.pitch           = 1.0f;
            this.repeat          = false;
            this.repeatDelay     = 0;
            this.relative        = true;
            this.attenuationType = AttenuationType.NONE;
        }

        @Override
        public CompletableFuture<AudioStream> getAudioStream(SoundLoader loader, Identifier id, boolean repeatInstantly) {
            try {
                InputStream in = new BufferedInputStream(Files.newInputStream(filePath));
                AudioStream stream = "mp3".equals(ext)
                        ? new Mp3AudioStream(in)
                        : new net.minecraft.client.sound.OggAudioStream(in);
                return CompletableFuture.completedFuture(stream);
            } catch (IOException e) {
                return CompletableFuture.failedFuture(
                        new RuntimeException("StabShot: can't open audio: " + filePath + " — " + e.getMessage(), e));
            }
        }
    }

    // ── Mp3AudioStream — JLayer MP3 → PCM for MC's sound engine ──────────────

    @Environment(EnvType.CLIENT)
    static class Mp3AudioStream implements AudioStream {

        private final Bitstream bitstream;
        private final Decoder   decoder;
        private byte[] overflow    = new byte[0];
        private int    overflowPos = 0;
        private int    sampleRate  = 44100;
        private int    channels    = 2;
        private boolean headerRead = false;

        Mp3AudioStream(InputStream in) {
            this.bitstream = new Bitstream(in);
            this.decoder   = new Decoder();
        }

        @Override
        public ByteBuffer getBuffer(int size) throws IOException {
            // Fill overflow buffer until we have enough data or EOF
            while (overflow.length - overflowPos < size && decodeNextFrame()) {}

            int available = overflow.length - overflowPos;
            if (available <= 0) return ByteBuffer.allocateDirect(0);

            int      toReturn = Math.min(size, available);
            ByteBuffer buf    = ByteBuffer.allocateDirect(toReturn);
            buf.put(overflow, overflowPos, toReturn);
            overflowPos += toReturn;
            buf.flip();
            return buf;
        }

        private boolean decodeNextFrame() throws IOException {
            try {
                Header header = bitstream.readFrame();
                if (header == null) return false;

                if (!headerRead) {
                    sampleRate = header.frequency();
                    channels   = (header.mode() == 3) ? 1 : 2; // mode 3 = mono
                    headerRead = true;
                }

                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                bitstream.closeFrame();

                short[] samples   = output.getBuffer();
                int     count     = output.getBufferLength();
                int     remaining = overflow.length - overflowPos;
                byte[]  newBuf    = new byte[remaining + count * 2];

                if (remaining > 0) System.arraycopy(overflow, overflowPos, newBuf, 0, remaining);

                int off = remaining;
                for (int i = 0; i < count; i++) {
                    short s = samples[i];
                    newBuf[off++] = (byte)  (s & 0xFF);
                    newBuf[off++] = (byte) ((s >> 8) & 0xFF);
                }
                overflow    = newBuf;
                overflowPos = 0;
                return true;

            } catch (BitstreamException e) {
                if (e.getErrorCode() == 260) return false; // 260 = BitstreamErrors.STREAM_EOF
                throw new IOException("MP3 bitstream error: " + e.getMessage(), e);
            } catch (DecoderException e) {
                throw new IOException("MP3 decoder error: " + e.getMessage(), e);
            }
        }

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate, 16, channels, channels * 2, sampleRate, false);
        }

        @Override
        public void close() throws IOException {
            try { bitstream.close(); } catch (BitstreamException ignored) {}
        }
    }
}
